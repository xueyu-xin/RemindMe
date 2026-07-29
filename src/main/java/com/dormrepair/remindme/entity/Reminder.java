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

// 单条提醒实体类。对应表 reminders，是本项目核心业务表：存储用户所有提醒，包括一次性提醒、周期提醒生成的实例、Excel 导入产生的课程提醒等
@Data
@Entity
@Table(name = "reminders")
@EntityListeners(AuditingEntityListener.class)
public class Reminder {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 所属用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 提醒标题：简洁描述提醒内容
    @Column(nullable = false)
    private String title;

    // 实际动作发生/事情开始的时间（事件时间）；推送时间 = remind_time - advance_minutes
    @Column(name = "remind_time", nullable = false)
    private LocalDateTime remindTime;

    // 结束时间（可选）：比如会议结束时间、课程下课时间；用于日历视图连续显示
    @Column(name = "end_time")
    private LocalDateTime endTime;

    // 时区：默认 Asia/Shanghai
    @Column(nullable = false)
    private String timezone = "Asia/Shanghai";

    // 提前多少分钟推送（相对 remind_time）；用户未设时用 users.default_advance_minutes 兜底
    @Column(name = "advance_minutes", nullable = false)
    private Integer advanceMinutes = 10;

    // Cron 表达式（可选）：未来支持复杂重复规则时使用，目前多数情况下为 null
    @Column(name = "cron_expression")
    private String cronExpression;

    // 地点（可选）
    private String location;

    // 优先级：LOW / NORMAL / HIGH，默认 NORMAL；影响推送顺序与失败重试策略
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    // 提醒生命周期状态：PENDING-待推送、SENT-已推送、COMPLETED-已完成、CANCELLED-已取消、EXPIRED-已过期
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderStatus status = ReminderStatus.PENDING;

    // 信息确认阶段状态：PENDING-待用户确认卡片、CONFIRMED-已确认、CANCELLED-已取消、DEFAULTED-超时默认确认
    @Column(name = "confirm_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmStatus confirmStatus = ConfirmStatus.PENDING;

    // 到点执行确认阶段状态：PENDING-待用户点击已执行、CONFIRMED-已点已执行、CANCELLED-已点取消、DEFAULTED-超时默认
    @Column(name = "execution_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    // 信息确认阶段的已重试次数（到点未确认时二次提醒）
    @Column(name = "confirm_attempts", nullable = false)
    private Integer confirmAttempts = 0;

    // 上次信息确认重试的时间，避免短时间内反复打扰
    @Column(name = "last_confirm_remind_at")
    private LocalDateTime lastConfirmRemindAt;

    // 提醒来源：USER_INPUT-自然语言、WEATHER_LINK-天气联动、EXCEL_IMPORT-Excel、PERIOD_REMINDER-经期
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderSource source = ReminderSource.USER_INPUT;

    // 创建时间，JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 更新时间，JPA Auditing 自动写入（状态变更、修改时间/标题时刷新）
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
