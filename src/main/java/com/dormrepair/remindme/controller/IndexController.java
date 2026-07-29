package com.dormrepair.remindme.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 首页入口页面控制器。处理根路径与首页路由，将 / 和 /index 映射到 index.html 模板，作为用户登录后进入日历与提醒主页面的入口
@Controller
public class IndexController {

    // 首页路由：GET /index 直接返回 index 模板页面
    @GetMapping("/index")
    public String indexPage() {
        return "index";
    }

    // 根路径重定向：访问域根直接跳转至 /index，保证无论输域名直接跳转到首页
    @GetMapping("/")
    public String root() {
        return "redirect:/index";
    }
}
