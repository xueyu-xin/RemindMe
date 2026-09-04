package com.dormrepair.remindme.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class LLMConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);   // 10 秒连接超时
        factory.setReadTimeout(30000);      // 30 秒读取超时
        return RestClient.builder().requestFactory(factory);
    }
}
