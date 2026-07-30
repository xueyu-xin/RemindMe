package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.request.LoginRequest;
import com.dormrepair.remindme.dto.request.RegisterRequest;
import com.dormrepair.remindme.dto.request.SendCodeRequest;
import com.dormrepair.remindme.dto.response.LoginResponse;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.service.UserService;
import com.dormrepair.remindme.service.VerificationCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

// 认证相关 REST 接口控制器。统一前缀 /api/auth，提供注册、登录、发送验证码三项基础能力，返回可直接使用的 JSON 响应
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {

    // 用户业务服务：注册与登录的核心校验、数据库操作由其完成
    private final UserService userService;
    // 验证码服务：负责生成、存储、校验短信验证码的生命周期
    private final VerificationCodeService verificationCodeService;

    // 用户注册接口。校验手机号与验证码有效性，创建用户记录后返回生成的 userId
    // request - 包含手机号、验证码、密码、昵称等注册信息（由 RegisterRequest 字段约束校验）
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "注册成功");
        response.put("userId", user.getId());

        return ResponseEntity.ok(response);
    }

    // 用户登录接口。校验手机号+密码（或验证码）匹配后返回用户基础信息
    // request - 包含手机号、密码/验证码等登录凭证
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);

        return ResponseEntity.ok(response);
    }

    // 发送验证码接口。按手机号生成 6 位随机数字验证码，写入缓存并设置有效期（TTL），返回验证码供注册/登录使用
    // request - 包含目标手机号
    @PostMapping("/send-code")
    public ResponseEntity<Map<String, Object>> sendCode(@Valid @RequestBody SendCodeRequest request) {
        String phone = request.getPhone();
        String code = verificationCodeService.sendCode(phone);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "验证码发送成功");
        response.put("code", code);

        return ResponseEntity.ok(response);
    }
}
