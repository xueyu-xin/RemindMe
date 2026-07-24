package com.dormrepair.remindme.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 天气API配置
 * <p>
 * 支持从环境变量读取配置：
 * - WEATHER_API_KEY: API密钥
 * - WEATHER_API_HOST: API主机完整URL（如 https://api.qweather.com）
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "weather.api")
public class WeatherConfig {

    /**
     * API密钥
     */
    private String key;

    /**
     * API主机完整URL（如 https://api.qweather.com）
     */
    private String host;
}
