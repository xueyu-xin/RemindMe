package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.Gender;
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

    @PutMapping("/{userId}")
    public ResponseEntity<User> updateUser(@PathVariable Long userId, @RequestBody Map<String, Object> updates) {
        return userService.getUserById(userId)
                .map(user -> {
                    if (updates.containsKey("nickname")) {
                        user.setNickname((String) updates.get("nickname"));
                    }
                    if (updates.containsKey("gender")) {
                        user.setGender(Gender.valueOf((String) updates.get("gender")));
                    }
                    if (updates.containsKey("city")) {
                        user.setCity((String) updates.get("city"));
                    }
                    if (updates.containsKey("defaultAdvanceMinutes")) {
                        user.setDefaultAdvanceMinutes(((Number) updates.get("defaultAdvanceMinutes")).intValue());
                    }
                    if (updates.containsKey("cycleDays")) {
                        user.setCycleDays(((Number) updates.get("cycleDays")).intValue());
                    }
                    if (updates.containsKey("enablePeriodReminder")) {
                        user.setEnablePeriodReminder((Boolean) updates.get("enablePeriodReminder"));
                    }
                    User saved = userService.save(user);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
