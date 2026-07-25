package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.Gender;
import com.dormrepair.remindme.service.LocationService;
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
    private final LocationService locationService;

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
                        user.setDefaultAdvanceMinutes(toInt(updates.get("defaultAdvanceMinutes")));
                    }
                    if (updates.containsKey("cycleDays")) {
                        user.setCycleDays(toInt(updates.get("cycleDays")));
                    }
                    if (updates.containsKey("enablePeriodReminder")) {
                        user.setEnablePeriodReminder(toBoolean(updates.get("enablePeriodReminder")));
                    }
                    User saved = userService.save(user);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 根据经纬度更新用户默认城市
     */
    @PutMapping("/location")
    public ResponseEntity<Map<String, Object>> updateLocation(
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            Double latitude = ((Number) body.get("latitude")).doubleValue();
            Double longitude = ((Number) body.get("longitude")).doubleValue();

            String address = locationService.reverseGeocode(latitude, longitude);
            userService.updateDefaultCity(userId, address);

            response.put("success", true);
            response.put("address", address);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("更新用户位置失败: userId={}", userId, e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String str = ((String) value).trim().toLowerCase();
            return "true".equals(str) || "1".equals(str) || "yes".equals(str);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return null;
    }
}
