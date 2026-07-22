package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒 Repository
 * <p>
 * 对应实体：{@link Reminder}
 * 对应表名：`reminders`
 * </p>
 */
@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByUserIdAndStatus(Long userId, ReminderStatus status);

    List<Reminder> findByUserIdAndRemindTimeBetween(Long userId, LocalDateTime start, LocalDateTime end);

    List<Reminder> findByUserIdAndConfirmStatus(Long userId, ConfirmStatus confirmStatus);

    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.status = 'PENDING' AND r.remindTime <= :now")
    List<Reminder> findPendingReminders(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    List<Reminder> findByUserIdAndStatusAndExecutionStatus(Long userId, ReminderStatus status, ExecutionStatus executionStatus);

    List<Reminder> findByUserIdAndStatusAndConfirmStatusAndExecutionStatus(Long userId, ReminderStatus status, ConfirmStatus confirmStatus, ExecutionStatus executionStatus);

    List<Reminder> findByStatusAndConfirmStatusAndExecutionStatusAndRemindTimeBefore(
            ReminderStatus status, ConfirmStatus confirmStatus, ExecutionStatus executionStatus, LocalDateTime before);

    List<Reminder> findByStatusAndConfirmStatusAndExecutionStatusAndConfirmAttemptsGreaterThanEqual(
            ReminderStatus status, ConfirmStatus confirmStatus, ExecutionStatus executionStatus, Integer minAttempts);
}
