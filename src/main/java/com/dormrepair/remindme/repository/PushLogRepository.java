package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.PushLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

// 推送日志数据访问接口。操作 push_logs 表，3 类查询：
// 按用户+时间区间查推送记录（推送历史）、按提醒 id 查所有推送（去重）、按提醒 id 是否存在判断是否已推送过
@Repository
public interface PushLogRepository extends JpaRepository<PushLog, Long> {

    // 按用户 id + 推送时间区间取推送日志列表，推送历史/问题排查时取某段时间所有推送给该用户的记录
    List<PushLog> findByUserIdAndPushedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    // 按提醒 id 查所有推送日志，同一条提醒可能多条（重试），供审计/问题回溯
    List<PushLog> findByReminderId(Long reminderId);

    // 按提醒 id 判重（是否存在任意一条），推送前检查避免重复推给用户
    boolean existsByReminderId(Long reminderId);
}
