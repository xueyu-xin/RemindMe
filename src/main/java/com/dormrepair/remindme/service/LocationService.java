package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.config.WeatherConfig;
import com.dormrepair.remindme.dto.response.CitySearchResult;
import com.dormrepair.remindme.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

// 位置服务：封装和风天气城市搜索，
// 对 Agent 暴露 get_location（当前定位）和 geocode_city（城市名转坐标）两个工具。
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final WeatherConfig weatherConfig;

    // 获取用户当前地理位置：有 GPS 就用和风城市搜索反查标准格式地址+经纬度（不修改用户默认城市），
    // 没有 GPS 则回退到数据库里的默认城市，并尽量通过城市搜索补出经纬度，
    // 保证返回结果能直接喂给天气、空气质量等后续工具。
    @AgentTool(name = "get_location", description = "获取用户当前地理位置。有GPS时返回实时定位地址+经纬度；没GPS时返回我的页面设置的默认城市+默认城市的经纬度。只在用户问【我在哪】、【当前位置是哪】、以及任何提到「当前位置」的查询时调用。用户没提到当前位置就不要调这个工具。")
    public Map<String, Object> getLocation(Long userId, Double latitude, Double longitude) {
        Map<String, Object> result = new HashMap<>();

        // 有GPS坐标：用和风城市搜索反查标准格式地址 + 直接返回 GPS 经纬度（不修改用户默认城市设置）
        if (latitude != null && longitude != null) {
            try {
                // 和风城市搜索支持传经纬度（格式：经度,纬度）
                String coord = longitude + "," + latitude;
                List<CitySearchResult> hits = searchCities(coord);
                if (hits.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "无法解析当前位置对应的城市");
                } else {
                    String displayName = hits.get(0).getDisplayName();
                    result.put("success", true);
                    result.put("address", displayName);
                    result.put("source", "gps");
                    result.put("latitude", latitude);
                    result.put("longitude", longitude);
                    log.info("GPS定位成功: userId={}, address={}, coord=({}, {})", userId, displayName, latitude, longitude);
                }
            } catch (Exception e) {
                log.error("GPS定位失败: userId={}", userId, e);
                result.put("success", false);
                result.put("message", "定位失败: " + e.getMessage());
            }
            return result;
        }

        // 无GPS坐标：从数据库读取默认城市 → 再通过和风天气搜索把默认城市名转成经纬度
        try {
            User user = userService.getUserById(userId).orElse(null);
            if (user != null && user.getCity() != null && !user.getCity().isBlank()) {
                result.put("success", true);
                result.put("address", user.getCity());
                result.put("source", "database (defaultCity)");
                // 把默认城市名转换成经纬度（兜底，失败就 null）
                try {
                    String coord = resolveLocationToCoord(user.getCity());
                    if (coord != null && coord.contains(",")) {
                        String[] parts = coord.split(",");
                        result.put("longitude", Double.parseDouble(parts[0].trim()));
                        result.put("latitude", Double.parseDouble(parts[1].trim()));
                        log.info("默认城市坐标转换: userId={}, city={} -> ({}, {})",
                                userId, user.getCity(), result.get("latitude"), result.get("longitude"));
                    }
                } catch (Exception e) {
                    log.warn("默认城市转换坐标失败，只返回地址不返回经纬度: userId={}, city={}, error={}",
                            userId, user.getCity(), e.getMessage());
                }
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

    // 调此工具拿到真实坐标，再传给天气/空气质量类工具
    @AgentTool(name = "geocode_city", description = "把具体城市名解析成经纬度。用户明确提到了一个具体城市名（上海、广州、北京…）时，调用这个工具拿真实坐标，再用坐标查天气/空气质量。禁止自己脑补。")
    public Map<String, Object> geocodeCity(String city) {
        Map<String, Object> result = new HashMap<>();
        if (city == null || city.isBlank()) {
            result.put("success", false);
            result.put("message", "城市名为空");
            return result;
        }
        try {
            String coord = resolveLocationToCoord(city);
            if (coord == null || !coord.contains(",")) {
                result.put("success", false);
                result.put("message", "找不到该城市的坐标: " + city);
                return result;
            }
            String[] parts = coord.split(",");
            double lon = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());

            List<CitySearchResult> hits = searchCities(city);
            String displayName = (hits != null && !hits.isEmpty()) ? hits.get(0).getDisplayName() : city;

            result.put("success", true);
            result.put("city", displayName);
            result.put("latitude", lat);
            result.put("longitude", lon);
            log.info("geocode_city: {} -> ({}, {})", city, lat, lon);
            return result;
        } catch (Exception e) {
            log.warn("geocode_city 转换失败: city={}, error={}", city, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "城市坐标转换失败: " + e.getMessage());
            return result;
        }
    }

    // ==================== 和风天气城市搜索 ====================

    // 调用和风天气 /geo/v2/city/lookup 接口搜索城市，最多返回 5 条匹配结果，
    // 查询失败或无匹配时返回空列表。
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

    // 城市名转经纬度（WeatherService 也会调）：
    // 1. 入参已是经纬度格式 → 原样返回；
    // 2. 其他格式（城市名、"省-市-区"等）→ 直接调和风城市搜索取第一条结果的经纬度（和风API支持模糊搜索）；
    // 3. 都拿不到 → 返回原值，交给和风天气 API 自行兜底。
    // 返回格式为"经度,纬度"。
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

        // 3. 直接用原始字符串调用城市搜索API（和风API支持模糊搜索，能处理各种格式输入）
        List<CitySearchResult> results = searchCities(location);
        if (!results.isEmpty()) {
            CitySearchResult first = results.get(0);
            if (!first.getLon().isBlank() && !first.getLat().isBlank()) {
                String coord = first.getLon() + "," + first.getLat();
                log.info("城市名转换成功: {} → {} ({})", location, coord, first.getDisplayName());
                return coord;
            }
        }

        // 4. 转换失败，返回原值（降级处理）
        log.warn("城市名转换失败，使用原值: {}", location);
        return location;
    }

    // 判断字符串是否为经纬度格式（如 "113.2644,23.1291"）
    private boolean isCoordinate(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        return location.matches("^[-+]?\\d+\\.?\\d*,[-+]?\\d+\\.?\\d*$");
    }

    // ==================== HTTP 工具方法 ====================

    // 和风天气 API 的 HTTP GET 请求：用原生 HttpURLConnection + 手动 gzip 解压，
    // 与 WeatherService 保持一致，规避 RestTemplate 处理 gzip 响应时的 400 问题。
    // 带重试：仅 SocketTimeoutException，重试前重建连接
    private String httpGet(String urlStr) {
        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            HttpURLConnection conn = null;
            try {
                URI uri = URI.create(urlStr);
                conn = (HttpURLConnection) uri.toURL().openConnection();

                // 设置请求头
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(weatherConfig.getConnectTimeout());
                conn.setReadTimeout(weatherConfig.getReadTimeout());
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
            } catch (SocketTimeoutException e) {
                lastException = e;
                log.warn("LocationService httpGet 超时，第 {} 次重试: {}", attempt, e.getMessage());

                if (attempt >= maxRetries) {
                    break;
                }

                long waitMs = (long) (Math.pow(2, attempt - 1) * 100);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("HTTP请求异常: url={}", urlStr, e);
                throw new RuntimeException("HTTP请求失败: " + e.getMessage(), e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        throw new RuntimeException("LocationService httpGet 调用失败，已重试 " + maxRetries + " 次", lastException);
    }

    // 读取错误响应流，同样处理 gzip 解压，避免错误信息乱码
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

    // 拼接和风天气 API 完整 URL
    private String buildWeatherUrl(String path) {
        return weatherConfig.getHost() + path;
    }

    // URL 编码，处理中文等特殊字符
    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    // ==================== 城市匹配（覆盖率算法） ====================

    // 从城市搜索结果中选择最佳匹配。覆盖率算法：
    // 1. 计算 keyword 与每个候选 displayName 的字符集覆盖率（去除"省/市/区/县"等后缀字符后比较）
    // 2. 取覆盖率最高的候选
    // 3. 如果最高覆盖率 < 100% → 返回失败（未完全匹配，调用方应反问用户）
    // 4. 如果最高覆盖率 = 100%：在所有 100% 覆盖的候选中按段数升序（市级优先于区县级）
    //    - 段数最短唯一 → 返回该候选
    //    - 段数最短多个 → 返回失败（歧义，调用方应反问用户）
    // 返回 Map：成功时 { success: true, displayName: "广东省-广州" }
    //          失败时 { success: false, message: "...", candidates: ["...", "..."] }
    public Map<String, Object> selectBestCityMatch(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "城市名不能为空");
            return r;
        }

        List<CitySearchResult> candidates = searchCities(keyword);
        if (candidates.isEmpty()) {
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "未找到城市：" + keyword + "，请确认城市名");
            return r;
        }

        // 去除后缀字符集（"省/市/区/县"等）
        Set<Character> keywordChars = stripSuffixToCharSet(keyword);

        // 找出所有 100% 覆盖的候选，按段数分组
        int bestSegments = Integer.MAX_VALUE;
        List<CitySearchResult> bestMatches = new ArrayList<>();

        for (CitySearchResult c : candidates) {
            Set<Character> candidateChars = stripSuffixToCharSet(c.getDisplayName());
            int intersectionSize = 0;
            for (Character ch : keywordChars) {
                if (candidateChars.contains(ch)) intersectionSize++;
            }
            double coverage = keywordChars.isEmpty() ? 0 : (double) intersectionSize / keywordChars.size();

            if (coverage < 1.0) continue;  // 只看 100% 覆盖的

            int segments = c.getDisplayName().split("-").length;
            if (segments < bestSegments) {
                bestSegments = segments;
                bestMatches.clear();
                bestMatches.add(c);
            } else if (segments == bestSegments) {
                bestMatches.add(c);
            }
        }

        if (bestMatches.isEmpty()) {
            // 没有 100% 覆盖的候选
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "未完全匹配「" + keyword + "」，请确认城市名或更明确地指出");
            r.put("candidates", candidates.stream().map(CitySearchResult::getDisplayName).toList());
            return r;
        }

        if (bestMatches.size() > 1) {
            // 段数最短的有多个，歧义
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "找到多个匹配的城市，请明确指出");
            r.put("candidates", bestMatches.stream().map(CitySearchResult::getDisplayName).toList());
            return r;
        }

        // 唯一最佳匹配
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("displayName", bestMatches.get(0).getDisplayName());
        return r;
    }

    // 把字符串转为字符集，去除"-"分隔符和"省/市/区/县/自治州/盟/旗"等行政区划后缀字符
    private Set<Character> stripSuffixToCharSet(String s) {
        Set<Character> chars = new HashSet<>();
        for (char c : s.toCharArray()) {
            // 跳过分隔符和常见行政区划后缀字符
            if (c == '-' || c == '省' || c == '市' || c == '区' || c == '县'
                    || c == '自' || c == '治' || c == '州' || c == '盟' || c == '旗') {
                continue;
            }
            chars.add(c);
        }
        return chars;
    }
}
