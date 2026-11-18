package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.utils.TimeUtils;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 确认会话实体类。对应表 confirmation_sessions，代表用户自然语言解析后生成的「待确认卡片」生命周期：
// 从 INFO_CONFIRM（等待用户确认解析出的标题/时间等信息）→ REMINDER_EXECUTE（到点后等待用户点击已执行/稍后/取消）的完整状态机
@Entity
@Table(name = "confirmation_sessions", indexes = {
        @Index(name = "idx_user_status", columnList = "user_id, status"),
        @Index(name = "idx_expires_at", columnList = "expires_at"),
        @Index(name = "idx_stage", columnList = "stage")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationSession {

    // 会话主键：36 位 UUID 字符串，替代自增 ID 以便在分布式或调用方直接引用
    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    // 所属用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 关联的提醒 id：会话确认后会创建 reminders，这里回填其 id；未确认时为 null
    @Column(name = "reminder_id")
    private Long reminderId;

    // 用户原始自然语言输入，便于解析失败时回溯与重试
    @Column(name = "original_input", columnDefinition = "TEXT")
    private String originalInput;

    // LLM 首次解析出的结构化数据 JSON（title/remindTime/location/priority 等），用于构成确认卡片
    @Column(name = "parsed_data", columnDefinition = "JSON")
    private String parsedData;

    // 解析出的提醒标题（便于列表查询、全文检索时直接取）
    @Column(name = "parsed_title", length = 200)
    private String parsedTitle;

    // 解析出的提醒时间
    @Column(name = "parsed_time")
    private LocalDateTime parsedTime;

    // 解析出的结束时间（可选）
    @Column(name = "parsed_end_time")
    private LocalDateTime parsedEndTime;

    // 解析出的地点（可选）
    @Column(name = "parsed_location", length = 200)
    private String parsedLocation;

    // 解析出的优先级字符串：LOW / NORMAL / HIGH
    @Column(name = "parsed_priority", length = 20)
    private String parsedPriority;

    // 解析出的提前提醒分钟数
    @Column(name = "parsed_advance_minutes")
    private Integer parsedAdvanceMinutes;

    // 当前会话阶段：INFO_CONFIRM-信息确认中（首次创建默认）、REMINDER_EXECUTE-到点执行确认
    @Column(name = "stage", length = 30)
    @Builder.Default
    private String stage = "INFO_CONFIRM";

    // 会话状态（外部生命周期）：PENDING-待处理、CONFIRMED-已确认、CANCELLED-已取消、EXPIRED-已过期
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.PENDING;

    // 会话创建时间，@PrePersist 自动填充
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // 会话过期时间：超过后状态自动标为 EXPIRED，确认按钮失效
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // 最后更新时间，@PrePersist/@PreUpdate 自动维护
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // 用户主动请求重试解析的次数（用户选择「重新解析」时自增）
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    // 已触发提醒的尝试次数（到点后多次推送/发送提醒计数）
    @Column(name = "attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    // 最多尝试提醒次数（默认 3 次，超过后不再主动推送）
    @Column(name = "max_attempts")
    @Builder.Default
    private Integer maxAttempts = 3;

    // 两次尝试之间的间隔分钟数（默认 10 分钟）
    @Column(name = "interval_minutes")
    @Builder.Default
    private Integer intervalMinutes = 10;

    // 插入前钩子：自动写入 createdAt、updatedAt
    @PrePersist
    protected void onCreate() {
        createdAt = TimeUtils.now();
        updatedAt = TimeUtils.now();
    }

    // 更新前钩子：自动刷新 updatedAt
    @PreUpdate
    protected void onUpdate() {
        updatedAt = TimeUtils.now();
    }

    // 会话状态内部枚举：PENDING-待用户操作、CONFIRMED-用户已确认、CANCELLED-用户已取消、EXPIRED-超时未操作
    public enum SessionStatus {
        // 【P2-3 语义：确认会话级·整会话待处理】
        // 归属阶段：confirmation_sessions 粒度的外部生命周期。Agent/用户通过 sessionId 关联整张卡片。
        // 与 TaskStatus.PENDING（子任务单条目）区分：SessionStatus 是整体会话；与 ConfirmStatus.PENDING（单提醒 confirm 阶段）区分：
        //   SessionStatus 覆盖单任务/多任务/周期冲突等所有卡片类型，ConfirmStatus 只对 reminders 表中的单提醒确认生效。
        PENDING,
        CONFIRMED,
        CANCELLED,
        EXPIRED
    }
}
