package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.ScheduleImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// 日程批量导入任务数据访问接口。操作 schedule_imports 表，覆盖任务列表/按状态过滤/用户+id 取单个任务 三个常见查询
@Repository
public interface ScheduleImportRepository extends JpaRepository<ScheduleImport, Long> {

    // 查某用户全部导入任务，按创建时间倒序（最新的在前），导入历史页用
    List<ScheduleImport> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 按用户 id + 任务状态过滤（如只看 SUCCESS 或 PARTIAL）
    List<ScheduleImport> findByUserIdAndStatus(Long userId, String status);

    // 按用户 id + 导入任务 id 取单条任务，加用户维度权限校验，防止越权访问别人的导入任务
    Optional<ScheduleImport> findByUserIdAndId(Long userId, Long id);
}
