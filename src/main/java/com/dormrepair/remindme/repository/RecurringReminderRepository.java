package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.RecurringReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

// 周期提醒模板数据访问接口。操作 recurring_reminders 表，3 个查询：
// 取某用户 ACTIVE 模板列表、全表扫 ACTIVE 模板（调度器每日 1 点跑）、按时间区间取用户范围内模板
@Repository
public interface RecurringReminderRepository extends JpaRepository<RecurringReminder, Long> {

    // 按用户 id + 模板状态（ACTIVE/PAUSED/CANCELLED/COMPLETED）查列表
    List<RecurringReminder> findByUserIdAndStatus(Long userId, String status);

    // 全表按状态扫（只看 ACTIVE），调度器每天凌晨 1 点为所有 ACTIVE 模板生成当天 recurring_instances 用
    List<RecurringReminder> findByStatus(String status);

    // 按用户 id + 状态 + 模板时间区间重叠范围查：start_date < endDate 且 end_date > startDate，
    // 表示模板生效范围与 [startDate, endDate] 有交集，取交集内所有模板
    List<RecurringReminder> findByUserIdAndStatusAndStartDateBeforeAndEndDateAfter(
            Long userId, String status, LocalDate startDate, LocalDate endDate);
}
