package com.dormrepair.remindme.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 认证相关页面跳转控制器（服务端模板渲染）。访问 /login 或 /register 路径时，返回对应的 Thymeleaf 模板名供浏览器加载
@Controller
public class AuthPageController {

    // 登录页面路由：GET /login 时返回 login.html 模板
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    // 注册页面路由：GET /register 时返回 register.html 模板
    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }
}
