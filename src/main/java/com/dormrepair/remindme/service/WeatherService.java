package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.config.WeatherConfig;
import com.dormrepair.remindme.dto.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// 天气服务：调用和风天气 API，对 Agent 提供"实时天气/天气预报/空气质量/天气预警"四个工具。
// 位置处理统一流程：调 LocationService.searchCity 拿标准 displayName + 坐标，再调天气 API。
// 解析失败直接返回错误让 AI 决定。
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherConfig weatherConfig;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final LocationService locationService;


    // 查询实时天气，location 为"经度,纬度"或 LocationID，直接传给和风 API
    public WeatherNowResponse getWeatherNow(String location) {
        log.info("查询实时天气: location={}", location);
        String url = buildUrl("/v7/weather/now")
                + "?location=" + encode(location)
                + "&lang=zh-hans";

        String json = httpGet(url);
        WeatherNowResponse response = parseJson(json, WeatherNowResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("实时天气查询成功: location={}, temp={}", location, response.getNow().getTemp());
            return response;
        }
        throw new RuntimeException("实时天气查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    // 查询逐小时预报，location 为"经度,纬度"或 LocationID，返回未来 24 小时逐小时预报
    public WeatherHourlyResponse getWeatherHourly(String location) {
        log.info("查询逐小时预报: location={}", location);
        String url = buildUrl("/v7/weather/24h")
                + "?location=" + encode(location)
                + "&lang=zh-hans";

        String json = httpGet(url);
        WeatherHourlyResponse response = parseJson(json, WeatherHourlyResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("逐小时预报查询成功: location={}, count={}", location, response.getHourly().size());
            return response;
        }
        throw new RuntimeException("逐小时预报查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    // 查询天气预报，days 支持 3/7/10/15/30，默认 7 天
    public WeatherForecastResponse getWeatherForecast(String location, Integer days) {
        log.info("查询天气预报: location={}, days={}", location, days);

        int forecastDays = days != null ? days : 7;
        String daysPath = switch (forecastDays) {
            case 3 -> "3d";
            case 7 -> "7d";
            case 10 -> "10d";
            case 15 -> "15d";
            case 30 -> "30d";
            default -> "7d";
        };

        String url = buildUrl("/v7/weather/" + daysPath)
                + "?location=" + encode(location)
                + "&lang=zh-hans";

        String json = httpGet(url);
        WeatherForecastResponse response = parseJson(json, WeatherForecastResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("天气预报查询成功: location={}, days={}, count={}", location, days, response.getDaily().size());
            return response;
        }
        throw new RuntimeException("天气预报查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    // 查询空气质量：直接用真实经纬度调用
    public AirQualityResponse getAirQuality(Double latitude, Double longitude) {
        log.info("查询空气质量: lat={}, lon={}", latitude, longitude);

        String url = buildUrl("/airquality/v1/current/" + latitude + "/" + longitude)
                + "?lang=zh-hans";

        String json = httpGet(url);
        AirQualityResponse response = parseJson(json, AirQualityResponse.class);

        if (response != null && response.getIndexes() != null && !response.getIndexes().isEmpty()) {
            log.info("空气质量查询成功: lat={}, lon={}, aqi={}", latitude, longitude, response.getIndexes().get(0).getAqi());
            return response;
        }
        throw new RuntimeException("空气质量查询失败");
    }

    // 查询天气预警：直接用真实经纬度调用
    public WeatherAlertResponse getWeatherAlert(Double latitude, Double longitude) {
        log.info("查询天气预警: lat={}, lon={}", latitude, longitude);

        String url = buildUrl("/weatheralert/v1/current/" + latitude + "/" + longitude)
                + "?lang=zh-hans";

        String json = httpGet(url);
        WeatherAlertResponse response = parseJson(json, WeatherAlertResponse.class);

        if (response != null) {
            int alertCount = response.getAlerts() != null ? response.getAlerts().size() : 0;
            log.info("天气预警查询成功: lat={}, lon={}, alertCount={}", latitude, longitude, alertCount);
            return response;
        }
        throw new RuntimeException("天气预警查询失败");
    }

    // ==================== 位置解析（统一调 LocationService.searchCity） ====================

    // 解析位置，优先级：经纬度 > location 城市名 > 用户偏好城市 > 返回失败。
    // 统一调 LocationService.searchCity 拿标准 displayName + 坐标。
    // 返回 Map 结构：
    //   成功：{ success:true, coord:"经度,纬度", displayName:"北京市-北京-朝阳区", latitude, longitude }
    //   歧义：{ success:true, ambiguous:true, message, candidates:[...] }
    //   失败：{ success:false, message }
    private Map<String, Object> resolveLocation(Long userId, Double latitude, Double longitude, String location) {
        // 优先级1：经纬度直接用坐标查天气，displayName取AI传来的location（search_city的标准名）
        if (latitude != null && longitude != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("coord", longitude + "," + latitude);
            // AI从search_city选定候选后，会把标准名作为location传入；没传则用占位
            result.put("displayName", (location != null && !location.isBlank()) ? location : "当前位置");
            result.put("latitude", latitude);
            result.put("longitude", longitude);
            log.info("resolveLocation: 优先级1 使用经纬度 lat={}, lon={}, displayName={}", latitude, longitude, result.get("displayName"));
            return result;
        }

        // 优先级2：location 参数（城市名）
        if (location != null && !location.isBlank()) {
            log.info("resolveLocation: 优先级2 使用location参数 location={}", location);
            return locationService.searchCity(location);
        }

        // 优先级3：用户默认城市
        if (userId != null) {
            try {
                Map<String, Object> preferences = userService.getUserPreferences(userId);
                String userCity = (String) preferences.get("city");
                if (userCity != null && !userCity.isBlank()) {
                    log.info("resolveLocation: 优先级3 使用用户默认城市 userId={}, city={}", userId, userCity);
                    return locationService.searchCity(userCity);
                }
            } catch (Exception e) {
                log.warn("resolveLocation: 读取用户默认城市失败 userId={}, error={}", userId, e.getMessage());
            }
        }

        // 优先级4：返回失败
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "未提供有效位置，且用户未设置默认城市");
        log.info("resolveLocation: 优先级4 未提供有效位置，返回失败");
        return result;
    }

    // ==================== Agent 工具（四个方法：resolveLocation 拿 coord → 调 API → 结果带 resolvedCity 标准名） ====================

    @AgentTool(name = "query_weather_now", description = "查询实时天气。必须先调search_city拿标准名+坐标，再调本工具并传location=标准名+latitude/longitude=坐标。返回candidates时取候选的displayName+坐标再次调用，禁止再调search_city。")
    public Map<String, Object> queryWeatherNowForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询实时天气: userId={}, location={}, latitude={}, longitude={}", userId, location, latitude, longitude);

        try {
            Map<String, Object> resolvedCity = resolveLocation(userId, latitude, longitude, location);
            // 多候选或失败：直接返回结果给AI判断
            if (resolvedCity.containsKey("candidates") || !Boolean.TRUE.equals(resolvedCity.get("success"))) {
                return resolvedCity;
            }
            String coord = (String) resolvedCity.get("coord");
            String displayName = (String) resolvedCity.get("displayName");
            WeatherNowResponse response = getWeatherNow(coord);

            Map<String, Object> result = new HashMap<>();
            if (response != null && "200".equals(response.getCode())) {
                result.put("success", true);
                result.put("message", displayName + " 实时天气查询成功");
                result.put("resolvedCity", displayName);

                Map<String, Object> weatherData = new HashMap<>();
                WeatherNowResponse.NowData now = response.getNow();
                if (now != null) {
                    weatherData.put("temperature", now.getTemp());
                    weatherData.put("feelsLike", now.getFeelsLike());
                    weatherData.put("weather", now.getText());
                    weatherData.put("windDirection", now.getWindDir());
                    weatherData.put("windScale", now.getWindScale());
                    weatherData.put("humidity", now.getHumidity());
                    weatherData.put("precipitation", now.getPrecip());
                    weatherData.put("visibility", now.getVis());
                }
                result.put("weather", weatherData);
                log.info("Agent查询实时天气成功: userId={}, resolvedCity={}, temp={}",
                        userId, displayName, now != null ? now.getTemp() : "null");
            } else {
                result.put("success", false);
                result.put("resolvedCity", displayName);
                result.put("message", displayName + " 实时天气查询暂时不可用，请稍后再试");
                log.warn("实时天气查询API失败: userId={}, resolvedCity={}, code={}", userId, displayName, response != null ? response.getCode() : "null");
            }
            return result;
        } catch (Exception e) {
            log.error("Agent查询实时天气失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "天气查询失败，可能因为城市名不正确或网络问题，请检查城市名或稍后再试。");
            return errorResult;
        }
    }

    @AgentTool(name = "query_weather_forecast", description = "查询天气预报。必须先调search_city拿标准名+坐标，再调本工具并传location=标准名+latitude/longitude=坐标。返回candidates时取候选的displayName+坐标再次调用，禁止再调search_city。")
    public Map<String, Object> queryWeatherForecastForAgent(Long userId, String location, Integer days, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预报: userId={}, location={}, days={}, latitude={}, longitude={}", userId, location, days, latitude, longitude);

        try {
            Map<String, Object> resolvedCity = resolveLocation(userId, latitude, longitude, location);
            if (resolvedCity.containsKey("candidates") || !Boolean.TRUE.equals(resolvedCity.get("success"))) {
                return resolvedCity;
            }
            String coord = (String) resolvedCity.get("coord");
            String displayName = (String) resolvedCity.get("displayName");
            WeatherForecastResponse response = getWeatherForecast(coord, days);

            Map<String, Object> result = new HashMap<>();
            if (response != null && "200".equals(response.getCode())) {
                result.put("success", true);
                result.put("message", displayName + " 天气预报查询成功");
                result.put("resolvedCity", displayName);

                Map<String, Object> forecastData = new HashMap<>();
                forecastData.put("days", days != null ? days : 7);

                java.util.List<Map<String, Object>> dailyList = new java.util.ArrayList<>();
                if (response.getDaily() != null) {
                    for (WeatherForecastResponse.DailyForecast daily : response.getDaily()) {
                        Map<String, Object> dayMap = new HashMap<>();
                        dayMap.put("date", daily.getFxDate());
                        dayMap.put("tempMax", daily.getTempMax());
                        dayMap.put("tempMin", daily.getTempMin());
                        dayMap.put("weatherDay", daily.getTextDay());
                        dayMap.put("weatherNight", daily.getTextNight());
                        dayMap.put("windDirDay", daily.getWindDirDay());
                        dayMap.put("windScaleDay", daily.getWindScaleDay());
                        dayMap.put("humidity", daily.getHumidity());
                        dayMap.put("precipitation", daily.getPrecip());
                        dayMap.put("uvIndex", daily.getUvIndex());
                        dailyList.add(dayMap);
                    }
                }
                forecastData.put("daily", dailyList);
                result.put("forecast", forecastData);
                log.info("Agent查询天气预报成功: userId={}, resolvedCity={}, days={}, count={}",
                        userId, displayName, days, dailyList.size());
            } else {
                result.put("success", false);
                result.put("resolvedCity", displayName);
                result.put("message", displayName + " 天气预报查询暂时不可用，请稍后再试");
                log.warn("天气预报查询API失败: userId={}, resolvedCity={}, code={}", userId, displayName, response != null ? response.getCode() : "null");
            }
            return result;
        } catch (Exception e) {
            log.error("Agent查询天气预报失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "天气预报查询失败，可能因为城市名不正确或网络问题，请检查城市名或稍后再试。");
            return errorResult;
        }
    }

    @AgentTool(name = "query_air_quality", description = "查询空气质量。必须先调search_city拿标准名+坐标，再调本工具并传location=标准名+latitude/longitude=坐标。返回candidates时取候选的displayName+坐标再次调用，禁止再调search_city。")
    public Map<String, Object> queryAirQualityForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询空气质量: userId={}, location={}, lat={}, lon={}", userId, location, latitude, longitude);

        try {
            Map<String, Object> resolvedCity = resolveLocation(userId, latitude, longitude, location);
            if (resolvedCity.containsKey("candidates") || !Boolean.TRUE.equals(resolvedCity.get("success"))) {
                return resolvedCity;
            }
            Double lat = (Double) resolvedCity.get("latitude");
            Double lon = (Double) resolvedCity.get("longitude");
            String displayName = (String) resolvedCity.get("displayName");
            AirQualityResponse response = getAirQuality(lat, lon);

            Map<String, Object> result = new HashMap<>();
            if (response != null && response.getIndexes() != null && !response.getIndexes().isEmpty()) {
                result.put("success", true);
                result.put("message", displayName + " 空气质量查询成功");
                result.put("resolvedCity", displayName);

                Map<String, Object> aqiData = new HashMap<>();
                AirQualityResponse.AirIndex index = response.getIndexes().get(0);
                aqiData.put("aqi", index.getAqi());
                aqiData.put("aqiDisplay", index.getAqiDisplay());
                aqiData.put("level", index.getLevel());
                aqiData.put("category", index.getCategory());

                if (index.getPrimaryPollutant() != null) {
                    aqiData.put("primaryPollutant", index.getPrimaryPollutant().getName());
                }
                if (index.getHealth() != null) {
                    aqiData.put("healthEffect", index.getHealth().getEffect());
                    if (index.getHealth().getAdvice() != null) {
                        aqiData.put("adviceGeneral", index.getHealth().getAdvice().getGeneralPopulation());
                        aqiData.put("adviceSensitive", index.getHealth().getAdvice().getSensitivePopulation());
                    }
                }
                result.put("airQuality", aqiData);
                log.info("Agent查询空气质量成功: userId={}, resolvedCity={}, aqi={}", userId, displayName, index.getAqi());
            } else {
                result.put("success", false);
                result.put("resolvedCity", displayName);
                result.put("message", displayName + " 空气质量查询暂时不可用，请稍后再试");
                log.warn("空气质量查询API失败: userId={}, resolvedCity={}, indexes为空或null", userId, displayName);
            }
            return result;
        } catch (Exception e) {
            log.error("Agent查询空气质量失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "空气质量查询失败，可能因为城市名不正确或网络问题，请检查城市名或稍后再试。");
            return errorResult;
        }
    }

    @AgentTool(name = "query_weather_alert", description = "查询天气预警。必须先调search_city拿标准名+坐标，再调本工具并传location=标准名+latitude/longitude=坐标。返回candidates时取候选的displayName+坐标再次调用，禁止再调search_city。")
    public Map<String, Object> queryWeatherAlertForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预警: userId={}, location={}, lat={}, lon={}", userId, location, latitude, longitude);

        try {
            Map<String, Object> resolvedCity = resolveLocation(userId, latitude, longitude, location);
            if (resolvedCity.containsKey("candidates") || !Boolean.TRUE.equals(resolvedCity.get("success"))) {
                return resolvedCity;
            }
            Double lat = (Double) resolvedCity.get("latitude");
            Double lon = (Double) resolvedCity.get("longitude");
            String displayName = (String) resolvedCity.get("displayName");
            WeatherAlertResponse response = getWeatherAlert(lat, lon);

            Map<String, Object> result = new HashMap<>();
            if (response != null) {
                result.put("success", true);
                result.put("resolvedCity", displayName);

                java.util.List<Map<String, Object>> alertList = new java.util.ArrayList<>();
                if (response.getAlerts() != null) {
                    for (WeatherAlertResponse.Alert alert : response.getAlerts()) {
                        Map<String, Object> alertMap = new HashMap<>();
                        alertMap.put("headline", alert.getHeadline());
                        alertMap.put("severity", alert.getSeverity());
                        alertMap.put("urgency", alert.getUrgency());
                        alertMap.put("description", alert.getDescription());
                        alertMap.put("instruction", alert.getInstruction());
                        alertMap.put("effectiveTime", alert.getEffectiveTime());
                        alertMap.put("expireTime", alert.getExpireTime());
                        if (alert.getEventType() != null) {
                            alertMap.put("eventType", alert.getEventType().getName());
                        }
                        if (alert.getColor() != null) {
                            alertMap.put("color", alert.getColor().getCode());
                        }
                        alertList.add(alertMap);
                    }
                }

                boolean hasAlerts = alertList.size() > 0;
                result.put("message", hasAlerts
                        ? displayName + " 查询到 " + alertList.size() + " 条天气预警"
                        : displayName + " 当前无天气预警");
                result.put("hasAlerts", hasAlerts);
                result.put("alerts", alertList);
                log.info("Agent查询天气预警成功: userId={}, resolvedCity={}, alerts={}", userId, displayName, alertList.size());
            } else {
                result.put("success", false);
                result.put("resolvedCity", displayName);
                result.put("message", displayName + " 天气预警查询暂时不可用，请稍后再试");
                log.warn("天气预警查询API失败: userId={}, resolvedCity={}, response=null", userId, displayName);
            }
            return result;
        } catch (Exception e) {
            log.error("Agent查询天气预警失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "天气预警查询失败，可能因为城市名不正确或网络问题，请检查城市名或稍后再试。");
            return errorResult;
        }
    }

    // ==================== HTTP 工具方法 ====================

    // 发送 HTTP GET 请求，自动处理 gzip 解压（带重试：仅 SocketTimeoutException，重试前重建连接）
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
                                new GZIPInputStream(conn.getInputStream()), "UTF-8"));
                    } else {
                        reader = new BufferedReader(new InputStreamReader(
                                conn.getInputStream(), "UTF-8"));
                    }

                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String json = sb.toString();
                    log.debug("响应内容: {}", json);

                    return json;
                } else {
                    String errorMsg = readErrorStream(conn);
                    log.error("HTTP错误: code={}, message={}", responseCode, errorMsg);
                    throw new RuntimeException("HTTP请求失败: " + responseCode + " - " + errorMsg);
                }
            } catch (SocketTimeoutException e) {
                lastException = e;
                log.warn("WeatherService httpGet 超时，第 {} 次重试: {}", attempt, e.getMessage());

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
                log.error("HTTP请求异常", e);
                throw new RuntimeException("HTTP请求失败: " + e.getMessage(), e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        throw new RuntimeException("WeatherService httpGet 调用失败，已重试 " + maxRetries + " 次", lastException);
    }

    // 读取错误响应流，同样处理 gzip 解压
    private String readErrorStream(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() != null) {
                String contentEncoding = conn.getHeaderField("Content-Encoding");
                BufferedReader reader;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    reader = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(conn.getErrorStream()), "UTF-8"));
                } else {
                    reader = new BufferedReader(new InputStreamReader(
                            conn.getErrorStream(), "UTF-8"));
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

    // 反序列化 JSON 响应
    private <T> T parseJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("JSON解析失败: json={}, class={}", json, clazz.getSimpleName(), e);
            throw new RuntimeException("JSON解析失败: " + e.getMessage(), e);
        }
    }

    // 拼接和风天气 API 完整 URL
    private String buildUrl(String path) {
        return weatherConfig.getHost() + path;
    }

    // URL 编码，处理逗号等特殊字符
    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
