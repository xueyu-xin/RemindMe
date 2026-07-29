package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// 验证码实体类。对应表 verification_codes，存储注册/登录/找回密码等场景下发的手机验证码，支持过期校验与使用状态追踪
@Data
@Entity
@Table(name = "verification_codes")
@EntityListeners(AuditingEntityListener.class)
public class VerificationCode {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 接收验证码的手机号，11 位国内手机号格式
    @Column(nullable = false, length = 11)
    private String phone;

    // 验证码字符串，6 位数字
    @Column(nullable = false, length = 6)
    private String code;

    // 过期时间：超过该时间点校验一律返回失效，默认 5 分钟
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // 是否已使用：true-已通过校验，不再允许重复使用；false-可用
    @Column(nullable = false)
    private Boolean used = false;

    // 发送时间，JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
