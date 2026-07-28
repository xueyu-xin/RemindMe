package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.RepeatType;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Entity
@Table(name = "recurring_reminders", indexes = {
    @Index(name = "idx_user_status", columnList = "user_id, status"),
    @Index(name = "idx_date_range", columnList = "start_date, end_date"),
    @Index(name = "idx_repeat_type", columnList = "repeat_type")
})
@EntityListeners(AuditingEntityListener.class)
public class RecurringReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "remind_time", nullable = false)
    private LocalTime remindTime;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_type", nullable = false)
    private RepeatType repeatType;

    private String location;

    @Column(nullable = false)
    private String priority = "NORMAL";

    @Column(name = "advance_minutes", nullable = false)
    private Integer advanceMinutes = 10;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "last_generated_date")
    private LocalDate lastGeneratedDate;

    @Column(nullable = false)
    private String source = "USER_INPUT";

    @Column(nullable = false)
    private String timezone = "Asia/Shanghai";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
