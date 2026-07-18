package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.PushChannel;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 推送日志表 实体类
 * <p>
 * 对应表名：`push_logs`
 * 用途：记录每一次推送的详细日志，用于防重复推送和数据分析
 * </p>
 */
@Data
@Entity
@Table(name = "push_logs")
@EntityListeners(AuditingEntityListener.class)
public class PushLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @CreatedDate
    @Column(name = "pushed_at", nullable = false)
    private LocalDateTime pushedAt;

    @Column(name = "user_read", nullable = false)
    private Boolean userRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_channel", nullable = false)
    private PushChannel pushChannel = PushChannel.WEBSOCKET;
}
