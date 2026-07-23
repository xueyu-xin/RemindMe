package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.config.WeatherConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 天气服务
 * <p>
 * 调用和风天气API获取实时天气信息
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherConfig weatherConfig;
    private final RestTemplate restTemplate = new RestTemplate();

    @SuppressWarnings("unchecked")
    public Map<String, Object> getWeather(String city) {
        try {
            String url = weatherConfig.getUrl() + "?key=" + weatherConfig.getKey() + "&location=" + city;
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return response != null ? response : mockWeather(city);
        } catch (Exception e) {
            log.warn("天气API调用失败，返回Mock数据: {}", e.getMessage());
            return mockWeather(city);
        }
    }

    public String getWeatherAdvice(String city) {
        Map<String, Object> weather = getWeather(city);
        return generateAdvice(weather);
    }

    public boolean checkWeatherAlert(String city) {
        Map<String, Object> weather = getWeather(city);
        return hasAlert(weather);
    }

    private Map<String, Object> mockWeather(String city) {
        Map<String, Object> mock = new HashMap<>();
        Map<String, Object> now = new HashMap<>();
        now.put("temp", "26");
        now.put("text", "晴");
        now.put("windDir", "东南风");
        now.put("windScale", "3");
        now.put("humidity", "60");
        mock.put("code", "200");
        mock.put("now", now);
        mock.put("location", Map.of("name", city));
        return mock;
    }

    @SuppressWarnings("unchecked")
    private String generateAdvice(Map<String, Object> weather) {
        Map<String, Object> now = (Map<String, Object>) weather.get("now");
        String text = (String) now.get("text");
        String temp = (String) now.get("temp");

        StringBuilder advice = new StringBuilder();

        if (text.contains("雨")) {
            advice.append("今天有雨，请记得带伞。");
        } else if (text.contains("雪")) {
            advice.append("今天有雪，注意保暖和路面湿滑。");
        } else if (text.contains("晴")) {
            advice.append("今天天气晴朗，适合户外活动。");
        } else if (text.contains("云")) {
            advice.append("今天多云，天气舒适。");
        } else if (text.contains("雾")) {
            advice.append("今天有雾，出行注意安全。");
        }

        int temperature = Integer.parseInt(temp);
        if (temperature < 10) {
            advice.append("气温较低，建议穿厚外套。");
        } else if (temperature > 30) {
            advice.append("气温较高，注意防暑降温。");
        }

        return advice.toString();
    }

    @SuppressWarnings("unchecked")
    private boolean hasAlert(Map<String, Object> weather) {
        Map<String, Object> now = (Map<String, Object>) weather.get("now");
        String text = (String) now.get("text");
        return text.contains("雨") || text.contains("雪") || text.contains("雾") || text.contains("雷");
    }

    /**
     * Agent工具：查询天气
     * <p>
     * 获取指定城市的天气信息
     * </p>
     *
     * @param userId 用户ID（用于获取用户默认城市，可选）
     * @param city 城市名称（可选，默认北京）
     * @return 天气信息
     */
    @AgentTool(name = "query_weather", description = "查询天气，用户询问天气时调用")
    public Map<String, Object> getWeatherForAgent(Long userId, String city) {
        log.info("Agent调用查询天气: userId={}, city={}", userId, city);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 默认城市
            String targetCity = (city != null && !city.isBlank()) ? city : "北京";
            
            // 调用现有方法获取天气
            Map<String, Object> weather = getWeather(targetCity);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> now = (Map<String, Object>) weather.get("now");
            
            result.put("success", true);
            result.put("city", targetCity);
            result.put("temperature", now != null ? now.get("temp") : "25");
            result.put("condition", now != null ? now.get("text") : "晴");
            result.put("windDirection", now != null ? now.get("windDir") : "南风");
            result.put("windLevel", now != null ? now.get("windScale") : "3");
            result.put("humidity", now != null ? now.get("humidity") : "60");
            result.put("advice", generateAdvice(weather));
            
            log.info("Agent查询天气成功: city={}, temperature={}", targetCity, result.get("temperature"));
            
        } catch (Exception e) {
            log.error("Agent查询天气失败: city={}, error={}", city, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "查询天气失败: " + e.getMessage());
        }
        
        return result;
    }
}
