package com.dormrepair.remindme.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

// 应用全局配置类。集中注册项目通用的第三方工具 Bean：HTTP 客户端 RestTemplate，
// 以及 Agent 状态推送专用异步线程池（与业务主线程解耦，避免阻塞 ReAct 循环）
@Configuration
@EnableAsync
public class AppConfig {

    // HTTP 客户端 Bean：基于 Spring RestTemplate，配置连接超时与读取超时各 10 秒，天气服务、RAG 文档抓取等场景均通过它发起外部请求
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    // Agent 状态推送专用线程池：
    // - 独立于业务主线程，确保 WebSocket 推送完全不阻塞 Agent ReAct 主循环（实现"无感并行"）
    // - 核心线程数 2：日常并发量不大，足够处理推送
    // - 最大线程数 8：应对多人同时对话的突发情况
    // - 队列容量 500：峰值削峰，丢弃策略为 CallerRuns（推送极度繁忙时由事件发布线程兜底，不丢事件）
    @Bean(name = "agentStatusExecutor")
    public Executor agentStatusExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("agent-status-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
