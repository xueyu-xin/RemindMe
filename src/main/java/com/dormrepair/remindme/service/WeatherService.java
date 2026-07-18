package com.dormrepair.remindme.service;

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

    private boolean hasAlert(Map<String, Object> weather) {
        Map<String, Object> now = (Map<String, Object>) weather.get("now");
        String text = (String) now.get("text");
        return text.contains("雨") || text.contains("雪") || text.contains("雾") || text.contains("雷");
    }
}
