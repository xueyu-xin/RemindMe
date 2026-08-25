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
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// 位置服务：封装和风天气城市搜索，
// 对 Agent 暴露 get_location（当前定位）和 search_city（城市搜索）两个工具。
// 城市匹配策略：和风API返回多个候选→全给AI选；只返回1个→代码直接返回标准名+坐标。
// 三大功能（查天气、设偏好地址、提醒查天气）共用 searchCity 方法。
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final WeatherConfig weatherConfig;

    // 获取用户当前地理位置：有 GPS 就用和风城市搜索反查标准格式地址+经纬度，
    // 没有 GPS 则直接返回数据库里的默认城市（坐标交给后续 search_city 处理）。
    @AgentTool(name = "get_location", description = "获取用户当前地理位置。有GPS时返回实时定位地址+经纬度；没GPS时返回我的页面设置的默认城市（不含坐标，如需查天气请再调search_city拿坐标）。只在用户问【我在哪】、【当前位置是哪】、以及任何提到「当前位置」的查询时调用。用户没提到当前位置就不要调这个工具。")
    public Map<String, Object> getLocation(Long userId, Double latitude, Double longitude) {
        Map<String, Object> result = new HashMap<>();

        // 有GPS坐标：用和风城市搜索反查标准格式地址 + 直接返回 GPS 经纬度
        if (latitude != null && longitude != null) {
            try {
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
                result.put("message", "定位失败，请稍后再试。");
            }
            return result;
        }

        // 无GPS坐标：直接返回数据库默认城市，坐标交给后续 search_city 处理
        try {
            User user = userService.getUserById(userId).orElse(null);
            if (user != null && user.getCity() != null && !user.getCity().isBlank()) {
                result.put("success", true);
                result.put("address", user.getCity());
                result.put("source", "database (defaultCity)");
                log.info("无GPS定位，返回默认城市: userId={}, city={}", userId, user.getCity());
            } else {
                result.put("success", false);
                result.put("message", "未设置默认城市，请在设置中配置");
            }
        } catch (Exception e) {
            log.error("查询用户默认城市失败: userId={}", userId, e);
            result.put("success", false);
            result.put("message", "城市查询失败，请稍后再试。");
        }
        return result;
    }

    // 统一城市搜索工具（@AgentTool "search_city"）：
    // AI可直接调用；WeatherService、UserService 内部也调它。
    // - 0个结果 → {success:false, message:"未找到城市"}
    // - 1个结果 → {success:true, displayName, coord, latitude, longitude}
    // - 多个结果 → {success:true, ambiguous:true, message, candidates:[{displayName, latitude, longitude}]}
    @AgentTool(name = "search_city", description = "城市搜索：把用户说的地点名解析成标准城市名+经纬度。用户说任何地点（城市名、城市+区县、省+城市等）要查天气/设默认城市时，先调这个工具拿标准名称和坐标。返回单个结果直接用；返回多个候选项时必须询问用户到底是哪个，不要自己选。")
    public Map<String, Object> searchCity(String keyword) {
        Map<String, Object> result = new HashMap<>();
        if (keyword == null || keyword.isBlank()) {
            result.put("success", false);
            result.put("message", "城市名为空");
            return result;
        }

        try {
            List<CitySearchResult> hits = searchCities(keyword);
            if (hits == null || hits.isEmpty()) {
                result.put("success", false);
                result.put("message", "未找到城市「" + keyword + "」，请确认城市名是否正确");
                return result;
            }

            // 1个结果：直接返回标准名+坐标
            if (hits.size() == 1) {
                CitySearchResult c = hits.get(0);
                double lon = Double.parseDouble(c.getLon().trim());
                double lat = Double.parseDouble(c.getLat().trim());
                result.put("success", true);
                result.put("displayName", c.getDisplayName());
                result.put("coord", c.getLon() + "," + c.getLat());
                result.put("latitude", lat);
                result.put("longitude", lon);
                log.info("search_city 唯一匹配: {} -> displayName={}, coord=({},{})", keyword, c.getDisplayName(), lat, lon);
                return result;
            }

            // 多个结果：全给AI，AI根据用户原话自己判断选哪个，选不了再问用户
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (CitySearchResult c : hits) {
                Map<String, Object> candidate = new HashMap<>();
                candidate.put("displayName", c.getDisplayName());
                candidate.put("latitude", Double.parseDouble(c.getLat().trim()));
                candidate.put("longitude", Double.parseDouble(c.getLon().trim()));
                candidates.add(candidate);
            }
            result.put("success", true);
            result.put("candidates", candidates);
            result.put("message", "「" + keyword + "」匹配到" + hits.size() + "个城市");
            log.info("search_city 多候选: {} -> {}个", keyword, hits.size());
            return result;
        } catch (Exception e) {
            log.warn("search_city 转换失败: keyword={}, error={}", keyword, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "城市搜索失败，请稍后再试。");
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
                    + "&number=5"
                    + "&lang=zh-hans";

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

                conn.setRequestMethod("GET");
                conn.setConnectTimeout(weatherConfig.getConnectTimeout());
                conn.setReadTimeout(weatherConfig.getReadTimeout());
                conn.setRequestProperty("X-QW-Api-Key", weatherConfig.getKey());
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Encoding", "gzip");

                int responseCode = conn.getResponseCode();
                log.debug("HTTP响应码: {}", responseCode);

                if (responseCode == 200 || responseCode == 304) {
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
}
