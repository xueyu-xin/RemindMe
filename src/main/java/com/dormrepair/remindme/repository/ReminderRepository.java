package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // 按用户 id + 多个生命周期状态查列表（如查 PENDING + REMINDED 两种状态的提醒）
    List<Reminder> findByUserIdAndStatusIn(Long userId, List<ReminderStatus> statuses);

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
    // 排除取消的；必须状态=CONFIRMED；
    // 时间段重叠判定：r.remindTime <= :endTime AND COALESCE(r.endTime, r.remindTime) > :startTime
    //   r.remindTime <= :endTime → 包容边界，防止单点提醒恰好在边界时漏查
    //   COALESCE(...) > :startTime → 严格大于，保证相邻时间段（如 14:00-15:00 与 15:00-16:00）不误报
    // 单点提醒（endTime 为 NULL）使用 remindTime 作为结束时间判断重叠
    // 时间段提醒（有 endTime）使用 endTime 判断重叠
    // 支持跨天提醒检测（不再限制 DATE(r.remindTime) = DATE(:startTime)）
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId " +
           "AND r.status != 'CANCELLED' " +
           "AND r.confirmStatus = 'CONFIRMED' " +
           "AND r.remindTime <= :endTime " +
           "AND COALESCE(r.endTime, r.remindTime) > :startTime")
    List<Reminder> findOverlappingReminders(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    // 按 ID 列表 + 用户 ID 批量更新状态，用于取消提醒的原子操作
    @Modifying
    @Query("UPDATE Reminder r SET r.status = :status WHERE r.id IN :ids AND r.userId = :userId")
    int bulkCancelByIds(@Param("ids") List<Long> ids,
                        @Param("userId") Long userId,
                        @Param("status") ReminderStatus status);

    // 按 userId + title + remindTime + 状态集合 计数，用于批量创建前的去重检查
    // 匹配 reminders 表中未取消/未过期的同用户同标题同时间提醒，避免重复创建
    long countByUserIdAndTitleAndRemindTimeAndStatusIn(Long userId,
                                                        String title,
                                                        LocalDateTime remindTime,
                                                        List<ReminderStatus> statuses);

    // 根据父周期模板ID查询所有周期实例提醒
    // 用于周期模板级联操作（如取消/暂停模板时批量处理其生成的实例）
    List<Reminder> findByParentRecurringId(Long parentRecurringId);

    // 查询某时间段内与周期实例冲突的普通提醒（排除自身）
    // 仅查询 isRecurringInstance=false 的普通提醒，周期实例之间的冲突由模板层面统一管理
    // excludeId 为 null 时不做排除；状态排除 CANCELLED
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId " +
           "AND r.isRecurringInstance = false " +
           "AND r.status <> 'CANCELLED' " +
           "AND r.remindTime BETWEEN :startTime AND :endTime " +
           "AND (:excludeId IS NULL OR r.id <> :excludeId)")
    List<Reminder> findConflictingNonRecurringReminders(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("excludeId") Long excludeId
    );

    // 导出查询：按用户 + 状态集合 + created_at 日期范围查提醒，创建时间升序
    // startTime/endTime 为 null 时不限制对应边界（左闭右开：createdAt >= startTime 且 createdAt < endTime）
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId " +
           "AND r.status IN :statuses " +
           "AND (:startTime IS NULL OR r.createdAt >= :startTime) " +
           "AND (:endTime IS NULL OR r.createdAt < :endTime) " +
           "ORDER BY r.createdAt ASC")
    List<Reminder> findForExport(
            @Param("userId") Long userId,
            @Param("statuses") List<ReminderStatus> statuses,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
