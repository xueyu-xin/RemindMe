package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.IntentType;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 对话统计表 实体类
 * <p>
 * 对应表名：`user_dialog_stats`
 * 用途：记录用户每一次对话输入，用于动态提示词学习和用户行为分析
 * </p>
 */
@Data
@Entity
@Table(name = "user_dialog_stats", indexes = {
        @Index(name = "idx_user_created", columnList = "user_id, created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class UserDialogStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 角色：user-用户消息, bot-机器人回复 */
    @Column(name = "role", length = 8)
    private String role;

    /** 消息内容（新增存储字段，主存储） */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 会话ID，用于关联同一轮对话 */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 原有字段：用户输入文本（保持向后兼容） */
    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    @Column(name = "intent_type")
    @Enumerated(EnumType.STRING)
    private IntentType intentType;

    @Column(name = "parsed_time")
    private LocalDateTime parsedTime;

    @Column(name = "parsed_title")
    private String parsedTitle;

    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
