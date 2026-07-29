package com.dormrepair.remindme.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// Spring Security 安全框架配置。注册 BCrypt 密码加密器用于用户密码散列存储，同时关闭 CSRF 防护并放行全部 HTTP 接口，登录态由客户端会话自行管理
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 密码加密器 Bean：采用 BCrypt 算法（内置随机 salt + 自适应 cost），注册、修改密码、登录校验均通过此 Bean 完成哈希与比对
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // HTTP 安全过滤链主配置
    // csrf.disable()        - 关闭 CSRF Token 校验，前后端分离架构下客户端通过 HTTP Header 携带会话，无需额外表单 Token
    // anyRequest.permitAll()- 放行所有请求路径，接口级权限控制放在各 Controller 层或业务层处理，Security 仅负责提供密码加密能力
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );
        return http.build();
    }
}
