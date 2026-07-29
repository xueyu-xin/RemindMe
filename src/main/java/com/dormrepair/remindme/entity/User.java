package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.Gender;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户表 实体类
 * <p>
 * 对应表名：`users`
 * 用途：存储所有注册用户的核心信息
 * </p>
 */
@Data
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 11)
    private String phone;

    @Column(nullable = false)
    private String password;

    private String nickname;

    @Lob
    private String avatar;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender = Gender.PRIVATE;

    @Column(nullable = false)
    private String city = "广州";

    @Column(name = "default_advance_minutes", nullable = false)
    private Integer defaultAdvanceMinutes = 10;

    @Column(name = "remind_interval", nullable = false)
    private Integer remindInterval = 10;

    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays = 28;

    @Column(name = "period_remind_postponed", nullable = false)
    private Boolean periodRemindPostponed = false;

    @Column(name = "period_confirm_count", nullable = false)
    private Integer periodConfirmCount = 0;

    @Column(name = "period_remind_postponed_count", nullable = false)
    private Integer periodRemindPostponedCount = 0;

    @Column(name = "last_period_start")
    private LocalDate lastPeriodStart;

    @Column(name = "semester_start_date")
    private LocalDate semesterStartDate;

    @Column(name = "period_duration", nullable = false)
    private Integer periodDuration = 5;

    @Column(name = "enable_period_reminder", nullable = false)
    private Boolean enablePeriodReminder = true;

    @Column(name = "period_confirm_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmStatus periodConfirmStatus = ConfirmStatus.PENDING;

    @Column(name = "period_postponed_until")
    private LocalDate periodPostponedUntil;

    @Column(name = "period_postpone_count", nullable = false)
    private Integer periodPostponeCount = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
