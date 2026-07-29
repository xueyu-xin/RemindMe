package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.RepeatType;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 周期提醒模板实体类。对应表 recurring_reminders，存储「按日/周/月重复」的提醒规则（模板），
// 实际的单条提醒由调度器每日根据模板生成 recurring_instances + reminders
@Data
@Entity
@Table(name = "recurring_reminders", indexes = {
    @Index(name = "idx_user_status", columnList = "user_id, status"),
    @Index(name = "idx_date_range", columnList = "start_date, end_date"),
    @Index(name = "idx_repeat_type", columnList = "repeat_type")
})
@EntityListeners(AuditingEntityListener.class)
public class RecurringReminder {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 所属用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 提醒标题（每次生成实例时原样复制到 reminders.title）
    @Column(nullable = false)
    private String title;

    // 每天几点提醒（日期部分由 start_date~end_date + repeat_type 组合决定）
    @Column(name = "remind_time", nullable = false)
    private LocalTime remindTime;

    // 周期生效起始日期（含当天）
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    // 周期结束日期（含当天），超过后状态自动置为 COMPLETED
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // 重复粒度：DAILY-按天、WEEKLY-按周、MONTHLY-按月
    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_type", nullable = false)
    private RepeatType repeatType;

    // 地点（可选），生成实例时原样复制
    private String location;

    // 优先级字符串：LOW / NORMAL / HIGH，默认 NORMAL
    @Column(nullable = false)
    private String priority = "NORMAL";

    // 提前多少分钟提醒（相对 remind_time 的偏移，默认 10 分钟）
    @Column(name = "advance_minutes", nullable = false)
    private Integer advanceMinutes = 10;

    // 模板状态：ACTIVE-正常生成实例、PAUSED-用户暂停不再生成、CANCELLED-用户取消、COMPLETED-end_date 已到
    @Column(nullable = false)
    private String status = "ACTIVE";

    // 调度器上次成功生成实例的日期（避免重复生成同一天的实例，便于断点续跑）
    @Column(name = "last_generated_date")
    private LocalDate lastGeneratedDate;

    // 模板来源：USER_INPUT-用户手动创建、EXCEL_IMPORT-Excel 课程表导入、PERIOD_REMINDER-经期自动生成等
    @Column(nullable = false)
    private String source = "USER_INPUT";

    // 时区，默认 Asia/Shanghai；跨国场景下统一按该时区生成
    @Column(nullable = false)
    private String timezone = "Asia/Shanghai";

    // 创建时间，JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 更新时间，JPA Auditing 自动写入（修改状态/时间范围时刷新）
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
