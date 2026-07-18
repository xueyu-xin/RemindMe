package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.request.LoginRequest;
import com.dormrepair.remindme.dto.request.RegisterRequest;
import com.dormrepair.remindme.dto.response.LoginResponse;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.service.UserService;
import com.dormrepair.remindme.service.VerificationCodeService;
import com.dormrepair.remindme.utils.JwtUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {

    private final UserService userService;
    private final VerificationCodeService verificationCodeService;
    private final JwtUtils jwtUtils;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "注册成功");
        response.put("userId", user.getId());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        String token = jwtUtils.generateToken(response.getUserId(), request.getPhone());
        response.setToken(token);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/send-code")
    public ResponseEntity<Map<String, Object>> sendCode(@RequestParam String phone) {
        String code = verificationCodeService.sendCode(phone);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "验证码发送成功");
        response.put("code", code);

        return ResponseEntity.ok(response);
    }
}
