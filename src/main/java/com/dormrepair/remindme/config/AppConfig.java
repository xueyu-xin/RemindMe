package com.dormrepair.remindme.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

// 应用全局配置类。集中注册项目通用的第三方工具 Bean，目前承载 HTTP 客户端 RestTemplate，供天气、地理编码等外部 API 调用使用
@Configuration
public class AppConfig {

    // HTTP 客户端 Bean：基于 Spring RestTemplate，配置连接超时与读取超时各 10 秒，天气服务、RAG 文档抓取等场景均通过它发起外部请求
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
}
