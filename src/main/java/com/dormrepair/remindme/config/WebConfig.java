package com.dormrepair.remindme.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Spring MVC 全局配置。实现 WebMvcConfigurer 接口，配置后端接口的跨域访问规则，并把用户上传目录映射为可通过 URL 直接访问的静态资源
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // CORS 跨域规则：仅对 /api/** 路径生效
    // allowedOriginPatterns("*")   - 允许任意域名来源，前后端分离部署时客户端端口（如 5173 / 8080）不在同源下也能正常请求
    // allowedMethods               - 放开常用 HTTP 动作 + 预检 OPTIONS
    // allowedHeaders("*")          - 允许客户端自定义请求头（如 user-id、Authorization 等）
    // allowCredentials(true)       - 允许携带 Cookie / 会话凭证
    // maxAge(3600)                 - 预检请求缓存 1 小时，减少重复 OPTIONS 开销
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    // 静态资源映射：把 URL 路径 /uploads/** 指向文件系统中的 ./uploads/ 目录（项目运行根目录）
    // 用户上传的头像、导出文件等保存在此，可通过 <img src="/uploads/xxx.jpg"> 直接访问
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:./uploads/");
    }
}
