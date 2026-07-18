package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.PushLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推送日志 Repository
 * <p>
 * 对应实体：{@link PushLog}
 * 对应表名：`push_logs`
 * </p>
 */
@Repository
public interface PushLogRepository extends JpaRepository<PushLog, Long> {

    List<PushLog> findByUserIdAndPushedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    List<PushLog> findByReminderId(Long reminderId);

    boolean existsByReminderId(Long reminderId);
}
