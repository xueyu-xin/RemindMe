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
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// 天气服务：调用和风天气 API，对 Agent 提供"实时天气/天气预报/空气质量/天气预警"四个工具，
// 内部用原生 HttpURLConnection + 手动 gzip 解压，城市名到经纬度的转换交给 LocationService。
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherConfig weatherConfig;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final LocationService locationService;

    // ==================== 核心接口 ====================

    // 查询实时天气，location 接受城市名或"经度,纬度"字符串
    public WeatherNowResponse getWeatherNow(String location) {
        log.info("查询实时天气: location={}", location);

        // 通过 LocationService 将城市名转换为经纬度
        String coord = locationService.resolveLocationToCoord(location);
        log.info("经纬度转换: {} → {}", location, coord);

        String url = buildUrl("/v7/weather/now")
                + "?location=" + encode(coord)
                + "&lang=zh";

        String json = httpGet(url);
        WeatherNowResponse response = parseJson(json, WeatherNowResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("实时天气查询成功: location={}, coord={}, temp={}", location, coord, response.getNow().getTemp());
            return response;
        }

        throw new RuntimeException("实时天气查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    // 查询天气预报，days 支持 3/7/10/15/30，默认 7 天
    public WeatherForecastResponse getWeatherForecast(String location, Integer days) {
        log.info("查询天气预报: location={}, days={}", location, days);

        // 通过 LocationService 将城市名转换为经纬度
        String coord = locationService.resolveLocationToCoord(location);
        log.info("经纬度转换: {} → {}", location, coord);

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
                + "?location=" + encode(coord)
                + "&lang=zh";

        String json = httpGet(url);
        WeatherForecastResponse response = parseJson(json, WeatherForecastResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("天气预报查询成功: location={}, coord={}, days={}, count={}", location, coord, days, response.getDaily().size());
            return response;
        }

        throw new RuntimeException("天气预报查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    // 查询空气质量，入参必须是经纬度
    public AirQualityResponse getAirQuality(Double latitude, Double longitude) {
        log.info("查询空气质量: lat={}, lon={}", latitude, longitude);

        String url = buildUrl("/airquality/v1/current/" + latitude + "/" + longitude)
                + "?lang=zh";

        String json = httpGet(url);
        AirQualityResponse response = parseJson(json, AirQualityResponse.class);

        if (response != null && response.getIndexes() != null && !response.getIndexes().isEmpty()) {
            log.info("空气质量查询成功: lat={}, lon={}, aqi={}", latitude, longitude, response.getIndexes().get(0).getAqi());
            return response;
        }

        throw new RuntimeException("空气质量查询失败");
    }

    // 查询天气预警，入参必须是经纬度
    public WeatherAlertResponse getWeatherAlert(Double latitude, Double longitude) {
        log.info("查询天气预警: lat={}, lon={}", latitude, longitude);

        String url = buildUrl("/weatheralert/v1/current/" + latitude + "/" + longitude)
                + "?lang=zh";

        String json = httpGet(url);
        WeatherAlertResponse response = parseJson(json, WeatherAlertResponse.class);

        if (response != null) {
            int alertCount = response.getAlerts() != null ? response.getAlerts().size() : 0;
            log.info("天气预警查询成功: lat={}, lon={}, alertCount={}", latitude, longitude, alertCount);
            return response;
        }

        throw new RuntimeException("天气预警查询失败");
    }

    // ==================== 位置解析 ====================

    // 解析位置参数，优先级：经纬度 > location 城市名 > 用户偏好城市。
    // 返回的是"标准化后的城市名或经纬度"，真正的经纬度转换交给 getWeatherNow 等方法内部完成。
    private String resolveLocation(Long userId, Double latitude, Double longitude, String location) {
        // 优先级1：经纬度（和风天气API要求 location 格式为"经度,纬度"）
        if (latitude != null && longitude != null) {
            String lonLat = longitude + "," + latitude;
            log.info("使用经纬度查询: lat={}, lon={}, locationStr={}", latitude, longitude, lonLat);
            return lonLat;
        }

        // 优先级2：location参数（城市名，可能是"省-市-区"格式，直接透传由 LocationService 处理）
        if (location != null && !location.isBlank()) {
            log.info("使用城市名查询: location={}", location);
            return location;
        }

        // 优先级3：用户偏好城市
        if (userId != null) {
            try {
                Map<String, Object> preferences = userService.getUserPreferences(userId);
                String userCity = (String) preferences.get("city");
                if (userCity != null && !userCity.isBlank()) {
                    log.info("使用用户默认城市查询: userId={}, city={}", userId, userCity);
                    return userCity;
                }
            } catch (Exception e) {
                log.warn("获取用户偏好城市失败: userId={}, error={}", userId, e.getMessage());
            }
        }

        // 默认：广州
        log.info("未提供位置信息，使用默认城市: 广州");
        return "广州";
    }

    // ==================== Agent工具 ====================

    @AgentTool(name = "query_weather_now", description = "查询实时天气，用户询问当前天气时调用。优先使用经纬度查询，其次使用城市名，最后使用用户默认城市")
    public Map<String, Object> queryWeatherNowForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询实时天气: userId={}, location={}, latitude={}, longitude={}", userId, location, latitude, longitude);

        try {
            String resolvedLocation = resolveLocation(userId, latitude, longitude, location);
            WeatherNowResponse response = getWeatherNow(resolvedLocation);

            Map<String, Object> result = new HashMap<>();
            if (response != null && "200".equals(response.getCode())) {
                result.put("success", true);
                result.put("message", "实时天气查询成功");

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
                log.info("Agent查询实时天气成功: userId={}, temp={}", userId, now != null ? now.getTemp() : "null");
            } else {
                result.put("success", false);
                result.put("message", "实时天气查询失败：API返回码 " + (response != null ? response.getCode() : "null"));
            }

            return result;
        } catch (Exception e) {
            log.error("Agent查询实时天气失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "实时天气查询失败：" + e.getMessage());
            return errorResult;
        }
    }

    @AgentTool(name = "query_weather_forecast", description = "查询天气预报，用户询问未来天气时调用。优先使用经纬度查询，其次使用城市名，最后使用用户默认城市")
    public Map<String, Object> queryWeatherForecastForAgent(Long userId, String location, Integer days, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预报: userId={}, location={}, days={}, latitude={}, longitude={}", userId, location, days, latitude, longitude);

        try {
            String resolvedLocation = resolveLocation(userId, latitude, longitude, location);
            WeatherForecastResponse response = getWeatherForecast(resolvedLocation, days);

            Map<String, Object> result = new HashMap<>();
            if (response != null && "200".equals(response.getCode())) {
                result.put("success", true);
                result.put("message", "天气预报查询成功");

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
                log.info("Agent查询天气预报成功: userId={}, days={}, count={}", userId, days, dailyList.size());
            } else {
                result.put("success", false);
                result.put("message", "天气预报查询失败：API返回码 " + (response != null ? response.getCode() : "null"));
            }

            return result;
        } catch (Exception e) {
            log.error("Agent查询天气预报失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "天气预报查询失败：" + e.getMessage());
            return errorResult;
        }
    }

    @AgentTool(name = "query_air_quality", description = "查询空气质量，用户询问空气质量时调用。优先使用经纬度（必须真实查询得到），其次使用location城市名，最后兜底使用用户默认城市。禁止自己猜坐标。")
    public Map<String, Object> queryAirQualityForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询空气质量: userId={}, location={}, lat={}, lon={}", userId, location, latitude, longitude);

        try {
            double[] coords = resolveLatLon(userId, latitude, longitude, location);
            AirQualityResponse response = getAirQuality(coords[0], coords[1]);

            Map<String, Object> result = new HashMap<>();
            if (response != null && response.getIndexes() != null && !response.getIndexes().isEmpty()) {
                result.put("success", true);
                result.put("message", "空气质量查询成功");

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
                log.info("Agent查询空气质量成功: userId={}, aqi={}", userId, index.getAqi());
            } else {
                result.put("success", false);
                result.put("message", "空气质量查询失败：无有效数据");
            }

            return result;
        } catch (Exception e) {
            log.error("Agent查询空气质量失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "空气质量查询失败：" + e.getMessage());
            return errorResult;
        }
    }

    @AgentTool(name = "query_weather_alert", description = "查询天气预警，用户询问是否有天气预警时调用。优先使用经纬度，其次使用location城市名，最后兜底用户默认城市。禁止自己猜坐标。")
    public Map<String, Object> queryWeatherAlertForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预警: userId={}, location={}, lat={}, lon={}", userId, location, latitude, longitude);

        try {
            double[] coords = resolveLatLon(userId, latitude, longitude, location);
            WeatherAlertResponse response = getWeatherAlert(coords[0], coords[1]);

            Map<String, Object> result = new HashMap<>();
            if (response != null) {
                result.put("success", true);

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
                result.put("message", hasAlerts ? "查询到 " + alertList.size() + " 条天气预警" : "当前无天气预警");
                result.put("hasAlerts", hasAlerts);
                result.put("alerts", alertList);
                log.info("Agent查询天气预警成功: userId={}, alerts={}", userId, alertList.size());
            } else {
                result.put("success", false);
                result.put("message", "天气预警查询失败：API返回null");
            }

            return result;
        } catch (Exception e) {
            log.error("Agent查询天气预警失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "天气预警查询失败：" + e.getMessage());
            return errorResult;
        }
    }

    // 从（latitude, longitude, location, 用户默认城市）按优先级算出最终经纬度，
    // 保证空气质量、预警这类必须传经纬度的工具不会因为坐标缺失而报错。
    // 返回 double[2] = [latitude, longitude]。
    private double[] resolveLatLon(Long userId, Double latitude, Double longitude, String location) {
        // 优先级 1：LLM 传了 lat/lon → 直接用
        if (latitude != null && longitude != null) {
            log.info("resolveLatLon: 使用传入的经纬度 lat={}, lon={}", latitude, longitude);
            return new double[] { latitude, longitude };
        }

        // 优先级 2：传了 location 具体城市名 → 转成经纬度
        if (location != null && !location.isBlank()) {
            try {
                String coord = locationService.resolveLocationToCoord(location);
                if (coord != null && coord.contains(",")) {
                    String[] parts = coord.split(",");
                    double lon = Double.parseDouble(parts[0].trim());
                    double lat = Double.parseDouble(parts[1].trim());
                    log.info("resolveLatLon: location={} -> lat={}, lon={}", location, lat, lon);
                    return new double[] { lat, lon };
                }
            } catch (Exception e) {
                log.warn("resolveLatLon: location 转坐标失败 location={}, error={}", location, e.getMessage());
            }
        }

        // 优先级 3：查用户偏好里的默认城市 → 转成经纬度
        if (userId != null) {
            try {
                Map<String, Object> pref = userService.getUserPreferences(userId);
                String userCity = (String) pref.get("city");
                if (userCity != null && !userCity.isBlank()) {
                    String coord = locationService.resolveLocationToCoord(userCity);
                    if (coord != null && coord.contains(",")) {
                        String[] parts = coord.split(",");
                        double lon = Double.parseDouble(parts[0].trim());
                        double lat = Double.parseDouble(parts[1].trim());
                        log.info("resolveLatLon: 使用用户默认城市 userId={}, city={} -> lat={}, lon={}", userId, userCity, lat, lon);
                        return new double[] { lat, lon };
                    }
                    // 兜底：默认城市转不出坐标，就直接用用户城市名返回的默认值（广州）
                    log.warn("resolveLatLon: 用户默认城市转坐标失败，city={}", userCity);
                }
            } catch (Exception e) {
                log.warn("resolveLatLon: 读取用户默认城市失败 userId={}, error={}", userId, e.getMessage());
            }
        }

        // 最后兜底：和风天气广州（和现有默认值"广东省-广州市-天河区"保持一致）
        log.warn("resolveLatLon: 所有路径都拿不到坐标，使用兜底默认坐标（广州）");
        return new double[] { 23.1291, 113.2644 };
    }

    // ==================== HTTP 工具方法 ====================

    // 发送 HTTP GET 请求，自动处理 gzip 解压
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
            conn.setRequestProperty("Accept-Encoding", "gzip");  // 请求 gzip 压缩，节省带宽

            int responseCode = conn.getResponseCode();
            log.debug("HTTP响应码: {}", responseCode);

            if (responseCode == 200 || responseCode == 304) {
                // 读取响应，自动处理 gzip
                String contentEncoding = conn.getHeaderField("Content-Encoding");
                BufferedReader reader;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    // gzip 压缩响应，需要解压
                    reader = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(conn.getInputStream()), "UTF-8"));
                } else {
                    // 普通响应
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
                // 错误响应
                String errorMsg = readErrorStream(conn);
                log.error("HTTP错误: code={}, message={}", responseCode, errorMsg);
                throw new RuntimeException("HTTP请求失败: " + responseCode + " - " + errorMsg);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("HTTP请求异常", e);
            throw new RuntimeException("HTTP请求失败: " + e.getMessage(), e);
        }
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
