package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 周期提醒实例实体类。对应表 recurring_instances，记录由 RecurringReminder 模板按周期自动生成的「某天某条」实例，状态跟随实际 reminders 同步
@Data
@Entity
@Table(name = "recurring_instances", indexes = {
    @Index(name = "idx_recurring_id", columnList = "recurring_id"),
    @Index(name = "idx_remind_date", columnList = "remind_date"),
    @Index(name = "idx_recurring_date", columnList = "recurring_id, remind_date", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class RecurringInstance {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 周期提醒模板 id，对应 recurring_reminders.id
    @Column(name = "recurring_id", nullable = false)
    private Long recurringId;

    // 实际生成的单条提醒 id，对应 reminders.id；尚未生成提醒前为 null
    @Column(name = "reminder_id")
    private Long reminderId;

    // 该实例对应要提醒的日期（提醒发生在哪一天）
    @Column(name = "remind_date", nullable = false)
    private LocalDate remindDate;

    // 实例状态：PENDING-待生成/待处理、GENERATED-已生成提醒、CANCELLED-被用户取消、COMPLETED-已完成；与 reminders.status 联动
    @Column(nullable = false)
    private String status = "PENDING";

    // 实例创建时间，由 JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
