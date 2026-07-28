package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.event.EventPublisher;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.Action;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.ConfirmationData;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ConfirmationRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
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
    private final ObjectMapper objectMapper;
    private final WebSocketPushService webSocketPushService;
    private final EventPublisher eventPublisher;
    private final WeatherService weatherService;
    private final LLMService llmService;
    private final ConflictDetectionService conflictDetectionService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private RecurringReminderService recurringReminderService;

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
            parsedTime = parseTimeFlexible(remindTimeStr);
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
        return confirm(sessionId, userId, List.of());
    }

    @Transactional
    public Map<String, Object> confirm(String sessionId, Long userId, List<Long> conflictIdsToOverwrite) {
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

        SessionStatus status = session.getStatus();
        if (status == SessionStatus.CONFIRMED) {
            throw new IllegalArgumentException("该会话已确认，不能重复确认");
        }
        if (status == SessionStatus.CANCELLED) {
            throw new IllegalArgumentException("该会话已取消，不能确认");
        }
        if (status == SessionStatus.EXPIRED) {
            throw new IllegalArgumentException("该会话已过期，不能确认");
        }

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);
            throw new IllegalArgumentException("该会话已过期，请重新创建");
        }

        Map<String, Object> parsedData = parseParsedData(session.getParsedData());

        // 周期提醒：走周期创建流程
        if (Boolean.TRUE.equals(parsedData.get("isRecurring"))) {
            return confirmRecurring(session, userId, parsedData);
        }

        List<String> overwrittenTitles = deleteConflicts(userId, conflictIdsToOverwrite);

        Reminder reminder = createReminder(userId, parsedData, ConfirmStatus.CONFIRMED);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        session.setReminderId(reminder.getId());
        confirmationRepository.save(session);

        String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
        String successMessage = buildConfirmSuccessMessage(reminder, overwrittenTitle);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", successMessage);
        result.put("reminderId", reminder.getId());
        result.put("title", reminder.getTitle());
        result.put("remindTime", reminder.getRemindTime().format(FORMATTER));
        if (!overwrittenTitles.isEmpty()) {
            result.put("overwrittenTitle", overwrittenTitle);
        }

        log.info("确认创建: sessionId={}, userId={}, reminderId={}, deletedConflicts={}", sessionId, userId, reminder.getId(), overwrittenTitles.size());

        try {
            eventPublisher.publishReminderConfirmed(userId, reminder.getId(), reminder.getTitle());
            log.info("发布确认事件: sessionId={}, userId={}, reminderId={}", sessionId, userId, reminder.getId());
        } catch (Exception e) {
            log.error("发布确认事件失败: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage(), e);
        }

        return result;
    }

    private Map<String, Object> confirmRecurring(ConfirmationSession session, Long userId, Map<String, Object> parsedData) {
        String title = (String) parsedData.get("title");
        // 使用纯时间 HH:mm:ss，如果没有则从完整时间中提取
        String remindTime = (String) parsedData.get("recurringRemindTime");
        if (remindTime == null) {
            String fullTime = (String) parsedData.get("remindTime");
            if (fullTime != null && fullTime.length() > 10) {
                remindTime = fullTime.substring(11);  // 提取时间部分
            }
        }
        String startDate = (String) parsedData.get("startDate");
        String endDate = (String) parsedData.get("endDate");
        String repeatType = (String) parsedData.get("repeatType");
        String location = (String) parsedData.get("location");
        String priority = (String) parsedData.getOrDefault("priority", "NORMAL");

        Map<String, Object> createResult = recurringReminderService.generateInstancesFromConfirm(
                userId, title, remindTime, startDate, endDate, repeatType, location, priority);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", createResult.get("success"));
        result.put("message", createResult.get("message"));
        result.put("isRecurring", true);
        if (createResult.get("recurringId") != null) {
            result.put("recurringId", createResult.get("recurringId"));
        }
        if (createResult.get("totalDays") != null) {
            result.put("totalDays", createResult.get("totalDays"));
        }
        if (createResult.get("successCount") != null) {
            result.put("successCount", createResult.get("successCount"));
        }
        if (createResult.get("conflictCount") != null) {
            result.put("conflictCount", createResult.get("conflictCount"));
        }

        log.info("周期提醒确认创建: sessionId={}, userId={}, recurringId={}", session.getSessionId(), userId, createResult.get("recurringId"));

        try {
            eventPublisher.publishReminderConfirmed(userId, null, title);
        } catch (Exception e) {
            log.error("发布确认事件失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 周期提醒-直接创建全部（不覆盖冲突，冲突日多条并存）
     */
    @Transactional
    public Map<String, Object> confirmRecurringDirect(String sessionId, Long userId) {
        ConfirmationSession session = findAndValidateSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        Map<String, Object> createResult = callRecurringGenerate(parsedData, userId, "direct");

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>(createResult);
        pushCardConfirmed(userId, sessionId, (String) parsedData.get("title"), null);
        log.info("周期提醒直接创建: sessionId={}, userId={}", sessionId, userId);
        return result;
    }

    /**
     * 周期提醒-创建无冲突部分（跳过冲突日）
     */
    @Transactional
    public Map<String, Object> confirmRecurringSkip(String sessionId, Long userId) {
        ConfirmationSession session = findAndValidateSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        Map<String, Object> createResult = callRecurringGenerate(parsedData, userId, "skip");

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>(createResult);
        pushCardConfirmed(userId, sessionId, (String) parsedData.get("title"), null);
        log.info("周期提醒跳过冲突创建: sessionId={}, userId={}", sessionId, userId);
        return result;
    }

    /**
     * 周期提醒-全部覆盖（删除冲突提醒，创建全部）
     */
    @Transactional
    public Map<String, Object> confirmRecurringOverwriteAll(String sessionId, Long userId) {
        ConfirmationSession session = findAndValidateSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        Map<String, Object> createResult = callRecurringGenerate(parsedData, userId, "overwrite");

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>(createResult);
        pushCardConfirmed(userId, sessionId, (String) parsedData.get("title"), null);
        log.info("周期提醒全部覆盖创建: sessionId={}, userId={}", sessionId, userId);
        return result;
    }

    private ConfirmationSession findAndValidateSession(String sessionId, Long userId) {
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.CONFIRMED) {
            throw new IllegalArgumentException("该会话已确认，不能重复操作");
        }
        if (status == SessionStatus.CANCELLED) {
            throw new IllegalArgumentException("该会话已取消");
        }
        if (status == SessionStatus.EXPIRED) {
            throw new IllegalArgumentException("该会话已过期，请重新创建");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);
            throw new IllegalArgumentException("该会话已过期，请重新创建");
        }
        return session;
    }

    private Map<String, Object> callRecurringGenerate(Map<String, Object> parsedData, Long userId, String mode) {
        String title = (String) parsedData.get("title");
        String remindTime = (String) parsedData.get("recurringRemindTime");
        if (remindTime == null) {
            String fullTime = (String) parsedData.get("remindTime");
            if (fullTime != null && fullTime.length() > 10) {
                remindTime = fullTime.substring(11);
            }
        }
        String startDate = (String) parsedData.get("startDate");
        String endDate = (String) parsedData.get("endDate");
        String repeatType = (String) parsedData.get("repeatType");
        String location = (String) parsedData.get("location");
        String priority = (String) parsedData.getOrDefault("priority", "NORMAL");

        return switch (mode) {
            case "direct" -> recurringReminderService.generateAll(
                    userId, title, remindTime, startDate, endDate, repeatType, location, priority);
            case "skip" -> recurringReminderService.generateWithSkip(
                    userId, title, remindTime, startDate, endDate, repeatType, location, priority);
            case "overwrite" -> recurringReminderService.generateWithOverwrite(
                    userId, title, remindTime, startDate, endDate, repeatType, location, priority);
            default -> throw new IllegalArgumentException("未知的创建模式: " + mode);
        };
    }

    @Transactional
    public Map<String, Object> cancel(String sessionId, Long userId) {
        // 1. 先查找会话（不检查过期时间）
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

        // 2. 检查会话状态
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.CONFIRMED) {
            throw new IllegalArgumentException("该会话已确认，不能取消");
        }
        if (status == SessionStatus.CANCELLED) {
            throw new IllegalArgumentException("该会话已取消，不能重复取消");
        }
        if (status == SessionStatus.EXPIRED) {
            throw new IllegalArgumentException("该会话已过期，不能取消");
        }

        // 3. 检查是否过期
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);
            throw new IllegalArgumentException("该会话已过期，请重新创建");
        }

        // 4. 所有检查通过，正常执行取消逻辑
        session.setStatus(SessionStatus.CANCELLED);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "❌ 已取消创建提醒");

        log.info("取消确认会话: sessionId={}, userId={}", sessionId, userId);

        // 发布提醒取消事件（通知Agent）
        try {
            eventPublisher.publishReminderCancelled(userId, session.getReminderId(), session.getParsedTitle());
            log.info("发布取消事件: sessionId={}, userId={}", sessionId, userId);
        } catch (Exception e) {
            log.error("发布取消事件失败: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage(), e);
        }

        return result;
    }

    /**
     * 覆盖创建：删除冲突提醒，然后创建新提醒
     * <p>
     * 用户在确认卡片上点击"覆盖创建"按钮时调用。
     * 删除冲突的旧提醒，然后确认当前会话的新提醒。
     * </p>
     *
     * @param sessionId 确认会话ID
     * @param userId    用户ID
     * @return 处理结果
     */
    @Transactional
    public Map<String, Object> handleOverwrite(String sessionId, Long userId) {
        log.info("=== handleOverwrite (全量) 被调用 === sessionId={}, userId={}", sessionId, userId);

        try {
            Map<String, Object> result = new HashMap<>();

            ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

            SessionStatus status = session.getStatus();
            if (status == SessionStatus.CONFIRMED) {
                throw new IllegalArgumentException("该会话已确认，不能重复操作");
            }
            if (status == SessionStatus.CANCELLED) {
                throw new IllegalArgumentException("该会话已取消");
            }
            if (status == SessionStatus.EXPIRED) {
                throw new IllegalArgumentException("该会话已过期，请重新创建");
            }

            if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
                session.setStatus(SessionStatus.EXPIRED);
                confirmationRepository.save(session);
                throw new IllegalArgumentException("该会话已过期，请重新创建");
            }

            Map<String, Object> parsedData = parseParsedData(session.getParsedData());

            List<Long> allConflictIds = getAllConflictIds(parsedData);
            List<String> overwrittenTitles = deleteConflicts(userId, allConflictIds);

            Reminder reminder = createReminder(userId, parsedData, ConfirmStatus.CONFIRMED);

            session.setStatus(SessionStatus.CONFIRMED);
            session.setStage("REMINDER_EXECUTE");
            session.setReminderId(reminder.getId());
            confirmationRepository.save(session);

            String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
            String successMessage = buildConfirmSuccessMessage(reminder, overwrittenTitle);

            result.put("success", true);
            result.put("message", successMessage);
            result.put("reminderId", reminder.getId());
            if (!overwrittenTitles.isEmpty()) {
                result.put("overwrittenTitle", overwrittenTitle);
            }

            log.info("全量覆盖成功: sessionId={}, reminderId={}, deletedConflicts={}", sessionId, reminder.getId(), overwrittenTitles.size());

            try {
                eventPublisher.publishReminderConfirmed(userId, reminder.getId(), reminder.getTitle());
            } catch (Exception e) {
                log.error("发布确认事件失败: {}", e.getMessage(), e);
            }

            return result;
        } catch (Exception e) {
            log.error("handleOverwrite 执行失败: sessionId={}, userId={}", sessionId, userId, e);
            throw e;
        }
    }

    @Transactional
    public Map<String, Object> handleOverwrite(String sessionId, Long userId, List<Long> conflictIdsToOverwrite) {
        log.info("=== handleOverwrite (指定ID) 被调用 === sessionId={}, userId={}, conflictIds={}", sessionId, userId, conflictIdsToOverwrite);

        try {
            Map<String, Object> result = new HashMap<>();

            ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

            SessionStatus status = session.getStatus();
            if (status == SessionStatus.CONFIRMED) {
                throw new IllegalArgumentException("该会话已确认，不能重复操作");
            }
            if (status == SessionStatus.CANCELLED) {
                throw new IllegalArgumentException("该会话已取消");
            }
            if (status == SessionStatus.EXPIRED) {
                throw new IllegalArgumentException("该会话已过期，请重新创建");
            }

            if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
                session.setStatus(SessionStatus.EXPIRED);
                confirmationRepository.save(session);
                throw new IllegalArgumentException("该会话已过期，请重新创建");
            }

            Map<String, Object> parsedData = parseParsedData(session.getParsedData());

            List<String> overwrittenTitles = deleteConflicts(userId, conflictIdsToOverwrite);

            Reminder reminder = createReminder(userId, parsedData, ConfirmStatus.CONFIRMED);

            session.setStatus(SessionStatus.CONFIRMED);
            session.setStage("REMINDER_EXECUTE");
            session.setReminderId(reminder.getId());
            confirmationRepository.save(session);

            String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
            String successMessage = buildConfirmSuccessMessage(reminder, overwrittenTitle);

            result.put("success", true);
            result.put("message", successMessage);
            result.put("reminderId", reminder.getId());
            if (!overwrittenTitles.isEmpty()) {
                result.put("overwrittenTitle", overwrittenTitle);
            }

            log.info("指定ID覆盖成功: sessionId={}, reminderId={}, deletedConflicts={}", sessionId, reminder.getId(), overwrittenTitles.size());

            try {
                eventPublisher.publishReminderConfirmed(userId, reminder.getId(), reminder.getTitle());
            } catch (Exception e) {
                log.error("发布确认事件失败: {}", e.getMessage(), e);
            }

            return result;
        } catch (Exception e) {
            log.error("handleOverwrite(指定ID) 执行失败: sessionId={}, userId={}", sessionId, userId, e);
            throw e;
        }
    }

    private List<Long> getAllConflictIds(Map<String, Object> parsedData) {
        List<Long> ids = new java.util.ArrayList<>();

        if (parsedData.get("conflictId") != null) {
            ids.add(Long.valueOf(String.valueOf(parsedData.get("conflictId"))));
        }

        Object conflictListObj = parsedData.get("conflictList");
        if (conflictListObj != null) {
            try {
                List<?> conflicts;
                if (conflictListObj instanceof String) {
                    conflicts = objectMapper.readValue((String) conflictListObj, List.class);
                } else if (conflictListObj instanceof List) {
                    conflicts = (List<?>) conflictListObj;
                } else {
                    conflicts = List.of();
                }
                for (Object c : conflicts) {
                    if (c instanceof Map) {
                        Object id = ((Map<?, ?>) c).get("id");
                        if (id != null) {
                            ids.add(Long.valueOf(String.valueOf(id)));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析冲突列表失败: {}", e.getMessage());
            }
        }

        return ids.stream().distinct().toList();
    }

    private List<String> deleteConflicts(Long userId, List<Long> conflictIds) {
        List<String> deletedTitles = new java.util.ArrayList<>();
        if (conflictIds == null || conflictIds.isEmpty()) {
            return deletedTitles;
        }

        for (Long conflictId : conflictIds) {
            Reminder conflictReminder = reminderRepository.findById(conflictId)
                    .filter(r -> r.getUserId().equals(userId))
                    .orElse(null);
            if (conflictReminder != null) {
                deletedTitles.add(conflictReminder.getTitle());
                conflictReminder.setStatus(ReminderStatus.CANCELLED);
                reminderRepository.save(conflictReminder);
                log.info("删除冲突提醒: reminderId={}, title={}", conflictId, conflictReminder.getTitle());
            }
        }

        return deletedTitles;
    }

    /**
     * 处理冲突选择
     * <p>
     * 用户在冲突卡片上选择处理方式后调用此方法
     * </p>
     *
     * @param userId 用户ID
     * @param action 操作类型：overwrite/cancel/modify
     * @param title 新提醒标题
     * @param remindTime 新提醒时间
     * @param location 地点（可选）
     * @param priority 优先级（可选）
     * @param conflictReminderId 冲突的提醒ID（overwrite时使用）
     * @param newTime 修改后的新时间（modify时使用）
     * @return 处理结果
     */
    @Transactional
    public Map<String, Object> handleConflict(Long userId, String action, String title, 
            String remindTime, String location, String priority, Long conflictReminderId, String newTime) {
        
        log.info("处理冲突选择: userId={}, action={}, title={}, remindTime={}", userId, action, title, remindTime);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            switch (action) {
                case "overwrite":
                    // 覆盖：删除冲突提醒，创建新提醒
                    result = handleOverwrite(userId, title, remindTime, location, priority, conflictReminderId);
                    break;
                    
                case "cancel":
                    // 取消：不创建新提醒
                    result.put("success", true);
                    result.put("message", "已取消创建提醒");
                    log.info("冲突处理-取消: userId={}, title={}", userId, title);
                    break;
                    
                case "modify":
                    // 修改时间：使用新时间重新检测并创建确认会话
                    result = handleModify(userId, title, newTime, location, priority);
                    break;
                    
                default:
                    result.put("success", false);
                    result.put("message", "无效的操作类型: " + action);
                    break;
            }
        } catch (Exception e) {
            log.error("处理冲突失败: userId={}, action={}, error={}", userId, action, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "处理冲突失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 处理覆盖操作：删除冲突提醒，创建新提醒
     */
    private Map<String, Object> handleOverwrite(Long userId, String title, String remindTime,
            String location, String priority, Long conflictReminderId) {
        
        Map<String, Object> result = new HashMap<>();
        
        // 删除冲突的提醒
        if (conflictReminderId != null) {
            Reminder conflictReminder = reminderRepository.findById(conflictReminderId)
                    .filter(r -> r.getUserId().equals(userId))
                    .orElse(null);
            if (conflictReminder != null) {
                conflictReminder.setStatus(ReminderStatus.CANCELLED);
                reminderRepository.save(conflictReminder);
                log.info("冲突处理-覆盖：删除冲突提醒 reminderId={}", conflictReminderId);
            }
        }
        
        // 解析时间
        LocalDateTime parsedTime = parseTimeFlexible(remindTime);
        
        // 构建解析数据
        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", title);
        parsedData.put("remindTime", remindTime);
        parsedData.put("location", location);
        parsedData.put("priority", priority != null ? priority : "NORMAL");
        parsedData.put("advanceMinutes", 10);
        parsedData.put("source", "USER_INPUT");
        parsedData.put("intentType", "REMINDER");
        
        // 创建确认会话
        ConfirmationCardResponse card = createSession(userId, title, parsedData);
        
        result.put("success", true);
        result.put("message", "已覆盖原有提醒，请确认新提醒");
        result.put("sessionId", card.getData().getSessionId());
        result.put("sessionCreated", true);
        
        log.info("冲突处理-覆盖成功: userId={}, title={}", userId, title);
        return result;
    }

    /**
     * 处理修改时间操作：使用新时间重新检测
     */
    private Map<String, Object> handleModify(Long userId, String title, String newTime,
            String location, String priority) {
        
        Map<String, Object> result = new HashMap<>();
        
        if (newTime == null || newTime.isBlank()) {
            result.put("success", false);
            result.put("message", "修改时间不能为空");
            return result;
        }
        
        LocalDateTime parsedTime = parseTimeFlexible(newTime);
        
        // 重新检测冲突
        var conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, null);
        if (conflictResponse.isHasConflict()) {
            // 仍然有冲突
            String description = conflictDetectionService.getConflictDescription(
                    conflictResponse.getConflicts(), parsedTime);
            
            result.put("success", false);
            result.put("hasConflict", true);
            result.put("conflicts", conflictResponse.getConflicts());
            result.put("suggestedTime", conflictResponse.getSuggestedTime() != null 
                    ? conflictResponse.getSuggestedTime().format(FORMATTER) : null);
            result.put("message", description);
            result.put("title", title);
            result.put("remindTime", newTime);
            
            log.info("冲突处理-修改后仍有冲突: userId={}, title={}, newTime={}", userId, title, newTime);
            return result;
        }
        
        // 没有冲突，创建确认会话
        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", title);
        parsedData.put("remindTime", newTime);
        parsedData.put("location", location);
        parsedData.put("priority", priority != null ? priority : "NORMAL");
        parsedData.put("advanceMinutes", 10);
        parsedData.put("source", "USER_INPUT");
        parsedData.put("intentType", "REMINDER");
        
        ConfirmationCardResponse card = createSession(userId, title, parsedData);
        
        result.put("success", true);
        result.put("message", "已修改时间，请确认提醒");
        result.put("sessionId", card.getData().getSessionId());
        result.put("sessionCreated", true);
        result.put("hasConflict", false);
        
        log.info("冲突处理-修改时间成功: userId={}, title={}, newTime={}", userId, title, newTime);
        return result;
    }

    @Transactional
    public ConfirmationCardResponse reparse(String sessionId, Long userId, String newInput, Map<String, Object> newParsedData) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        // 冲突检测（修改时间后重新检测）
        String remindTimeStr = (String) newParsedData.get("remindTime");
        if (remindTimeStr != null) {
            try {
                LocalDateTime parsedTime = LocalDateTime.parse(remindTimeStr, FORMATTER);
                ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, null);
                if (conflictResponse.isHasConflict()) {
                    String description = conflictDetectionService.getConflictDescription(
                            conflictResponse.getConflicts(), parsedTime);
                    newParsedData.put("hasConflict", true);
                    newParsedData.put("conflictMessage", description);
                    newParsedData.put("conflictList", conflictResponse.getConflicts());
                    newParsedData.put("suggestedTime", conflictResponse.getSuggestedTime() != null 
                            ? conflictResponse.getSuggestedTime().format(FORMATTER) : null);
                    if (conflictResponse.getConflicts() != null && !conflictResponse.getConflicts().isEmpty()) {
                        newParsedData.put("conflictId", conflictResponse.getConflicts().get(0).getId());
                    }
                } else {
                    newParsedData.put("hasConflict", false);
                    newParsedData.remove("conflictMessage");
                    newParsedData.remove("conflictList");
                    newParsedData.remove("suggestedTime");
                    newParsedData.remove("conflictId");
                }
            } catch (Exception e) {
                log.warn("reparse冲突检测失败: {}", e.getMessage());
                newParsedData.put("hasConflict", false);
            }
        }

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(newParsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        LocalDateTime parsedTime = null;
        if (remindTimeStr != null) {
            parsedTime = parseTimeFlexible(remindTimeStr);
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
                    Map<String, Object> parsedData = parseParsedData(session.getParsedData());

                    // 周期提醒：走周期创建流程
                    if (Boolean.TRUE.equals(parsedData.get("isRecurring"))) {
                        handleExpiredRecurringSession(session, parsedData);
                    } else {
                        // 使用 createReminderFromSessionWithoutPush，不推送 card_confirmed（超时自动确认场景）
                        Reminder reminder = createReminderFromSessionWithoutPush(session);

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
                    }
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

    /**
     * 处理周期提醒的超时确认
     */
    private void handleExpiredRecurringSession(ConfirmationSession session, Map<String, Object> parsedData) {
        String title = (String) parsedData.get("title");
        String remindTime = (String) parsedData.get("recurringRemindTime");
        if (remindTime == null) {
            String fullTime = (String) parsedData.get("remindTime");
            if (fullTime != null && fullTime.length() > 10) {
                remindTime = fullTime.substring(11);
            }
        }
        String startDate = (String) parsedData.get("startDate");
        String endDate = (String) parsedData.get("endDate");
        String repeatType = (String) parsedData.get("repeatType");
        String location = (String) parsedData.get("location");
        String priority = (String) parsedData.getOrDefault("priority", "NORMAL");

        Map<String, Object> createResult = recurringReminderService.generateInstancesFromConfirm(
                session.getUserId(), title, remindTime, startDate, endDate, repeatType, location, priority);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", createResult.get("success"));
        result.put("message", "⏰ 15秒未确认，已自动创建周期提醒：" + createResult.get("message"));
        result.put("sessionId", session.getSessionId());
        result.put("title", title);
        result.put("isRecurring", true);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("repeatType", repeatType);
        if (createResult.get("recurringId") != null) {
            result.put("recurringId", createResult.get("recurringId"));
        }
        if (createResult.get("totalDays") != null) {
            result.put("totalDays", createResult.get("totalDays"));
        }

        pushUserMessage(session.getUserId(), "timeout_auto_create", result);
        log.info("周期提醒超时自动确认: sessionId={}, userId={}, recurringId={}",
                session.getSessionId(), session.getUserId(), createResult.get("recurringId"));
    }

    /**
     * 灵活解析时间字符串，支持多种格式
     *
     * @param timeStr 时间字符串
     * @return 解析后的 LocalDateTime
     * @throws DateTimeParseException 如果所有格式都无法解析
     */
    private LocalDateTime parseTimeFlexible(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return null;
        }

        List<DateTimeFormatter> formatters = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        );

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(timeStr, formatter);
            } catch (DateTimeParseException e) {
                continue;
            }
        }

        throw new DateTimeParseException(
            "无法解析时间字符串: " + timeStr + "，支持的格式包括: yyyy-MM-dd HH:mm:ss, yyyy-MM-ddTHH:mm:ss",
            timeStr,
            0
        );
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
        reminder.setRemindTime(parseTimeFlexible((String) parsedData.get("remindTime")));
        reminder.setPriority(Priority.valueOf(
                ((String) parsedData.getOrDefault("priority", "NORMAL")).toUpperCase()));
        reminder.setLocation((String) parsedData.get("location"));
        reminder.setAdvanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10));
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setConfirmStatus(confirmStatus);
        reminder.setExecutionStatus(ExecutionStatus.PENDING);
        reminder.setSource(ReminderSource.USER_INPUT);
        reminder.setTimezone("Asia/Shanghai");

        return reminderRepository.save(reminder);
    }

    private ConfirmationCardResponse buildConfirmationCard(ConfirmationSession session, Map<String, Object> parsedData) {
        boolean hasConflict = Boolean.TRUE.equals(parsedData.get("hasConflict"));
        boolean isRecurring = Boolean.TRUE.equals(parsedData.get("isRecurring"));

        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .title((String) parsedData.get("title"))
                .remindTime((String) parsedData.get("remindTime"))
                .priority((String) parsedData.getOrDefault("priority", "NORMAL"))
                .location((String) parsedData.get("location"))
                .advanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10))
                .confidence((Double) parsedData.getOrDefault("confidence", 0.95))
                .hasConflict(hasConflict)
                .conflictMessage((String) parsedData.get("conflictMessage"))
                .conflictId(parsedData.get("conflictId") != null ? Long.valueOf(String.valueOf(parsedData.get("conflictId"))) : null)
                .suggestedTime((String) parsedData.get("suggestedTime"))
                .build();

        // 周期提醒：显示日期范围和总天数
        if (isRecurring) {
            StringBuilder titleBuilder = new StringBuilder();
            titleBuilder.append("📅 周期提醒：").append(parsedData.get("title"));
            String repeatType = (String) parsedData.getOrDefault("repeatType", "DAILY");
            String repeatLabel = switch (repeatType) {
                case "WEEKLY" -> "每周";
                case "MONTHLY" -> "每月";
                default -> "每天";
            };
            titleBuilder.append("（").append(repeatLabel).append("）");
            data.setTitle(titleBuilder.toString());

            // 提取纯时间部分用于显示
            String recurringRemindTime = (String) parsedData.get("recurringRemindTime");
            if (recurringRemindTime == null) {
                String fullTime = (String) parsedData.get("remindTime");
                if (fullTime != null && fullTime.length() > 10) {
                    recurringRemindTime = fullTime.substring(11);  // 提取 HH:mm:ss
                } else {
                    recurringRemindTime = fullTime;
                }
            }
            // 显示为 "每天 19:00 | 2026-08-03 至 2026-08-09 共7天"
            StringBuilder timeBuilder = new StringBuilder();
            if (recurringRemindTime != null) {
                // 截取 HH:mm 部分
                String displayTime = recurringRemindTime.length() >= 5 ? recurringRemindTime.substring(0, 5) : recurringRemindTime;
                timeBuilder.append(repeatLabel).append(" ").append(displayTime);
            }
            timeBuilder.append(" | ").append(parsedData.get("startDate"))
                    .append(" 至 ").append(parsedData.get("endDate"));
            Integer totalDays = (Integer) parsedData.get("totalDays");
            if (totalDays != null) {
                timeBuilder.append(" 共").append(totalDays).append("天");
            }
            data.setRemindTime(timeBuilder.toString());
        }

        // 冲突列表转为 JSON 字符串
        if (parsedData.get("conflictList") != null) {
            try {
                data.setConflictList(objectMapper.writeValueAsString(parsedData.get("conflictList")));
            } catch (JsonProcessingException e) {
                data.setConflictList("[]");
            }
        }

        List<Action> actions;
        String message;
        if (isRecurring && hasConflict) {
            // 周期提醒 + 有冲突：三个创建选项 + 修改
            String conflictMsg = (String) parsedData.get("conflictMessage");
            message = conflictMsg != null ? "⚠️ " + conflictMsg + "，请选择创建方式：" : "⚠️ 部分日期存在时间冲突，请选择创建方式：";
            actions = Arrays.asList(
                    Action.builder().id("confirm_direct").label("📝 直接创建").style("primary").build(),
                    Action.builder().id("confirm_skip").label("✅ 创建无冲突部分").style("success").build(),
                    Action.builder().id("overwrite_all").label("📝 全部覆盖").style("warning").build(),
                    Action.builder().id("edit").label("✏️ 修改").style("warning").build()
            );
        } else if (isRecurring) {
            String repeatType = (String) parsedData.getOrDefault("repeatType", "DAILY");
            String repeatLabel = switch (repeatType) {
                case "WEEKLY" -> "每周";
                case "MONTHLY" -> "每月";
                default -> "每天";
            };
            Integer totalDays = (Integer) parsedData.get("totalDays");
            message = String.format("📅 我将为你创建「%s」的%s提醒%s，请确认：",
                    parsedData.get("title"),
                    repeatLabel,
                    totalDays != null ? String.format("（共%d天）", totalDays) : "");
            actions = Arrays.asList(
                    Action.builder().id("confirm").label("✅ 确认创建").style("primary").build(),
                    Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                    Action.builder().id("cancel").label("❌ 取消").style("danger").build()
            );
        } else if (hasConflict) {
            // 有冲突时：确认（主按钮）+ 覆盖 + 修改 + 取消
            message = "⚠️ " + parsedData.get("conflictMessage");
            actions = Arrays.asList(
                    Action.builder().id("confirm").label("✅ 确认创建").style("primary").build(),
                    Action.builder().id("overwrite").label("📝 覆盖").style("warning").build(),
                    Action.builder().id("edit").label("✏️ 修改时间").style("warning").build(),
                    Action.builder().id("cancel").label("❌ 取消").style("danger").build()
            );
        } else {
            message = "📋 我帮你解析了提醒信息，请确认：";
            actions = Arrays.asList(
                    Action.builder().id("confirm").label("✅ 确认创建").style("primary").build(),
                    Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                    Action.builder().id("cancel").label("❌ 取消").style("danger").build()
            );
        }

        return ConfirmationCardResponse.builder()
                .type("confirmation_card")
                .message(message)
                .data(data)
                .actions(actions)
                .timeout(TIMEOUT_SECONDS)
                .expiresAt(session.getExpiresAt().format(ISO_FORMATTER))
                .build();
    }

    private void pushConfirmationCard(Long userId, ConfirmationCardResponse card) {
        webSocketPushService.pushToUser(userId, "/queue/confirmations", card);
    }

    private void pushUserMessage(Long userId, String type, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.putAll(data);
        webSocketPushService.pushToUser(userId, "/queue/messages", message);
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
        pushExecuteCard(reminder, null);
    }

    @Transactional
    public void pushExecuteCard(Reminder reminder, String weatherSuggestion) {
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
        parsedData.put("weatherSuggestion", weatherSuggestion);

        ConfirmationCardResponse card = buildExecuteCard(session, parsedData);
        pushConfirmationCard(reminder.getUserId(), card);

        log.info("推送阶段二提醒执行卡片: sessionId={}, reminderId={}, userId={}, weatherSuggestion={}", 
                sessionId, reminder.getId(), reminder.getUserId(), weatherSuggestion);
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
            reminder.setStatus(ReminderStatus.SENT);
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);
            reminderRepository.save(reminder);

            result.put("success", true);
            result.put("message", "⏰ 已自动确认执行（3次稍后未响应）");
            log.info("阶段二自动确认执行(3次稍后): sessionId={}, reminderId={}", sessionId, reminder.getId());
        } else {
            result.put("success", true);
            result.put("message", "⏰ 已推迟，" + session.getIntervalMinutes() + "分钟后再次提醒");
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
        reminder.setStatus(ReminderStatus.SENT);
        session.setStatus(SessionStatus.CONFIRMED);

        reminderRepository.save(reminder);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "✅ 提醒已完成");

        log.info("阶段二确认完成: sessionId={}, reminderId={}", sessionId, reminder.getId());
        return result;
    }

    @Transactional
    public Map<String, Object> handleExecuteCancel(String sessionId, Long userId) {
        ConfirmationSession session = findExecuteSession(sessionId, userId);

        Reminder reminder = reminderRepository.findById(session.getReminderId())
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在"));

        reminder.setStatus(ReminderStatus.CANCELLED);
        reminder.setExecutionStatus(ExecutionStatus.CANCELLED);
        session.setStatus(SessionStatus.CANCELLED);

        reminderRepository.save(reminder);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "❌ 提醒已取消");

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
                .weatherSuggestion((String) parsedData.get("weatherSuggestion"))
                .build();

        String message = "🔔 叮！该执行提醒了：";
        String weatherSuggestion = (String) parsedData.get("weatherSuggestion");
        if (weatherSuggestion != null && !weatherSuggestion.isBlank()) {
            message = weatherSuggestion + "\n" + message;
        }

        List<Action> actions = Arrays.asList(
                Action.builder().id("confirm").label("✅ 知道了").style("primary").build(),
                Action.builder().id("later").label("⏰ 稍后提醒").style("warning").build(),
                Action.builder().id("cancel").label("❌ 取消").style("danger").build()
        );

        return ConfirmationCardResponse.builder()
                .type("execute_card")
                .message(message)
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
                    // 从 parsedData 中提取建议时间
                    Map<String, Object> parsedData = parseParsedData(session.getParsedData());
                    data.put("suggestedTime", parsedData.get("suggestedTime"));
                    data.put("hasConflict", parsedData.get("hasConflict"));
                    data.put("conflictMessage", parsedData.get("conflictMessage"));
                    data.put("conflictList", parsedData.get("conflictList"));
                    // 周期提醒字段
                    data.put("isRecurring", parsedData.get("isRecurring"));
                    data.put("startDate", parsedData.get("startDate"));
                    data.put("endDate", parsedData.get("endDate"));
                    data.put("repeatType", parsedData.get("repeatType"));
                    data.put("recurringRemindTime", parsedData.get("recurringRemindTime"));
                    return data;
                })
                .orElse(new HashMap<>());
    }

    private String buildConfirmSuccessMessage(Reminder reminder) {
        return buildConfirmSuccessMessage(reminder, null);
    }

    private String buildConfirmSuccessMessage(Reminder reminder, String overwrittenTitle) {
        String title = reminder.getTitle();
        String remindTime = reminder.getRemindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        if (overwrittenTitle != null && !overwrittenTitle.isBlank()) {
            return "✅ 已覆盖「" + overwrittenTitle + "」，新提醒\"" + title + "\"已创建\n" +
                   "⏰ 我将在 " + remindTime + " 提醒您";
        }
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
                        ReminderStatus.PENDING, 
                        ConfirmStatus.CONFIRMED, 
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
        targetReminder.setStatus(ReminderStatus.SENT);
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
        Reminder savedReminder = createReminderFromSessionWithoutPush(session);
        
        // 推送卡片确认消息，通知前端关闭卡片（用于手动确认场景）
        pushCardConfirmed(session.getUserId(), session.getSessionId(), 
                session.getParsedTitle(), session.getParsedTime());
        
        return savedReminder;
    }
    
    private Reminder createReminderFromSessionWithoutPush(ConfirmationSession session) {
        Reminder reminder = new Reminder();
        reminder.setUserId(session.getUserId());
        reminder.setTitle(session.getParsedTitle());
        reminder.setRemindTime(session.getParsedTime());
        
        String priorityStr = session.getParsedPriority();
        if (priorityStr == null || priorityStr.isBlank()) {
            priorityStr = "NORMAL";
        }
        try {
            reminder.setPriority(Priority.valueOf(priorityStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            log.warn("无效的优先级值: {}, 使用默认值 NORMAL", priorityStr);
            reminder.setPriority(Priority.NORMAL);
        }
        
        reminder.setLocation(session.getParsedLocation());
        reminder.setAdvanceMinutes(session.getParsedAdvanceMinutes() != null ? session.getParsedAdvanceMinutes() : 10);
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
        reminder.setSource(ReminderSource.USER_INPUT);

        Reminder savedReminder = reminderRepository.save(reminder);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        session.setReminderId(savedReminder.getId());
        confirmationRepository.save(session);

        log.info("从会话创建提醒(不推送): sessionId={}, userId={}, reminderId={}, title={}", 
                session.getSessionId(), session.getUserId(), savedReminder.getId(), session.getParsedTitle());
        
        return savedReminder;
    }

    private void pushCardConfirmed(Long userId, String sessionId, String title, LocalDateTime remindTime) {
        log.info("pushCardConfirmed 被调用: userId={}, sessionId={}", userId, sessionId);
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
        webSocketPushService.pushToUser(userId, "/queue/messages", data);
    }
}