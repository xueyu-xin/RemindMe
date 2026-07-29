package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.VerificationCode;
import com.dormrepair.remindme.repository.VerificationCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

// 短信验证码服务：负责验证码的生成、校验与过期清理，配合注册/登录流程做手机号实名
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final VerificationCodeRepository verificationCodeRepository;

    // 验证码有效期 5 分钟
    private static final int EXPIRATION_MINUTES = 5;
    // 同一手机号最小重发间隔，避免短信轰炸
    private static final int SEND_INTERVAL_MINUTES = 60;

    // 发送验证码：先做频率限制，再生成 6 位数字码并落库
    @Transactional
    public String sendCode(String phone) {
        // 取近 60 分钟内已发送的记录，存在则判定为频繁
        List<VerificationCode> recentCodes = verificationCodeRepository
                .findByPhoneAndCreatedAtAfter(phone, LocalDateTime.now().minusMinutes(SEND_INTERVAL_MINUTES));

        if (!recentCodes.isEmpty()) {
            throw new IllegalArgumentException("发送过于频繁，请稍后再试");
        }

        String code = generateCode();

        // 落库，过期时间设为当前时间 + 5 分钟
        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setPhone(phone);
        verificationCode.setCode(code);
        verificationCode.setExpiresAt(LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES));
        verificationCode.setUsed(false);

        verificationCodeRepository.save(verificationCode);

        log.info("验证码发送成功：phone={}, code={}", phone, code);

        return code;
    }

    // 校验验证码：命中未使用记录且未过期则标记为已用
    @Transactional
    public void verifyCode(String phone, String code) {
        VerificationCode verificationCode = verificationCodeRepository
                .findByPhoneAndCodeAndUsedFalse(phone, code)
                .orElseThrow(() -> new IllegalArgumentException("验证码错误"));

        // 命中后还要再校一次过期时间，防止临界态
        if (verificationCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期");
        }

        verificationCode.setUsed(true);
        verificationCodeRepository.save(verificationCode);
    }

    // 定时清理过期验证码，每小时跑一次
    @Transactional
    @Scheduled(fixedRate = 3600000)
    public void cleanExpiredCodes() {
        verificationCodeRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.info("已清理过期验证码");
    }

    // 生成 6 位随机数字验证码，范围 [100000, 999999]
    private String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }
}
