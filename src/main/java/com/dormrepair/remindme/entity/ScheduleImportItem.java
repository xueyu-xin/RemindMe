package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 日程导入明细表
 * <p>
 * 对应表名：`schedule_import_items`
 * 用途：存储批量导入中每条日程的处理状态
 * </p>
 */
@Data
@Entity
@Table(name = "schedule_import_items")
@NoArgsConstructor
public class ScheduleImportItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_id", nullable = false)
    private Long importId;

    @Column(name = "reminder_id")
    private Long reminderId;

    @Column(nullable = false)
    private String title;

    @Column(name = "remind_date", nullable = false)
    private LocalDate remindDate;

    @Column(name = "remind_time", nullable = false)
    private LocalTime remindTime;

    @Column
    private String location;

    @Column(name = "repeat_type")
    private String repeatType = "ONCE";

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
