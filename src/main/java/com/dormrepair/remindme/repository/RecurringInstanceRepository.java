package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.RecurringInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 周期提醒实例数据访问接口。操作 recurring_instances 表，3 类查询：
// 按 (模板id, 日期) 判重（唯一索引）、按 (模板id, 日期) 取单条、按模板 id 查所有实例
@Repository
public interface RecurringInstanceRepository extends JpaRepository<RecurringInstance, Long> {

    // 判重：某模板 + 某日期是否已生成过实例，避免重复创建（配合表上 (recurring_id, remind_date) 唯一索引）
    boolean existsByRecurringIdAndRemindDate(Long recurringId, LocalDate date);

    // 按模板 id + 日期取实例单条，生成提醒前查已存在实例，做幂等
    Optional<RecurringInstance> findByRecurringIdAndRemindDate(Long recurringId, LocalDate date);

    // 按模板 id 查全部实例行，实例状态变更历史/手动调整某模板实例用
    List<RecurringInstance> findByRecurringId(Long recurringId);
}
