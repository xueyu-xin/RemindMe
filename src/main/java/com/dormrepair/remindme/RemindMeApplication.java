package com.dormrepair.remindme;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 校园生活提醒助手 - 启动类
 */
@SpringBootApplication
@EnableJpaAuditing // 开启 JPA 审计功能（自动填充创建时间、更新时间等）
@EnableScheduling  // 开启定时任务支持
public class RemindMeApplication {

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(RemindMeApplication.class, args);
    }
}