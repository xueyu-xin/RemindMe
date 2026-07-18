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

/**
 * 验证码业务逻辑服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final VerificationCodeRepository verificationCodeRepository;

    private static final int EXPIRATION_MINUTES = 5;
    private static final int SEND_INTERVAL_MINUTES = 60;

    @Transactional
    public String sendCode(String phone) {
        List<VerificationCode> recentCodes = verificationCodeRepository
                .findByPhoneAndCreatedAtAfter(phone, LocalDateTime.now().minusMinutes(SEND_INTERVAL_MINUTES));

        if (!recentCodes.isEmpty()) {
            throw new IllegalArgumentException("发送过于频繁，请稍后再试");
        }

        String code = generateCode();

        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setPhone(phone);
        verificationCode.setCode(code);
        verificationCode.setExpiresAt(LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES));
        verificationCode.setUsed(false);

        verificationCodeRepository.save(verificationCode);

        log.info("验证码发送成功：phone={}, code={}", phone, code);

        return code;
    }

    @Transactional
    public void verifyCode(String phone, String code) {
        VerificationCode verificationCode = verificationCodeRepository
                .findByPhoneAndCodeAndUsedFalse(phone, code)
                .orElseThrow(() -> new IllegalArgumentException("验证码错误"));

        if (verificationCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期");
        }

        verificationCode.setUsed(true);
        verificationCodeRepository.save(verificationCode);
    }

    @Transactional
    @Scheduled(fixedRate = 3600000)
    public void cleanExpiredCodes() {
        verificationCodeRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.info("已清理过期验证码");
    }

    private String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }
}
