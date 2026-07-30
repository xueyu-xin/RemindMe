package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 确认会话数据访问接口。操作 confirmation_sessions 表，6 个查询：
// 按 sessionId 取单条（纯主键）、按 sessionId+userId 带权限取、按用户+状态查列表、
// 按状态+过期时间 before（扫过期）、用户全量会话列表、按状态+过期时间批量删除
@Repository
public interface ConfirmationRepository extends JpaRepository<ConfirmationSession, String> {

    // 按 sessionId 主键取会话，不校验用户，内部服务/调度用
    Optional<ConfirmationSession> findBySessionId(String sessionId);

    // 按 sessionId + userId 取会话，校验会话归属是否为当前用户，对外 HTTP 接口推荐用
    Optional<ConfirmationSession> findBySessionIdAndUserId(String sessionId, Long userId);

    // 查某用户指定生命周期状态（PENDING/CONFIRMED/CANCELLED/EXPIRED）的会话列表
    List<ConfirmationSession> findByUserIdAndStatus(Long userId, SessionStatus status);

    // 扫某状态 + expires_at < dateTime 的会话，清理已过期卡片
    List<ConfirmationSession> findByStatusAndExpiresAtBefore(SessionStatus status, LocalDateTime dateTime);

    // 查某用户全部会话（不管状态），会话历史页用
    List<ConfirmationSession> findByUserId(Long userId);

    // 按状态 + 过期时间批量删除会话，定时任务清 EXPIRED 会话用
    void deleteByStatusAndExpiresAtBefore(SessionStatus status, LocalDateTime dateTime);

    // 查找指定提醒的待确认执行阶段会话，用于清理旧会话
    List<ConfirmationSession> findByReminderIdAndStageAndStatus(Long reminderId, String stage, SessionStatus status);

    // 批量过期指定提醒的旧执行会话
    @Modifying
    @Query("UPDATE ConfirmationSession s SET s.status = 'EXPIRED' WHERE s.reminderId = :reminderId AND s.stage = 'REMINDER_EXECUTE' AND s.status = 'PENDING'")
    void expireOldExecuteSessions(Long reminderId);
}
