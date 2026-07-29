package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.ScheduleImportItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 日程导入明细 Repository
 * <p>
 * 对应实体：{@link ScheduleImportItem}
 * 对应表名：`schedule_import_items`
 * </p>
 */
@Repository
public interface ScheduleImportItemRepository extends JpaRepository<ScheduleImportItem, Long> {

    List<ScheduleImportItem> findByImportId(Long importId);

    List<ScheduleImportItem> findByImportIdAndStatus(Long importId, String status);

    Long countByImportIdAndStatus(Long importId, String status);

    boolean existsByImportIdAndReminderId(Long importId, Long reminderId);
}
