package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.config.WeatherConfig;
import com.dormrepair.remindme.dto.weather.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.zip.GZIPInputStream;

/**
 * 天气服务
 * <p>
 * 调用新版和风天气API，支持实时天气、天气预报、空气质量、天气预警
 * 使用原生 HttpURLConnection + GZIP 解压，避免 RestTemplate 的 gzip 问题
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherConfig weatherConfig;
    private final ObjectMapper objectMapper;

    // ==================== 核心接口 ====================

    /**
     * 获取实时天气
     */
    public WeatherNowResponse getWeatherNow(String location) {
        log.info("查询实时天气: location={}", location);

        String url = buildUrl("/v7/weather/now")
                + "?location=" + encode(location)
                + "&lang=zh";

        String json = httpGet(url);
        WeatherNowResponse response = parseJson(json, WeatherNowResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("实时天气查询成功: location={}, temp={}", location, response.getNow().getTemp());
            return response;
        }

        throw new RuntimeException("实时天气查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    /**
     * 获取天气预报
     */
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
                + "&lang=zh";

        String json = httpGet(url);
        WeatherForecastResponse response = parseJson(json, WeatherForecastResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("天气预报查询成功: location={}, days={}, count={}", location, days, response.getDaily().size());
            return response;
        }

        throw new RuntimeException("天气预报查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    /**
     * 获取空气质量
     */
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

    /**
     * 获取天气预警
     */
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

    // ==================== Agent工具 ====================

    @AgentTool(name = "query_weather_now", description = "查询实时天气，用户询问当前天气时调用")
    public WeatherNowResponse queryWeatherNowForAgent(Long userId, String location) {
        log.info("Agent调用查询实时天气: userId={}, location={}", userId, location);
        return getWeatherNow(location);
    }

    @AgentTool(name = "query_weather_forecast", description = "查询天气预报，用户询问未来天气时调用")
    public WeatherForecastResponse queryWeatherForecastForAgent(Long userId, String location, Integer days) {
        log.info("Agent调用查询天气预报: userId={}, location={}, days={}", userId, location, days);
        return getWeatherForecast(location, days);
    }

    @AgentTool(name = "query_air_quality", description = "查询空气质量，用户询问空气质量时调用")
    public AirQualityResponse queryAirQualityForAgent(Long userId, Double latitude, Double longitude) {
        log.info("Agent调用查询空气质量: userId={}, lat={}, lon={}", userId, latitude, longitude);
        return getAirQuality(latitude, longitude);
    }

    @AgentTool(name = "query_weather_alert", description = "查询天气预警，用户询问是否有天气预警时调用")
    public WeatherAlertResponse queryWeatherAlertForAgent(Long userId, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预警: userId={}, lat={}, lon={}", userId, latitude, longitude);
        return getWeatherAlert(latitude, longitude);
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 HTTP GET 请求，自动处理 gzip 解压
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

    /**
     * 读取错误流
     */
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

    /**
     * 解析 JSON 响应
     */
    private <T> T parseJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("JSON解析失败: json={}, class={}", json, clazz.getSimpleName(), e);
            throw new RuntimeException("JSON解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 API URL
     */
    private String buildUrl(String path) {
        return weatherConfig.getHost() + path;
    }

    /**
     * URL 编码（处理逗号等特殊字符）
     */
    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
