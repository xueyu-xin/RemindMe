package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.utils.TimeUtils;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 日程批量导入明细实体类。对应表 schedule_import_items，一次导入任务（ScheduleImport）下的每一条待创建提醒都对应一条明细
@Data
@Entity
@Table(name = "schedule_import_items")
@NoArgsConstructor
public class ScheduleImportItem {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 所属导入任务 id，对应 schedule_imports.id
    @Column(name = "import_id", nullable = false)
    private Long importId;

    // 成功创建的提醒 id，对应 reminders.id；尚未创建或失败时为 null
    @Column(name = "reminder_id")
    private Long reminderId;

    // 日程标题：课名或事项名，解析自文件
    @Column(nullable = false)
    private String title;

    // 提醒日期（哪一天上课/发生）
    @Column(name = "remind_date", nullable = false)
    private LocalDate remindDate;

    // 提醒具体时间（几点几分上课/开始）
    @Column(name = "remind_time", nullable = false)
    private LocalTime remindTime;

    // 地点：可选，教室或发生地点
    @Column
    private String location;

    // 重复类型：ONCE-单次、WEEKLY-按周重复；仅记录文件解析出的原始值，真实提醒的重复规则以 recurring_reminders 为准
    @Column(name = "repeat_type")
    private String repeatType = "ONCE";

    // 明细处理状态：PENDING-待处理、SUCCESS-已成功、FAILED-处理失败
    @Column(nullable = false)
    private String status = "PENDING";

    // 处理失败时的错误描述，供调用方展示失败原因
    @Column(name = "error_message")
    private String errorMessage;

    // 明细创建时间，由 @PrePersist 兜底填充
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 插入前钩子：createdAt 为空时自动填充当前时间
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = TimeUtils.now();
        }
    }
}
