package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.IntentType;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// 对话记录统计实体类。对应表 user_dialog_stats，记录用户每一条对话消息，用于 LLM 动态提示词学习、用户行为分析和历史查询
@Data
@Entity
@Table(name = "user_dialog_stats", indexes = {
        @Index(name = "idx_user_created", columnList = "user_id, created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class UserDialogStats {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 消息角色：user-用户发的输入、bot-LLM 或系统返回的回复
    @Column(name = "role", length = 8)
    private String role;

    // 消息内容文本：对话消息的主存储字段，支持大文本
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    // 会话 id：同一轮连续对话共享同一个 sessionId，用于切分对话上下文
    @Column(name = "session_id", length = 64)
    private String sessionId;

    // 兼容字段：用户输入原始文本（保留历史逻辑，role=user 时与 content 内容一致）
    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    // LLM 解析出的意图类型（仅 role=user 且为提醒类指令时赋值，普通闲聊为 null）
    @Column(name = "intent_type")
    @Enumerated(EnumType.STRING)
    private IntentType intentType;

    // LLM 解析出的提醒时间（解析成功才赋值，与 reminders.remind_time 一致）
    @Column(name = "parsed_time")
    private LocalDateTime parsedTime;

    // LLM 解析出的提醒标题（解析成功才赋值）
    @Column(name = "parsed_title")
    private String parsedTitle;

    // 是否已被后处理任务消费（动态提示词学习、统计聚合等），消费后置 true 避免重复处理
    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    // 消息创建时间，JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
