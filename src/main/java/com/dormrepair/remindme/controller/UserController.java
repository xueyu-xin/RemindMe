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

// 用户信息接口控制器。统一前缀 /api/user，承载用户信息相关全部能力：基本资料查询/修改、手机号查重、经期参数同步、根据经纬度更新城市、头像上传等
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    // 用户主服务：用户实体的增删改查与业务校验
    private final UserService userService;
    // 位置服务：通过经纬度反向地理编码为具体城市/街道
    private final LocationService locationService;
    // 经期服务：根据性别/周期/上次起始日等计算下次经期、安全期、易孕期等
    private final PeriodService periodService;

    // 允许的图片 MIME 类型白名单，避免存储非图片文件
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp"
    );

    // 头像文件大小上限，5MB 足以覆盖常见高清头像，避免占用过多磁盘
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    // 静态资源上传根目录，与 WebConfig 中资源映射路径保持一致
    private static final String UPLOAD_BASE_DIR = "uploads";

    // 头像存放子目录，区分后续可能出现的课表、图片记忆等其他上传场景
    private static final String AVATAR_SUB_DIR = "avatars";

    // 按用户 id 取资料：登录后拉取个人信息使用，不存在返回 404
    @GetMapping("/{userId}")
    public ResponseEntity<User> getUserById(@PathVariable Long userId) {
        return userService.getUserById(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 手机号查重：注册时输入手机号后即可调用查重，避免提交时才发现重复
    @GetMapping("/exists/{phone}")
    public ResponseEntity<Map<String, Object>> existsByPhone(@PathVariable String phone) {
        boolean exists = userService.existsByPhone(phone);

        Map<String, Object> response = new HashMap<>();
        response.put("exists", exists);

        return ResponseEntity.ok(response);
    }

    // 个人资料修改接口：支持昵称/性别/城市/提前分钟数/经期相关字段等混合更新，
    // 经期字段改动或女性用户已有经期设置时，会同步计算一次最新经期状态随响应返回
    @PutMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long userId, @RequestBody Map<String, Object> updates) {
        return userService.getUserById(userId)
                .map(user -> {
                    // 标记经期相关字段是否有变动，变动后需要重新推导下次经期日期
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
                        Integer cycle = toInt(updates.get("cycleDays"));
                        if (cycle != null && cycle <= 0) {
                            Map<String, Object> err = new HashMap<>();
                            err.put("success", false);
                            err.put("message", "月经周期必须大于0");
                            return ResponseEntity.badRequest().body(err);
                        }
                        if (cycle != null) {
                            // 新周期必须大于当前的经期持续天数
                            int currentDuration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
                            if (cycle <= currentDuration) {
                                Map<String, Object> err = new HashMap<>();
                                err.put("success", false);
                                err.put("message", "月经周期必须大于经期持续天数（" + currentDuration + "天）");
                                return ResponseEntity.badRequest().body(err);
                            }
                            user.setCycleDays(cycle);
                            periodRelatedChanged = true;
                        }
                    }
                    if (updates.containsKey("periodDuration")) {
                        // 经期长度变更也触发重新计算
                        Integer dur = toInt(updates.get("periodDuration"));
                        if (dur != null) {
                            if (dur <= 0) {
                                Map<String, Object> err = new HashMap<>();
                                err.put("success", false);
                                err.put("message", "经期持续天数必须大于0");
                                return ResponseEntity.badRequest().body(err);
                            }
                            int effectiveCycle = user.getCycleDays() != null ? user.getCycleDays() : 28;
                            if (dur >= effectiveCycle) {
                                Map<String, Object> err = new HashMap<>();
                                err.put("success", false);
                                err.put("message", "经期持续天数必须小于月经周期（" + effectiveCycle + "天）");
                                return ResponseEntity.badRequest().body(err);
                            }
                            user.setPeriodDuration(dur);
                            periodRelatedChanged = true;
                        }
                    }
                    if (updates.containsKey("lastPeriodStart")) {
                        // 上次经期开始日变更也触发重新计算
                        Object raw = updates.get("lastPeriodStart");
                        if (raw != null && !raw.toString().isBlank()) {
                            try {
                                LocalDate parsed = LocalDate.parse(raw.toString(),
                                        DateTimeFormatter.ISO_LOCAL_DATE);
                                if (parsed.isAfter(LocalDate.now())) {
                                    Map<String, Object> err = new HashMap<>();
                                    err.put("success", false);
                                    err.put("message", "上次经期开始日期不能晚于今天");
                                    return ResponseEntity.badRequest().body(err);
                                }
                                user.setLastPeriodStart(parsed);
                                periodRelatedChanged = true;
                            } catch (Exception ignored) {
                                // 传入的日期格式不对，保留原值
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

                    // 满足以下任一条件，重新计算经期状态一并返回：
                    // 1. 本次修改了任何经期相关字段；2. 用户为女性且配置了周期天数
                    // 目的是修改周期后，下次经期日期立即刷新
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

    // 根据经纬度获取标准格式地址：用和风城市搜索反查经纬度对应的城市，
    // 返回 displayName 给前端填充城市选择输入框，用户点保存后才写库
    @PutMapping("/location")
    public ResponseEntity<Map<String, Object>> updateLocation(
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            Double latitude = ((Number) body.get("latitude")).doubleValue();
            Double longitude = ((Number) body.get("longitude")).doubleValue();

            // 用和风城市搜索反查经纬度对应的标准格式地址（不写库，仅返回给前端）
            String coord = longitude + "," + latitude;
            var hits = locationService.searchCities(coord);
            if (hits.isEmpty()) {
                response.put("success", false);
                response.put("message", "无法解析当前位置对应的城市");
                return ResponseEntity.badRequest().body(response);
            }
            String displayName = hits.get(0).getDisplayName();

            response.put("success", true);
            response.put("address", displayName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取当前位置失败: userId={}", userId, e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // 头像上传接口。校验类型/大小→按年/月/日分目录落盘→写库时同步清理旧头像→返回可直接拼在 URL 上的相对路径
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

            // 7. 返回结果：avatarUrl 直接拼 "/uploads/" + relativePath，可直接作为图片路径使用
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

    // 通用 Object→Integer 工具。兼容 Number、"123" 字符串、null 三类格式，解析失败返回 null
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

    // 通用 Object→Boolean 工具。兼容 Boolean、"true"/"1"/"yes" 字符串、非 0 数字，解析失败返回 null
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
