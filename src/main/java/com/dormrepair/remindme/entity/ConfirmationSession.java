package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "confirmation_sessions", indexes = {
        @Index(name = "idx_user_status", columnList = "user_id, status"),
        @Index(name = "idx_expires_at", columnList = "expires_at"),
        @Index(name = "idx_stage", columnList = "stage")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationSession {

    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reminder_id")
    private Long reminderId;

    @Column(name = "original_input", columnDefinition = "TEXT")
    private String originalInput;

    @Column(name = "parsed_data", columnDefinition = "JSON")
    private String parsedData;

    @Column(name = "parsed_title", length = 200)
    private String parsedTitle;

    @Column(name = "parsed_time")
    private LocalDateTime parsedTime;

    @Column(name = "parsed_location", length = 200)
    private String parsedLocation;

    @Column(name = "parsed_priority", length = 20)
    private String parsedPriority;

    @Column(name = "parsed_advance_minutes")
    private Integer parsedAdvanceMinutes;

    @Column(name = "stage", length = 30)
    @Builder.Default
    private String stage = "INFO_CONFIRM";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.PENDING;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "max_attempts")
    @Builder.Default
    private Integer maxAttempts = 3;

    @Column(name = "interval_minutes")
    @Builder.Default
    private Integer intervalMinutes = 10;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum SessionStatus {
        PENDING,
        CONFIRMED,
        CANCELLED,
        EXPIRED
    }
}