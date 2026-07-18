package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户信息接口控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{userId}")
    public ResponseEntity<User> getUserById(@PathVariable Long userId) {
        return userService.getUserById(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/exists/{phone}")
    public ResponseEntity<Map<String, Object>> existsByPhone(@PathVariable String phone) {
        boolean exists = userService.existsByPhone(phone);

        Map<String, Object> response = new HashMap<>();
        response.put("exists", exists);

        return ResponseEntity.ok(response);
    }
}
