package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.ScheduleImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 日程导入记录 Repository
 * <p>
 * 对应实体：{@link ScheduleImport}
 * 对应表名：`schedule_imports`
 * </p>
 */
@Repository
public interface ScheduleImportRepository extends JpaRepository<ScheduleImport, Long> {

    List<ScheduleImport> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ScheduleImport> findByUserIdAndStatus(Long userId, String status);

    Optional<ScheduleImport> findByUserIdAndId(Long userId, Long id);
}
