package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.PeriodStatusResponse;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.Gender;
import com.dormrepair.remindme.service.LocationService;
import com.dormrepair.remindme.service.PeriodService;
import com.dormrepair.remindme.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    private final PeriodService periodService;

    /** 允许的图片类型 */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp"
    );

    /** 最大文件大小：5MB */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /** 上传根目录 */
    private static final String UPLOAD_BASE_DIR = "uploads";

    /** 头像子目录 */
    private static final String AVATAR_SUB_DIR = "avatars";

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
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long userId, @RequestBody Map<String, Object> updates) {
        return userService.getUserById(userId)
                .map(user -> {
                    boolean periodRelatedChanged = false;

                    if (updates.containsKey("nickname")) {
                        user.setNickname((String) updates.get("nickname"));
                    }
                    if (updates.containsKey("gender")) {
                        Object raw = updates.get("gender");
                        if (raw instanceof String s) {
                            user.setGender(Gender.valueOf(s));
                            periodRelatedChanged = true;
                        }
                    }
                    if (updates.containsKey("city")) {
                        user.setCity((String) updates.get("city"));
                    }
                    if (updates.containsKey("defaultAdvanceMinutes")) {
                        user.setDefaultAdvanceMinutes(toInt(updates.get("defaultAdvanceMinutes")));
                    }
                    if (updates.containsKey("cycleDays")) {
                        user.setCycleDays(toInt(updates.get("cycleDays")));
                        periodRelatedChanged = true;
                    }
                    if (updates.containsKey("periodDuration")) {
                        // 兼容扩展：经期长度变更也触发重新计算
                        Integer dur = toInt(updates.get("periodDuration"));
                        if (dur != null) {
                            user.setPeriodDuration(dur);
                            periodRelatedChanged = true;
                        }
                    }
                    if (updates.containsKey("lastPeriodStart")) {
                        // 兼容扩展：上次经期开始日变更也触发重新计算
                        Object raw = updates.get("lastPeriodStart");
                        if (raw != null && !raw.toString().isBlank()) {
                            try {
                                user.setLastPeriodStart(LocalDate.parse(raw.toString(),
                                        DateTimeFormatter.ISO_LOCAL_DATE));
                                periodRelatedChanged = true;
                            } catch (Exception ignored) {
                                // 前端传的日期格式不对，保留原值
                            }
                        }
                    }
                    if (updates.containsKey("enablePeriodReminder")) {
                        user.setEnablePeriodReminder(toBoolean(updates.get("enablePeriodReminder")));
                        periodRelatedChanged = true;
                    }
                    User saved = userService.save(user);

                    Map<String, Object> resp = new HashMap<>();
                    resp.put("user", saved);

                    // 经期相关字段改了，或者用户是女性且有经期设置 → 同步算一次最新经期状态一并返回，
                    // 前端拿到新的 nextPeriodDate，保证"我的"页面手动改周期后，下次经期日期同步刷新"。
                    boolean shouldCalc = periodRelatedChanged
                            || (saved.getGender() == Gender.FEMALE && (saved.getCycleDays() != null && saved.getCycleDays() > 0));
                    if (shouldCalc) {
                        try {
                            PeriodStatusResponse status = periodService.calculatePeriodStatus(saved);
                            resp.put("periodStatus", status);
                        } catch (Exception e) {
                            log.warn("更新用户后计算经期状态失败，跳过: userId={}", userId, e);
                        }
                    }
                    return ResponseEntity.ok(resp);
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

    /**
     * 上传用户头像
     * <p>
     * 校验文件类型和大小，保存到 uploads/avatars/年/月/日/ 目录，
     * 数据库存储相对路径，返回可访问的完整URL
     * </p>
     */
    @PostMapping("/avatar")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @RequestParam Long userId,
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> response = new HashMap<>();

        // 1. 校验文件非空
        if (file == null || file.isEmpty()) {
            response.put("success", false);
            response.put("message", "请选择要上传的图片");
            return ResponseEntity.badRequest().body(response);
        }

        // 2. 校验文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            response.put("success", false);
            response.put("message", "图片大小不能超过5MB");
            return ResponseEntity.badRequest().body(response);
        }

        // 3. 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            response.put("success", false);
            response.put("message", "仅支持 PNG、JPG、JPEG、GIF、WEBP 格式");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // 4. 生成存储路径：avatars/年/月/日/时间戳_原文件名.扩展名
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String originalFilename = file.getOriginalFilename();
            String fileName = System.currentTimeMillis() + "_" + originalFilename;
            String relativePath = AVATAR_SUB_DIR + "/" + datePath + "/" + fileName;

            // 5. 创建目录并保存文件
            //    使用绝对路径，避免 Tomcat 把相对路径解析到临时工作目录
            Path uploadDir = Paths.get(UPLOAD_BASE_DIR).toAbsolutePath();
            Path fullDirPath = uploadDir.resolve(AVATAR_SUB_DIR).resolve(datePath);
            if (!Files.exists(fullDirPath)) {
                Files.createDirectories(fullDirPath);
            }
            Path targetPath = uploadDir.resolve(relativePath);
            file.transferTo(targetPath.toFile());

            log.info("头像上传成功: userId={}, path={}", userId, relativePath);

            // 6. 更新数据库（同时删除旧头像）
            userService.updateAvatar(userId, relativePath);

            // 7. 返回结果
            response.put("success", true);
            response.put("message", "头像上传成功");
            response.put("avatarUrl", "/uploads/" + relativePath);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("头像上传失败: userId={}", userId, e);
            response.put("success", false);
            response.put("message", "头像上传失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
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
