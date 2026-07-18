package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 验证码 Repository
 * <p>
 * 对应实体：{@link VerificationCode}
 * 对应表名：`verification_codes`
 * </p>
 */
@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    Optional<VerificationCode> findByPhoneAndCodeAndUsedFalse(String phone, String code);

    List<VerificationCode> findByPhoneAndCreatedAtAfter(String phone, LocalDateTime time);

    void deleteByExpiresAtBefore(LocalDateTime time);
}
