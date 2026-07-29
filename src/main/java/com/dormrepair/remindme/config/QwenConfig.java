package com.dormrepair.remindme.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 阿里 DashScope 千问大模型 API 配置属性类。从 application.yml 中读取 dashscope.api 前缀下的配置项，注入到对应字段供 LLM 服务使用
@Data
@Configuration
@ConfigurationProperties(prefix = "dashscope.api")
public class QwenConfig {
    // DashScope 平台分配的 API 密钥，调用千问模型时作为鉴权凭证
    private String key;
}
