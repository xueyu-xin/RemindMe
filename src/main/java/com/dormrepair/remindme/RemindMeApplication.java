package com.dormrepair.remindme;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

// Spring Boot 启动入口。
@SpringBootApplication(exclude = {
        org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class
})
@EnableJpaAuditing
@EnableScheduling
public class RemindMeApplication {

    // 启动后立即执行的初始化钩子：JVM 默认时区锁死为 Asia/Shanghai，保证 LocalDateTime、调度 cron、日志时间等与北京时间一致
    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    // JVM 主入口，启动内嵌 Tomcat 并初始化 Spring 容器
    public static void main(String[] args) {
        SpringApplication.run(RemindMeApplication.class, args);
    }
}
