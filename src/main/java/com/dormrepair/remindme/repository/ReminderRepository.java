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

// 提醒数据访问接口。操作 reminders 表，封装 9 个派生查询 + 2 个自定义 JPQL；
// 覆盖按用户/状态/时间区间的列表查询、重叠提醒判定、调度器扫表取待处理提醒等场景
@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    // 按用户 id + 生命周期状态查提醒列表（如查某用户所有 COMPLETED）
    List<Reminder> findByUserIdAndStatus(Long userId, ReminderStatus status);

    // 按用户 id + 提醒时间闭区间查列表，日程视图某时间段取数用
    List<Reminder> findByUserIdAndRemindTimeBetween(Long userId, LocalDateTime start, LocalDateTime end);

    // 按用户 id + 信息确认阶段状态查列表，首页拉取待确认卡片用
    List<Reminder> findByUserIdAndConfirmStatus(Long userId, ConfirmStatus confirmStatus);

    // 自定义 JPQL：查某用户状态=PENDING 且 remind_time ≤ 给定 now 的提醒，调度器扫待推送任务用
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.status = 'PENDING' AND r.remindTime <= :now")
    List<Reminder> findPendingReminders(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    // 按用户 id + 生命周期状态 + 到点执行确认状态 三维查列表
    List<Reminder> findByUserIdAndStatusAndExecutionStatus(Long userId, ReminderStatus status, ExecutionStatus executionStatus);

    // 四维组合查询：用户 id + 生命周期 + 信息确认 + 到点执行确认，精细筛选用
    List<Reminder> findByUserIdAndStatusAndConfirmStatusAndExecutionStatus(Long userId, ReminderStatus status, ConfirmStatus confirmStatus, ExecutionStatus executionStatus);

    // 按三维状态 + 提醒时间 before 某时刻，调度器批量扫「到点还没处理」的提醒
    List<Reminder> findByStatusAndConfirmStatusAndExecutionStatusAndRemindTimeBefore(
            ReminderStatus status, ConfirmStatus confirmStatus, ExecutionStatus executionStatus, LocalDateTime before);

    // 按三维状态 + confirmAttempts ≥ minAttempts，扫已经重试到上限的提醒，触发最后通知或自动关闭
    List<Reminder> findByStatusAndConfirmStatusAndExecutionStatusAndConfirmAttemptsGreaterThanEqual(
            ReminderStatus status, ConfirmStatus confirmStatus, ExecutionStatus executionStatus, Integer minAttempts);

    // 自定义 JPQL：查某用户在 [startTime, endTime] 时间段内有时间重叠的已确认提醒，
    // 排除取消的；必须状态=CONFIRMED；提醒日期=startTime 当天；remindTime<endTime 且 (endTime 为空或 endTime>startTime)
    // 新建提醒时防重、或冲突检测用
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId " +
           "AND r.status != 'CANCELLED' " +
           "AND r.confirmStatus = 'CONFIRMED' " +
           "AND DATE(r.remindTime) = DATE(:startTime) " +
           "AND r.remindTime < :endTime " +
           "AND (r.endTime IS NULL OR r.endTime > :startTime)")
    List<Reminder> findOverlappingReminders(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
