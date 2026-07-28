package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recurring_instances", indexes = {
    @Index(name = "idx_recurring_id", columnList = "recurring_id"),
    @Index(name = "idx_remind_date", columnList = "remind_date"),
    @Index(name = "idx_recurring_date", columnList = "recurring_id, remind_date", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class RecurringInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recurring_id", nullable = false)
    private Long recurringId;

    @Column(name = "reminder_id")
    private Long reminderId;

    @Column(name = "remind_date", nullable = false)
    private LocalDate remindDate;

    @Column(nullable = false)
    private String status = "PENDING";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
