package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.Action;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.ConfirmationData;
import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.repository.ConfirmationRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmationService {

    private final ConfirmationRepository confirmationRepository;
    private final ReminderRepository reminderRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int TIMEOUT_SECONDS = 15;

    @Transactional
    public ConfirmationCardResponse createSession(Long userId, String originalInput, Map<String, Object> parsedData) {
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(TIMEOUT_SECONDS);

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(parsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        LocalDateTime parsedTime = null;
        String remindTimeStr = (String) parsedData.get("remindTime");
        if (remindTimeStr != null) {
            parsedTime = LocalDateTime.parse(remindTimeStr, FORMATTER);
        }

        ConfirmationSession session = ConfirmationSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .originalInput(originalInput)
                .parsedData(parsedDataJson)
                .parsedTitle((String) parsedData.get("title"))
                .parsedTime(parsedTime)
                .parsedLocation((String) parsedData.get("location"))
                .parsedPriority((String) parsedData.getOrDefault("priority", "NORMAL"))
                .parsedAdvanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10))
                .stage("INFO_CONFIRM")
                .status(SessionStatus.PENDING)
                .expiresAt(expiresAt)
                .retryCount(0)
                .attemptCount(0)
                .maxAttempts(3)
                .intervalMinutes(10)
                .build();

        confirmationRepository.save(session);

        ConfirmationCardResponse card = buildConfirmationCard(session, parsedData);
        pushConfirmationCard(userId, card);

        log.info("创建阶段一确认会话: sessionId={}, userId={}, stage=INFO_CONFIRM, expiresAt={}", sessionId, userId, expiresAt);
        return card;
    }

    @Transactional
    public Map<String, Object> confirm(String sessionId, Long userId) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        Reminder reminder = createReminder(userId, parsedData, com.dormrepair.remindme.enums.ConfirmStatus.CONFIRMED);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        session.setReminderId(reminder.getId());
        confirmationRepository.save(session);

        String successMessage = buildConfirmSuccessMessage(reminder);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", successMessage);
        result.put("reminderId", reminder.getId());
        result.put("title", reminder.getTitle());
        result.put("remindTime", reminder.getRemindTime().format(FORMATTER));

        pushUserMessage(userId, "success", result);
        log.info("阶段一确认: sessionId={}, userId={}, reminderId={}", sessionId, userId, reminder.getId());

        return result;
    }

    @Transactional
    public Map<String, Object> cancel(String sessionId, Long userId) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        session.setStatus(SessionStatus.CANCELLED);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "❌ 已取消创建提醒");

        pushUserMessage(userId, "cancelled", result);
        log.info("取消确认会话: sessionId={}, userId={}", sessionId, userId);

        return result;
    }

    @Transactional
    public ConfirmationCardResponse reparse(String sessionId, Long userId, String newInput, Map<String, Object> newParsedData) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(newParsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        LocalDateTime parsedTime = null;
        String remindTimeStr = (String) newParsedData.get("remindTime");
        if (remindTimeStr != null) {
            parsedTime = LocalDateTime.parse(remindTimeStr, FORMATTER);
        }

        session.setOriginalInput(newInput);
        session.setParsedData(parsedDataJson);
        session.setParsedTitle((String) newParsedData.get("title"));
        session.setParsedTime(parsedTime);
        session.setParsedLocation((String) newParsedData.get("location"));
        session.setParsedPriority((String) newParsedData.getOrDefault("priority", "NORMAL"));
        session.setParsedAdvanceMinutes((Integer) newParsedData.getOrDefault("advanceMinutes", 10));
        session.setRetryCount(session.getRetryCount() + 1);
        session.setExpiresAt(LocalDateTime.now().plusSeconds(TIMEOUT_SECONDS));
        confirmationRepository.save(session);

        ConfirmationCardResponse card = buildConfirmationCard(session, newParsedData);
        pushConfirmationCard(userId, card);

        log.info("阶段一修改: sessionId={}, userId={}, retryCount={}", sessionId, userId, session.getRetryCount());
        return card;
    }

    @Scheduled(fixedRate = 15000)
    @Transactional
    public void cleanupExpiredSessions() {
        List<ConfirmationSession> expired = confirmationRepository.findByStatusAndExpiresAtBefore(
                SessionStatus.PENDING, LocalDateTime.now());

        for (ConfirmationSession session : expired) {
            if ("INFO_CONFIRM".equals(session.getStage())) {
                try {
                    // 使用 createReminderFromSession 触发 card_confirmed 推送
                    Reminder reminder = createReminderFromSession(session);

                    String autoMessage = buildAutoConfirmMessage(reminder);

                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("message", autoMessage);
                    result.put("reminderId", reminder.getId());
                    result.put("sessionId", session.getSessionId());
                    result.put("title", reminder.getTitle());
                    result.put("remindTime", reminder.getRemindTime().format(FORMATTER));

                    // 保留 timeout_auto_create 消息用于向后兼容
                    pushUserMessage(session.getUserId(), "timeout_auto_create", result);
                    log.info("阶段一超时自动确认: sessionId={}, userId={}, reminderId={}", 
                            session.getSessionId(), session.getUserId(), reminder.getId());
                } catch (Exception e) {
                    log.warn("阶段一超时自动确认失败: sessionId={}, error={}", 
                            session.getSessionId(), e.getMessage());
                    
                    session.setStatus(SessionStatus.EXPIRED);
                    confirmationRepository.save(session);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", false);
                    result.put("message", "⏰ 确认已超时，请重新输入");
                    pushUserMessage(session.getUserId(), "timeout", result);
                }
            } else {
                session.setStatus(SessionStatus.EXPIRED);
                confirmationRepository.save(session);
                
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "⏰ 会话已过期");
                pushUserMessage(session.getUserId(), "timeout", result);
                log.info("会话过期: sessionId={}, userId={}", session.getSessionId(), session.getUserId());
            }
        }
    }

    private ConfirmationSession findValidSession(String sessionId, Long userId) {
        return confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> s.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或已过期"));
    }

    private Reminder createReminder(Long userId, Map<String, Object> parsedData, com.dormrepair.remindme.enums.ConfirmStatus confirmStatus) {
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setTitle((String) parsedData.get("title"));
        reminder.setRemindTime(LocalDateTime.parse((String) parsedData.get("remindTime"), FORMATTER));
        reminder.setPriority(com.dormrepair.remindme.enums.Priority.valueOf(
                ((String) parsedData.getOrDefault("priority", "NORMAL")).toUpperCase()));
        reminder.setLocation((String) parsedData.get("location"));
        reminder.setAdvanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10));
        reminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.PENDING);
        reminder.setConfirmStatus(confirmStatus);
        reminder.setExecutionStatus(ExecutionStatus.PENDING);
        reminder.setSource(com.dormrepair.remindme.enums.ReminderSource.USER_INPUT);
        reminder.setTimezone("Asia/Shanghai");

        return reminderRepository.save(reminder);
    }

    private ConfirmationCardResponse buildConfirmationCard(ConfirmationSession session, Map<String, Object> parsedData) {
        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .title((String) parsedData.get("title"))
                .remindTime((String) parsedData.get("remindTime"))
                .priority((String) parsedData.getOrDefault("priority", "NORMAL"))
                .location((String) parsedData.get("location"))
                .advanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10))
                .confidence((Double) parsedData.getOrDefault("confidence", 0.95))
                .build();

        List<Action> actions = Arrays.asList(
                Action.builder().id("confirm").label("✅ 确认创建").style("primary").build(),
                Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                Action.builder().id("cancel").label("❌ 取消").style("danger").build()
        );

        return ConfirmationCardResponse.builder()
                .type("confirmation_card")
                .message("📋 我帮你解析了提醒信息，请确认：")
                .data(data)
                .actions(actions)
                .timeout(TIMEOUT_SECONDS)
                .expiresAt(session.getExpiresAt().format(ISO_FORMATTER))
                .build();
    }

    private void pushConfirmationCard(Long userId, ConfirmationCardResponse card) {
        messagingTemplate.convertAndSend("/topic/confirmations", card);
    }

    private void pushUserMessage(Long userId, String type, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.putAll(data);
        messagingTemplate.convertAndSend("/topic/messages", message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParsedData(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    public Optional<ConfirmationSession> findPendingSession(Long userId) {
        return confirmationRepository.findByUserIdAndStatus(userId, SessionStatus.PENDING)
                .stream()
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(LocalDateTime.now()))
                .findFirst();
    }

    @Transactional
    public void pushExecuteCard(Reminder reminder) {
        String sessionId = UUID.randomUUID().toString();

        ConfirmationSession session = ConfirmationSession.builder()
                .sessionId(sessionId)
                .userId(reminder.getUserId())
                .reminderId(reminder.getId())
                .parsedTitle(reminder.getTitle())
                .parsedTime(reminder.getRemindTime())
                .parsedLocation(reminder.getLocation())
                .parsedPriority(reminder.getPriority().name())
                .parsedAdvanceMinutes(reminder.getAdvanceMinutes())
                .stage("REMINDER_EXECUTE")
                .status(SessionStatus.PENDING)
                .attemptCount(reminder.getConfirmAttempts())
                .maxAttempts(3)
                .intervalMinutes(reminder.getAdvanceMinutes() > 0 ? reminder.getAdvanceMinutes() : 10)
                .build();

        confirmationRepository.save(session);

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", reminder.getTitle());
        parsedData.put("remindTime", reminder.getRemindTime().format(FORMATTER));
        parsedData.put("location", reminder.getLocation());
        parsedData.put("priority", reminder.getPriority().name());
        parsedData.put("advanceMinutes", reminder.getAdvanceMinutes());
        parsedData.put("attempt", reminder.getConfirmAttempts());
        parsedData.put("maxAttempts", 3);

        ConfirmationCardResponse card = buildExecuteCard(session, parsedData);
        pushConfirmationCard(reminder.getUserId(), card);

        log.info("推送阶段二提醒执行卡片: sessionId={}, reminderId={}, userId={}", 
                sessionId, reminder.getId(), reminder.getUserId());
    }

    @Transactional
    public Map<String, Object> handleExecuteLater(String sessionId, Long userId) {
        ConfirmationSession session = findExecuteSession(sessionId, userId);

        Reminder reminder = reminderRepository.findById(session.getReminderId())
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在"));

        int newAttemptCount = session.getAttemptCount() + 1;
        session.setAttemptCount(newAttemptCount);
        session.setExpiresAt(LocalDateTime.now().plusMinutes(session.getIntervalMinutes()));
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();

        if (newAttemptCount >= session.getMaxAttempts()) {
            reminder.setExecutionStatus(ExecutionStatus.DEFAULTED);
            reminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.SENT);
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);
            reminderRepository.save(reminder);

            result.put("success", true);
            result.put("message", "⏰ 已自动确认执行（3次稍后未响应）");
            pushUserMessage(userId, "execute_auto_confirm", result);
            log.info("阶段二自动确认执行(3次稍后): sessionId={}, reminderId={}", sessionId, reminder.getId());
        } else {
            result.put("success", true);
            result.put("message", "⏰ 已推迟，" + session.getIntervalMinutes() + "分钟后再次提醒");
            pushUserMessage(userId, "execute_later", result);
            log.info("阶段二稍后: sessionId={}, reminderId={}, attempt={}/{}", 
                    sessionId, reminder.getId(), newAttemptCount, session.getMaxAttempts());
        }

        return result;
    }

    @Transactional
    public Map<String, Object> handleExecuteConfirm(String sessionId, Long userId) {
        ConfirmationSession session = findExecuteSession(sessionId, userId);

        Reminder reminder = reminderRepository.findById(session.getReminderId())
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在"));

        reminder.setExecutionStatus(ExecutionStatus.CONFIRMED);
        reminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.SENT);
        session.setStatus(SessionStatus.CONFIRMED);

        reminderRepository.save(reminder);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "✅ 提醒已完成");
        pushUserMessage(userId, "execute_success", result);

        log.info("阶段二确认完成: sessionId={}, reminderId={}", sessionId, reminder.getId());
        return result;
    }

    @Transactional
    public Map<String, Object> handleExecuteCancel(String sessionId, Long userId) {
        ConfirmationSession session = findExecuteSession(sessionId, userId);

        Reminder reminder = reminderRepository.findById(session.getReminderId())
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在"));

        reminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.CANCELLED);
        reminder.setExecutionStatus(ExecutionStatus.CANCELLED);
        session.setStatus(SessionStatus.CANCELLED);

        reminderRepository.save(reminder);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "❌ 提醒已取消");
        pushUserMessage(userId, "execute_cancelled", result);

        log.info("阶段二取消: sessionId={}, reminderId={}", sessionId, reminder.getId());
        return result;
    }

    private ConfirmationSession findExecuteSession(String sessionId, Long userId) {
        return confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> "REMINDER_EXECUTE".equals(s.getStage()))
                .orElseThrow(() -> new IllegalArgumentException("执行会话不存在或已过期"));
    }

    private ConfirmationCardResponse buildExecuteCard(ConfirmationSession session, Map<String, Object> parsedData) {
        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .title((String) parsedData.get("title"))
                .remindTime((String) parsedData.get("remindTime"))
                .priority((String) parsedData.get("priority"))
                .location((String) parsedData.get("location"))
                .advanceMinutes((Integer) parsedData.get("advanceMinutes"))
                .build();

        List<Action> actions = Arrays.asList(
                Action.builder().id("confirm").label("✅ 知道了").style("primary").build(),
                Action.builder().id("later").label("⏰ 稍后提醒").style("warning").build(),
                Action.builder().id("cancel").label("❌ 取消").style("danger").build()
        );

        return ConfirmationCardResponse.builder()
                .type("execute_card")
                .message("🔔 叮！该执行提醒了：")
                .data(data)
                .actions(actions)
                .timeout(session.getIntervalMinutes() * 60)
                .expiresAt(session.getExpiresAt() != null ? session.getExpiresAt().format(ISO_FORMATTER) : null)
                .build();
    }

    public Map<String, Object> getSessionData(String sessionId) {
        return confirmationRepository.findBySessionId(sessionId)
                .map(session -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("sessionId", session.getSessionId());
                    data.put("title", session.getParsedTitle());
                    data.put("remindTime", session.getParsedTime() != null ? session.getParsedTime().format(FORMATTER) : null);
                    data.put("location", session.getParsedLocation());
                    data.put("priority", session.getParsedPriority());
                    data.put("advanceMinutes", session.getParsedAdvanceMinutes());
                    return data;
                })
                .orElse(new HashMap<>());
    }

    private String buildConfirmSuccessMessage(Reminder reminder) {
        String title = reminder.getTitle();
        String remindTime = reminder.getRemindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return "✅ 已创建提醒：\"" + title + "\"，" + remindTime + " 执行\n" +
               "⏰ 我将在 " + remindTime + " 提醒您" + title;
    }

    private String buildAutoConfirmMessage(Reminder reminder) {
        String title = reminder.getTitle();
        String remindTime = reminder.getRemindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return "⏳ 15秒未确认，已自动确认提醒：\"" + title + "\"，" + remindTime + " 执行\n" +
               "⏰ 我将在 " + remindTime + " 提醒您" + title;
    }

    public List<ConfirmationSession> getPendingSessions(Long userId) {
        return confirmationRepository.findByUserIdAndStatus(userId, SessionStatus.PENDING)
                .stream()
                .filter(s -> "INFO_CONFIRM".equals(s.getStage()))
                .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isAfter(LocalDateTime.now()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    public ConfirmationSession getLatestPendingSession(Long userId) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            return null;
        }
        return sessions.get(sessions.size() - 1);
    }

    @Transactional
    public Reminder confirmByTitle(Long userId, String keyword) {
        // 阶段一：查询待确认创建的会话
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        
        if (!sessions.isEmpty()) {
            ConfirmationSession targetSession;
            if (keyword == null || keyword.trim().isEmpty()) {
                targetSession = sessions.get(sessions.size() - 1);
            } else {
                final String searchKeyword = keyword.trim();
                targetSession = sessions.stream()
                        .filter(s -> s.getParsedTitle() != null && s.getParsedTitle().contains(searchKeyword))
                        .findFirst()
                        .orElse(null);
            }
            
            if (targetSession != null) {
                return createReminderFromSession(targetSession);
            }
        }

        // 阶段二：查询待执行确认的提醒
        List<Reminder> pendingReminders = reminderRepository
                .findByUserIdAndStatusAndConfirmStatusAndExecutionStatus(
                        userId, 
                        com.dormrepair.remindme.enums.ReminderStatus.PENDING, 
                        com.dormrepair.remindme.enums.ConfirmStatus.CONFIRMED, 
                        ExecutionStatus.PENDING);
        
        if (pendingReminders.isEmpty()) {
            throw new IllegalArgumentException("当前没有待确认的行程");
        }

        Reminder targetReminder;
        if (keyword == null || keyword.trim().isEmpty()) {
            targetReminder = pendingReminders.get(pendingReminders.size() - 1);
        } else {
            final String searchKeyword = keyword.trim();
            targetReminder = pendingReminders.stream()
                    .filter(r -> r.getTitle() != null && r.getTitle().contains(searchKeyword))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未找到匹配的行程：" + searchKeyword));
        }

        // 执行阶段二确认
        targetReminder.setExecutionStatus(ExecutionStatus.CONFIRMED);
        targetReminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.SENT);
        reminderRepository.save(targetReminder);

        log.info("阶段二确认执行: userId={}, reminderId={}, title={}", userId, targetReminder.getId(), targetReminder.getTitle());
        
        // 推送阶段二确认消息，通知前端关闭卡片
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", null);
        data.put("title", targetReminder.getTitle());
        data.put("remindTime", targetReminder.getRemindTime() != null ? 
                targetReminder.getRemindTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        pushUserMessage(userId, "card_confirmed", data);
        
        return targetReminder;
    }

    @Transactional
    public boolean cancelByTitle(Long userId, String keyword) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            return false;
        }

        ConfirmationSession targetSession;
        if (keyword == null || keyword.trim().isEmpty()) {
            targetSession = sessions.get(sessions.size() - 1);
        } else {
            final String searchKeyword = keyword.trim();
            targetSession = sessions.stream()
                    .filter(s -> s.getParsedTitle() != null && s.getParsedTitle().contains(searchKeyword))
                    .findFirst()
                    .orElse(null);
        }

        if (targetSession == null) {
            return false;
        }

        targetSession.setStatus(SessionStatus.CANCELLED);
        confirmationRepository.save(targetSession);
        log.info("取消行程: sessionId={}, userId={}, title={}", targetSession.getSessionId(), userId, targetSession.getParsedTitle());
        return true;
    }

    @Transactional
    public List<Reminder> confirmAll(Long userId) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("当前没有待确认的行程");
        }

        List<Reminder> reminders = sessions.stream()
                .map(this::createReminderFromSession)
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "✅ 已确认全部 " + reminders.size() + " 个行程");
        result.put("count", reminders.size());
        pushUserMessage(userId, "batch_confirm_result", result);

        return reminders;
    }

    @Transactional
    public int cancelAll(Long userId) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            return 0;
        }

        sessions.forEach(s -> s.setStatus(SessionStatus.CANCELLED));
        confirmationRepository.saveAll(sessions);
        log.info("一键取消所有行程: userId={}, count={}", userId, sessions.size());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "❌ 已取消全部 " + sessions.size() + " 个行程");
        result.put("count", sessions.size());
        pushUserMessage(userId, "batch_cancel_result", result);

        return sessions.size();
    }

    @Transactional
    public Reminder confirmSingle(Long userId, String sessionId) {
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> "INFO_CONFIRM".equals(s.getStage()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或已过期"));

        return createReminderFromSession(session);
    }

    @Transactional
    public boolean cancelSingle(Long userId, String sessionId) {
        return confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> "INFO_CONFIRM".equals(s.getStage()))
                .map(session -> {
                    session.setStatus(SessionStatus.CANCELLED);
                    confirmationRepository.save(session);
                    log.info("取消单个行程: sessionId={}, userId={}", sessionId, userId);
                    
                    // 推送取消成功消息，通知前端关闭卡片
                    Map<String, Object> data = new HashMap<>();
                    data.put("sessionId", session.getSessionId());
                    data.put("title", session.getParsedTitle());
                    data.put("remindTime", session.getParsedTime() != null ? 
                            session.getParsedTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
                    pushUserMessage(userId, "card_cancelled", data);
                    
                    return true;
                })
                .orElse(false);
    }

    private Reminder createReminderFromSession(ConfirmationSession session) {
        Reminder reminder = new Reminder();
        reminder.setUserId(session.getUserId());
        reminder.setTitle(session.getParsedTitle());
        reminder.setRemindTime(session.getParsedTime());
        
        String priorityStr = session.getParsedPriority();
        if (priorityStr == null) {
            priorityStr = "NORMAL";
        }
        reminder.setPriority(com.dormrepair.remindme.enums.Priority.valueOf(priorityStr.toUpperCase()));
        
        reminder.setLocation(session.getParsedLocation());
        reminder.setAdvanceMinutes(session.getParsedAdvanceMinutes() != null ? session.getParsedAdvanceMinutes() : 10);
        reminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.PENDING);
        reminder.setConfirmStatus(com.dormrepair.remindme.enums.ConfirmStatus.PENDING);
        reminder.setSource(com.dormrepair.remindme.enums.ReminderSource.USER_INPUT);
        reminder.setTimezone("Asia/Shanghai");

        Reminder savedReminder = reminderRepository.save(reminder);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        session.setReminderId(savedReminder.getId());
        confirmationRepository.save(session);

        log.info("从会话创建提醒: sessionId={}, userId={}, reminderId={}, title={}", 
                session.getSessionId(), session.getUserId(), savedReminder.getId(), session.getParsedTitle());
        
        // 推送卡片确认消息，通知前端关闭卡片
        pushCardConfirmed(session.getUserId(), session.getSessionId(), 
                session.getParsedTitle(), session.getParsedTime());
        
        return savedReminder;
    }

    private void pushCardConfirmed(Long userId, String sessionId, String title, LocalDateTime remindTime) {
        if (userId == null || sessionId == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("title", title);
        data.put("remindTime", remindTime != null ? remindTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        pushUserMessage(userId, "card_confirmed", data);
    }

    public void pushSingleConfirmCard(Long userId, ConfirmationSession session) {
        Map<String, Object> cardData = new HashMap<>();
        cardData.put("type", "single_confirm_card");
        cardData.put("sessionId", session.getSessionId());
        cardData.put("title", session.getParsedTitle());
        
        String timeStr;
        if (session.getParsedTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            if (session.getParsedTime().toLocalDate().equals(now.toLocalDate())) {
                timeStr = session.getParsedTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            } else {
                timeStr = session.getParsedTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        } else {
            timeStr = "未设置";
        }
        cardData.put("remindTime", timeStr);
        cardData.put("location", session.getParsedLocation());
        cardData.put("expiresAt", session.getExpiresAt() != null ? session.getExpiresAt().format(ISO_FORMATTER) : null);

        pushMessage(userId, cardData);
    }

    public void pushMultiConfirmCard(Long userId, List<ConfirmationSession> sessions) {
        Map<String, Object> cardData = new HashMap<>();
        cardData.put("type", "multi_confirm_card");
        cardData.put("count", sessions.size());
        
        List<Map<String, Object>> items = sessions.stream()
                .map(session -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("sessionId", session.getSessionId());
                    item.put("title", session.getParsedTitle());
                    
                    String timeStr;
                    if (session.getParsedTime() != null) {
                        timeStr = session.getParsedTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                    } else {
                        timeStr = "未设置";
                    }
                    item.put("remindTime", timeStr);
                    return item;
                })
                .toList();
        
        cardData.put("items", items);

        pushMessage(userId, cardData);
    }

    public void pushPendingSummary(Long userId) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("⏳ 您有 ").append(sessions.size()).append(" 个待确认行程：\n");
        
        int index = 1;
        for (ConfirmationSession session : sessions) {
            String timeStr = session.getParsedTime() != null 
                    ? session.getParsedTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) 
                    : "未设置";
            sb.append(index++).append(". ").append(session.getParsedTitle()).append(" - ").append(timeStr).append("\n");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("type", "pending_summary");
        data.put("message", sb.toString().trim());
        data.put("count", sessions.size());

        pushMessage(userId, data);
    }

    private void pushMessage(Long userId, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/topic/messages", json);
        } catch (JsonProcessingException e) {
            log.error("推送消息失败: userId={}, error={}", userId, e.getMessage());
        }
    }
}