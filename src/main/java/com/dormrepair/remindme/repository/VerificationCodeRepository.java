package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 验证码数据访问接口。操作 verification_codes 表，覆盖注册/登录场景三大查询：
// 校验手机号+验证码+未使用、查某号近期发送记录（限频）、批量清理过期验证码
@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    // 登录/注册校验：按手机号 + 验证码 + 未使用=true 取单条，命中才视为验证通过
    Optional<VerificationCode> findByPhoneAndCodeAndUsedFalse(String phone, String code);

    // 限频统计：查某手机号指定时间（如 1 分钟前）之后的所有发送记录，超过阈值则拒绝再发
    List<VerificationCode> findByPhoneAndCreatedAtAfter(String phone, LocalDateTime time);

    // 定时清表：删除 expires_at 早于给定时间的所有行，避免无限制增长
    void deleteByExpiresAtBefore(LocalDateTime time);
}
