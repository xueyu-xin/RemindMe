package com.dormrepair.remindme.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 和风天气 API 配置属性类。从 application.yml 的 weather.api 前缀下读取配置，也支持通过同名环境变量 WEATHER_API_KEY / WEATHER_API_HOST 覆盖
@Data
@Configuration
@ConfigurationProperties(prefix = "weather.api")
public class WeatherConfig {

    // 和风天气开放平台分配的 API 密钥（订阅凭据），实时天气、7 日预报、空气质量、预警等接口调用时作为鉴权参数
    private String key;

    // 和风天气 API 主机完整地址
    private String host;
}
