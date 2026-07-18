package com.dormrepair.remindme.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 天气API配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "weather.api")
public class WeatherConfig {

    private String key;
    private String url;
}
