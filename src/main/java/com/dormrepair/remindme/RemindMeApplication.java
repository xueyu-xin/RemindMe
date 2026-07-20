package com.dormrepair.remindme;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 校园生活提醒助手 - 启动类
 */
@SpringBootApplication
@EnableJpaAuditing // 开启 JPA 审计功能（自动填充创建时间、更新时间等）
@EnableScheduling  // 开启定时任务支持
public class RemindMeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RemindMeApplication.class, args);
    }
}