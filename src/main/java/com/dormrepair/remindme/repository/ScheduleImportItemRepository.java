package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.ScheduleImportItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// 日程批量导入明细数据访问接口。操作 schedule_import_items 表，一条导入任务对应多条明细；
// 覆盖按任务查明细、按状态取明细、统计各状态条数、防重复创建提醒（导入任务+提醒id唯一）
@Repository
public interface ScheduleImportItemRepository extends JpaRepository<ScheduleImportItem, Long> {

    // 按导入任务 id 查该任务下全部明细行
    List<ScheduleImportItem> findByImportId(Long importId);

    // 按导入任务 id + 明细状态过滤，如取 FAILED 明细
    List<ScheduleImportItem> findByImportIdAndStatus(Long importId, String status);

    // 统计某导入任务下某状态的条目数（如 SUCCESS 条数/FAILED 条数），回填 schedule_imports.success_count/failed_count
    Long countByImportIdAndStatus(Long importId, String status);

    // 判重：某任务+某提醒 id 是否已存在；多次重试同一导入任务时避免重复创建同一提醒
    boolean existsByImportIdAndReminderId(Long importId, Long reminderId);
}
