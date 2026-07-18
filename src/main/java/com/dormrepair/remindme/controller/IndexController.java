package com.dormrepair.remindme.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 * <p>
 * 负责渲染前端页面
 * </p>
 */
@Controller
public class IndexController {

    @GetMapping("/index")
    public String indexPage() {
        return "index";
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/index";
    }
}
