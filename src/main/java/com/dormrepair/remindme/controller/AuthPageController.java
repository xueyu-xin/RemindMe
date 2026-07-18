package com.dormrepair.remindme.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 认证页面控制器
 * <p>
 * 负责渲染登录和注册页面
 * </p>
 */
@Controller
public class AuthPageController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }
}
