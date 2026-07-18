package com.dormrepair.remindme.service;

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
}
