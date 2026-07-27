package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.dto.request.LoginRequest;
import com.dormrepair.remindme.dto.request.RegisterRequest;
import com.dormrepair.remindme.dto.response.LoginResponse;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 用户业务逻辑服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verificationCodeService;

    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new IllegalArgumentException("该手机号已注册");
        }

        verificationCodeService.verifyCode(request.getPhone(), request.getCode());

        User user = new User();
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname("用户" + request.getPhone().substring(7));

        return userRepository.save(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByPhone(request.getPhone())
                .orElseThrow(() -> new IllegalArgumentException("账号或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("账号或密码错误");
        }

        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());

        return response;
    }

    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    public boolean existsByPhone(String phone) {
        return userRepository.existsByPhone(phone);
    }

    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    // ==================== Agent工具 ====================

    /**
     * 获取用户偏好设置
     * <p>
     * 用户询问默认城市、默认提前量、时区时调用
     * </p>
     */
    @AgentTool(name = "get_user_preferences", description = "获取用户偏好设置，用户询问默认城市、默认提前量、时区时调用")
    public Map<String, Object> getUserPreferences(Long userId) {
        log.info("Agent调用获取用户偏好: userId={}", userId);

        Map<String, Object> result = new HashMap<>();

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            result.put("success", false);
            result.put("error", "用户不存在: userId=" + userId);
            return result;
        }

        User user = userOpt.get();
        result.put("success", true);
        result.put("nickname", user.getNickname());
        result.put("city", user.getCity());
        result.put("defaultAdvanceMinutes", user.getDefaultAdvanceMinutes());
        result.put("timezone", "Asia/Shanghai");
        result.put("remindInterval", user.getRemindInterval());
        result.put("enablePeriodReminder", user.getEnablePeriodReminder());

        return result;
    }

    /**
     * 修改用户偏好设置
     * <p>
     * 用户要求更改默认城市、默认提前量、昵称时调用
     * </p>
     *
     * @param userId     用户ID（系统自动填充）
     * @param preference 偏好项名称：city / defaultAdvanceMinutes / nickname
     * @param value      新值
     * @return 修改结果
     */
    @AgentTool(name = "set_user_preferences", description = "修改用户偏好设置，用户要求更改默认城市、默认提前量、昵称时调用")
    @Transactional
    public Map<String, Object> setUserPreferences(Long userId, String preference, String value) {
        log.info("Agent调用修改用户偏好: userId={}, preference={}, value={}", userId, preference, value);

        Map<String, Object> result = new HashMap<>();
        result.put("preference", preference);
        result.put("newValue", value);

        // 验证用户是否存在
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            result.put("success", false);
            result.put("error", "用户不存在: userId=" + userId);
            return result;
        }

        User user = userOpt.get();

        // 验证 preference 是否支持
        if (preference == null || preference.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "偏好项不能为空，支持：city、defaultAdvanceMinutes、nickname");
            return result;
        }

        preference = preference.trim();
        String fieldDisplayName;

        switch (preference) {
            case "city":
                if (value == null || value.trim().isEmpty()) {
                    result.put("success", false);
                    result.put("error", "城市名称不能为空");
                    return result;
                }
                user.setCity(value.trim());
                fieldDisplayName = "默认城市";
                break;

            case "defaultAdvanceMinutes":
                int advanceMinutes;
                try {
                    advanceMinutes = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "默认提前量必须为整数");
                    return result;
                }
                if (advanceMinutes < 0 || advanceMinutes > 1440) {
                    result.put("success", false);
                    result.put("error", "默认提前量范围为 0-1440 分钟");
                    return result;
                }
                user.setDefaultAdvanceMinutes(advanceMinutes);
                fieldDisplayName = "默认提前量";
                break;

            case "nickname":
                if (value == null || value.trim().isEmpty()) {
                    result.put("success", false);
                    result.put("error", "昵称不能为空");
                    return result;
                }
                user.setNickname(value.trim());
                fieldDisplayName = "昵称";
                break;

            default:
                result.put("success", false);
                result.put("error", "不支持的偏好项: " + preference + "，支持：city、defaultAdvanceMinutes、nickname");
                return result;
        }

        userRepository.save(user);

        result.put("success", true);
        result.put("message", "已修改" + fieldDisplayName + "为：" + value);
        result.put("field", preference);
        return result;
    }

    /**
     * 更新用户默认城市
     */
    @Transactional
    public void updateDefaultCity(Long userId, String city) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        user.setCity(city);
        userRepository.save(user);
        log.info("已更新用户 {} 的默认城市为: {}", userId, city);
    }

    /**
     * 更新用户头像
     *
     * @param userId       用户ID
     * @param avatarPath   头像相对路径（如 avatars/2026/07/28/xxx.png）
     * @return 更新后的用户对象
     */
    @Transactional
    public User updateAvatar(Long userId, String avatarPath) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        // 记录旧头像路径，用于删除旧文件
        String oldAvatar = user.getAvatar();

        user.setAvatar(avatarPath);
        User saved = userRepository.save(user);
        log.info("已更新用户 {} 的头像为: {}", userId, avatarPath);

        // 删除旧头像文件（如果不是默认头像）
        if (oldAvatar != null && !oldAvatar.isEmpty() && oldAvatar.startsWith("avatars/")) {
            try {
                java.nio.file.Path oldFile = java.nio.file.Paths.get("uploads", oldAvatar).toAbsolutePath();
                if (java.nio.file.Files.exists(oldFile)) {
                    java.nio.file.Files.delete(oldFile);
                    log.info("已删除旧头像: {}", oldAvatar);
                }
            } catch (Exception e) {
                log.warn("删除旧头像失败: {}, 错误: {}", oldAvatar, e.getMessage());
            }
        }

        return saved;
    }
}
