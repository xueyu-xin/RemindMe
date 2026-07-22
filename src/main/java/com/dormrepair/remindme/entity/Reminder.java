package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 提醒表 实体类
 * <p>
 * 对应表名：`reminders`
 * 用途：存储所有用户设置的提醒
 * </p>
 */
@Data
@Entity
@Table(name = "reminders")
@EntityListeners(AuditingEntityListener.class)
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "remind_time", nullable = false)
    private LocalDateTime remindTime;

    @Column(nullable = false)
    private String timezone = "Asia/Shanghai";

    @Column(name = "advance_minutes", nullable = false)
    private Integer advanceMinutes = 10;

    @Column(name = "cron_expression")
    private String cronExpression;

    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "confirm_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmStatus confirmStatus = ConfirmStatus.PENDING;

    @Column(name = "execution_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    @Column(name = "confirm_attempts", nullable = false)
    private Integer confirmAttempts = 0;

    @Column(name = "last_confirm_remind_at")
    private LocalDateTime lastConfirmRemindAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderSource source = ReminderSource.USER_INPUT;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
