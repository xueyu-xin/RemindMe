package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.config.WeatherConfig;
import com.dormrepair.remindme.dto.response.CitySearchResult;
import com.dormrepair.remindme.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 位置服务
 * <p>
 * 集成两类位置能力：
 * 1. 高德地图逆地理编码（经纬度 → "省-市-区"地址）
 * 2. 和风天气城市搜索 API（城市名 → 经纬度/LocationID）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final WeatherConfig weatherConfig;

    @Value("${amap.api.key:}")
    private String amapKey;

    /**
     * 逆地理编码：根据经纬度获取"省-市-区"格式地址
     */
    public String reverseGeocode(Double latitude, Double longitude) {
        // 检查 Key 是否配置
        if (amapKey == null || amapKey.isBlank()) {
            log.warn("高德地图 API Key 未配置，返回默认地址");
            return "广东省-广州市-天河区";
        }

        // 拼接请求地址（高德要求：经度在前，纬度在后）
        String url = String.format(
                "https://restapi.amap.com/v3/geocode/regeo?location=%f,%f&key=%s&output=json",
                longitude, latitude, amapKey
        );

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            // 检查高德返回状态
            int status = root.path("status").asInt(0);
            if (status != 1) {
                log.error("高德逆地理编码失败: status={}, info={}", status, root.path("info").asText());
                throw new RuntimeException("逆地理编码失败: " + root.path("info").asText());
            }

            JsonNode regeo = root.path("regeocode").path("addressComponent");
            String province = regeo.path("province").asText("");
            String city = regeo.path("city").asText("");
            String district = regeo.path("district").asText("");

            // 直辖市 city 可能为空，用 province 代替
            if (city.isBlank()) {
                city = province;
            }

            String address = province + "-" + city + "-" + district;
            log.info("逆地理编码结果: ({},{}) -> {}", latitude, longitude, address);
            return address;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("逆地理编码异常: latitude={}, longitude={}", latitude, longitude, e);
            throw new RuntimeException("逆地理编码异常: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户当前地理位置
     * 有GPS坐标则调用高德API转地址并更新默认城市，否则返回数据库中的默认城市
     */
    @AgentTool(name = "get_location", description = "获取用户当前地理位置，如果有GPS坐标则调用高德API转地址，否则返回数据库中的默认城市")
    public Map<String, Object> getLocation(Long userId, Double latitude, Double longitude) {
        Map<String, Object> result = new HashMap<>();

        // 有GPS坐标：逆地理编码 + 更新默认城市
        if (latitude != null && longitude != null) {
            try {
                String address = reverseGeocode(latitude, longitude);
                userService.updateDefaultCity(userId, address);
                result.put("success", true);
                result.put("address", address);
                result.put("source", "gps");
                log.info("GPS定位成功: userId={}, address={}", userId, address);
            } catch (Exception e) {
                log.error("GPS定位失败: userId={}", userId, e);
                result.put("success", false);
                result.put("message", "定位失败: " + e.getMessage());
            }
            return result;
        }

        // 无GPS坐标：从数据库读取默认城市
        try {
            User user = userService.getUserById(userId).orElse(null);
            if (user != null && user.getCity() != null && !user.getCity().isBlank()) {
                result.put("success", true);
                result.put("address", user.getCity());
                result.put("source", "database");
            } else {
                result.put("success", false);
                result.put("message", "未设置默认城市，请在设置中配置");
            }
        } catch (Exception e) {
            log.error("查询用户默认城市失败: userId={}", userId, e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 和风天气城市搜索 ====================

    /**
     * 搜索城市（调用和风天气城市搜索API）
     * <p>
     * 调用 /geo/v2/city/lookup 接口，返回匹配的城市列表
     * </p>
     *
     * @param keyword 搜索关键词，最多返回5条
     * @return 城市搜索结果列表，查询失败返回空列表
     */
    public List<CitySearchResult> searchCities(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String url = buildWeatherUrl("/geo/v2/city/lookup")
                    + "?location=" + encode(keyword)
                    + "&range=cn"
                    + "&number=5";

            log.debug("调用和风城市搜索API: keyword={}, url={}", keyword, url);
            String response = httpGet(url);

            if (response == null) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response);
            String code = root.has("code") ? root.get("code").asText() : "";

            if (!"200".equals(code)) {
                log.warn("和风城市搜索API返回失败: keyword={}, code={}", keyword, code);
                return Collections.emptyList();
            }

            JsonNode locations = root.get("location");
            if (locations == null || !locations.isArray() || locations.size() == 0) {
                log.info("城市搜索无匹配结果: keyword={}", keyword);
                return Collections.emptyList();
            }

            List<CitySearchResult> results = new ArrayList<>();
            for (JsonNode item : locations) {
                CitySearchResult result = new CitySearchResult();
                result.setName(item.path("name").asText(""));
                result.setAdm1(item.path("adm1").asText(""));
                result.setAdm2(item.path("adm2").asText(""));
                result.setId(item.path("id").asText(""));
                result.setLat(item.path("lat").asText(""));
                result.setLon(item.path("lon").asText(""));
                results.add(result);
            }
            log.info("城市搜索成功: keyword={}, count={}", keyword, results.size());
            return results;

        } catch (Exception e) {
            log.warn("调用和风城市搜索API失败: keyword={}, error={}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将城市名转换为经纬度（供 WeatherService 调用）
     * <p>
     * 处理流程：
     * 1. 已为经纬度格式（如 "113.2644,23.1291"）→ 直接返回
     * 2. "省-市-区"格式 → 提取市部分用于搜索
     * 3. 调用和风城市搜索API，取第一条结果的经纬度
     * 4. 转换失败 → 返回原值（降级处理，由和风天气API自行处理）
     * </p>
     *
     * @param location 城市名或经纬度
     * @return 经纬度字符串（格式"经度,纬度"），转换失败返回原值
     */
    public String resolveLocationToCoord(String location) {
        // 1. null/空 → 返回默认城市"广州"
        if (location == null || location.isBlank()) {
            return "广州";
        }

        // 2. 已为经纬度格式，直接返回
        if (isCoordinate(location)) {
            log.debug("已是经纬度格式，直接返回: {}", location);
            return location;
        }

        // 3. "省-市-区"格式 → 提取市部分
        String query = location;
        if (query.contains("-")) {
            String[] parts = query.split("-");
            if (parts.length >= 2) {
                query = parts[1];
            }
        }

        // 4. 调用城市搜索API，取第一条结果的经纬度
        List<CitySearchResult> results = searchCities(query);
        if (!results.isEmpty()) {
            CitySearchResult first = results.get(0);
            if (!first.getLon().isBlank() && !first.getLat().isBlank()) {
                String coord = first.getLon() + "," + first.getLat();
                log.info("城市名转换成功: {} → {} ({})", location, coord, first.getDisplayName());
                return coord;
            }
        }

        // 5. 转换失败，返回原值（降级处理）
        log.warn("城市名转换失败，使用原值: {}", location);
        return location;
    }

    /**
     * 判断字符串是否为经纬度格式（如 "113.2644,23.1291"）
     */
    private boolean isCoordinate(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        return location.matches("^[-+]?\\d+\\.?\\d*,[-+]?\\d+\\.?\\d*$");
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 HTTP GET 请求（和风天气API）
     * <p>
     * 使用原生 HttpURLConnection + GZIP 解压，与 WeatherService 保持一致，
     * 避免 RestTemplate 对 gzip 响应处理不当导致 400 错误
     * </p>
     */
    private String httpGet(String urlStr) {
        try {
            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();

            // 设置请求头
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("X-QW-Api-Key", weatherConfig.getKey());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            int responseCode = conn.getResponseCode();
            log.debug("HTTP响应码: {}", responseCode);

            if (responseCode == 200 || responseCode == 304) {
                // 读取响应，自动处理 gzip 解压
                String contentEncoding = conn.getHeaderField("Content-Encoding");
                BufferedReader reader;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    reader = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(conn.getInputStream()), StandardCharsets.UTF_8));
                } else {
                    reader = new BufferedReader(new InputStreamReader(
                            conn.getInputStream(), StandardCharsets.UTF_8));
                }

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String json = sb.toString();
                log.debug("和风API响应: {}", json);
                return json;
            } else {
                // 错误响应也要解压，避免乱码
                String errorMsg = readErrorStream(conn);
                log.error("HTTP错误: code={}, message={}", responseCode, errorMsg);
                throw new RuntimeException("HTTP请求失败: " + responseCode);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("HTTP请求异常: url={}", urlStr, e);
            throw new RuntimeException("HTTP请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取错误流（自动处理 gzip 解压）
     */
    private String readErrorStream(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() != null) {
                String contentEncoding = conn.getHeaderField("Content-Encoding");
                BufferedReader reader;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    reader = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(conn.getErrorStream()), StandardCharsets.UTF_8));
                } else {
                    reader = new BufferedReader(new InputStreamReader(
                            conn.getErrorStream(), StandardCharsets.UTF_8));
                }

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return "Unknown error";
    }

    /**
     * 构建和风天气API URL
     */
    private String buildWeatherUrl(String path) {
        return weatherConfig.getHost() + path;
    }

    /**
     * URL 编码（处理中文等特殊字符）
     */
    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
