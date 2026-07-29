package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.PushChannel;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// 推送日志实体类。对应表 push_logs，记录每一次推送动作的详细信息，用于防重复推送、推送成功率统计、问题排查回溯
@Data
@Entity
@Table(name = "push_logs")
@EntityListeners(AuditingEntityListener.class)
public class PushLog {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联的提醒主键 id，对应 reminders.id
    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    // 接收推送的用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 推送发生时间，由 JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "pushed_at", nullable = false)
    private LocalDateTime pushedAt;

    // 用户是否已读：true-用户已确认/看到，false-未读
    @Column(name = "user_read", nullable = false)
    private Boolean userRead = false;

    // 用户读取时间，未读时为 null
    @Column(name = "read_at")
    private LocalDateTime readAt;

    // 推送使用的渠道（WEBSOCKET/EMAIL/SMS），默认 WEBSOCKET
    @Enumerated(EnumType.STRING)
    @Column(name = "push_channel", nullable = false)
    private PushChannel pushChannel = PushChannel.WEBSOCKET;
}
