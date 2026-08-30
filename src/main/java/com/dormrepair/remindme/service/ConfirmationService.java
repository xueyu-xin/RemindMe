package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentResponse;
import com.dormrepair.remindme.agent.AgentService;
import com.dormrepair.remindme.agent.event.EventPublisher;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.Action;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.ConfirmationData;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.MultiItem;
import com.dormrepair.remindme.dto.response.ConflictDetailResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.dto.response.KnowledgeResult;
import com.dormrepair.remindme.dto.response.WeatherNowResponse;
import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.exception.InvalidInputException;
import com.dormrepair.remindme.repository.ConfirmationRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// 两阶段确认会话服务：阶段一创建信息确认卡片，阶段二执行确认/稍后/取消，含超时自动确认与周期提醒分支
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
    private final RagService ragService;
    private final AgentService agentService;
    private final UserService userService;
    private final TransactionTemplate transactionTemplate;
    private final ReplyTemplateService replyTemplateService;

    // 周期提醒服务，用 @Lazy 避免循环依赖
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private RecurringReminderService recurringReminderService;

    // 统一时间格式与超时配置：确认会话默认 30 秒过期
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int TIMEOUT_SECONDS = 30;

    // 阶段一：创建信息确认会话，解析提醒字段后构建并推送确认卡片
    @Transactional(rollbackFor = Exception.class)
    public ConfirmationCardResponse createSession(Long userId, String originalInput, Map<String, Object> parsedData) {
        String sessionId = UUID.randomUUID().toString();

        // 根据会话类型设置不同的过期时间
        boolean isRecurring = Boolean.TRUE.equals(parsedData.get("isRecurring"));
        boolean hasConflict = Boolean.TRUE.equals(parsedData.get("hasConflict"));

        LocalDateTime expiresAt;
        if (isRecurring || hasConflict) {
            // 周期提醒或有冲突的会话：无超时，用户必须手动确认（与批量导入一致）
            expiresAt = null;
        } else {
            // 无冲突的单次提醒：保持30秒超时自动确认
            expiresAt = LocalDateTime.now().plusSeconds(TIMEOUT_SECONDS);
        }

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(parsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        LocalDateTime parsedTime = null;
        LocalDateTime parsedEndTime = null;
        String remindTimeStr = (String) parsedData.get("remindTime");
        if (remindTimeStr != null) {
            parsedTime = parseTimeFlexible(remindTimeStr);
        }
        String endTimeStr = (String) parsedData.get("endTime");
        if (endTimeStr != null) {
            parsedEndTime = parseTimeFlexible(endTimeStr);
        }

        int defaultAdvance = userService.getUserById(userId)
                .map(u -> u.getDefaultAdvanceMinutes())
                .orElse(10);

        ConfirmationSession session = ConfirmationSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .originalInput(originalInput)
                .parsedData(parsedDataJson)
                .parsedTitle((String) parsedData.get("title"))
                .parsedTime(parsedTime)
                .parsedEndTime(parsedEndTime)
                .parsedLocation((String) parsedData.get("location"))
                .parsedPriority((String) parsedData.getOrDefault("priority", Priority.NORMAL.name()))
                .parsedAdvanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", defaultAdvance))
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

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(String sessionId, Long userId) {
        return confirm(sessionId, userId, List.of());
    }

    // 确认会话：原子标记状态→创建提醒→提交事务→调用 AgentService 生成人性化回复（事务外）
    public Map<String, Object> confirm(String sessionId, Long userId, List<Long> conflictIdsToOverwrite) {
        // 在事务内执行 DB 操作（原子标记 + 创建提醒）
        Map<String, Object> result = transactionTemplate.execute(status -> {
            AtomicMarkResult markResult = atomicMarkConfirmed(sessionId, userId);
            if (markResult.getUpdated() == 0) {
                return handleDuplicateConfirm(sessionId, userId);
            }

            ConfirmationSession session = markResult.getSession();

            if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new InvalidInputException("该会话已过期，请重新创建");
            }

            Map<String, Object> parsedData = parseParsedData(session.getParsedData());

            if (Boolean.TRUE.equals(parsedData.get("isRecurring"))) {
                return confirmRecurring(session, userId, parsedData);
            }

            List<String> overwrittenTitles = deleteConflicts(userId, conflictIdsToOverwrite);
            Reminder reminder = createReminder(userId, parsedData, ConfirmStatus.CONFIRMED);

            session.setStage("REMINDER_EXECUTE");
            session.setReminderId(reminder.getId());
            confirmationRepository.save(session);

            String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
            Map<String, Object> basicResult = new HashMap<>();
            basicResult.put("success", true);
            basicResult.put("reminderId", reminder.getId());
            basicResult.put("title", reminder.getTitle());
            basicResult.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));
            if (!overwrittenTitles.isEmpty()) {
                basicResult.put("overwrittenTitle", overwrittenTitle);
            }
            basicResult.put("_llmFallback", buildConfirmSuccessMessage(reminder, overwrittenTitle));
            log.info("确认创建(事务内): sessionId={}, userId={}, reminderId={}, deletedConflicts={}", sessionId, userId, reminder.getId(), overwrittenTitles.size());
            return basicResult;
        });

        // 事务提交后，调用 AgentService 生成人性化回复（不占用数据库连接）
        if (result != null && Boolean.TRUE.equals(result.get("success"))) {
            String fallbackMsg = (String) result.remove("_llmFallback");
            // 覆盖场景：向 Agent 传递覆盖标题、覆盖数量等上下文，生成准确回复
            Map<String, Object> coverCtx = null;
            if (result.containsKey("overwrittenTitle")) {
                coverCtx = new HashMap<>();
                coverCtx.put("overwrittenTitle", result.get("overwrittenTitle"));
                // 过 result 中 overwrittenTitle 是 "、" 拼接的字符串，按分隔符拆分估算数量
                Object ovt = result.get("overwrittenTitle");
                if (ovt != null) {
                    int count = ovt.toString().split("、", -1).length;
                    coverCtx.put("overwrittenCount", count);
                }
            }
            // ========== 构建 contextData（天气/冲突/记忆，辅助查询失败不影响主流程） ==========
            Map<String, Object> contextData = new HashMap<>();

            // 1. 从 coverCtx 复制原有字段（覆盖标题/数量，如果有）
            if (coverCtx != null) {
                contextData.putAll(coverCtx);
            }

            // 2. 获取已创建的提醒对象
            Long reminderId = (Long) result.get("reminderId");
            Reminder reminder = null;
            if (reminderId != null) {
                reminder = reminderRepository.findById(reminderId).orElse(null);
            }

            if (reminder != null) {
                try {
                    // 3. 查天气（使用用户默认城市）
                    String defaultCity = (String) userService.getUserPreferences(userId).get("city");
                    if (defaultCity != null && !defaultCity.isEmpty()) {
                        WeatherNowResponse weatherResp = weatherService.getWeatherNow(defaultCity);
                        if (weatherResp != null && weatherResp.getNow() != null) {
                            String weatherText = weatherResp.getNow().getText();
                            String weatherTemp = weatherResp.getNow().getTemp();
                            if (weatherText != null) {
                                contextData.put("weather", "当前天气：" + weatherText + "，" + weatherTemp + "°C");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("查天气失败，不影响主流程: userId={}, error={}", userId, e.getMessage());
                }

                try {
                    // 4. 查冲突
                    ConflictResponse conflictResp = conflictDetectionService.checkConflict(
                            userId, reminder.getRemindTime(), reminderId
                    );
                    if (conflictResp != null && conflictResp.isHasConflict() && conflictResp.getConflicts() != null) {
                        List<String> conflictTitles = conflictResp.getConflicts().stream()
                                .map(ConflictResponse.ConflictItem::getTitle)
                                .collect(Collectors.toList());
                        if (!conflictTitles.isEmpty()) {
                            contextData.put("conflicts", conflictTitles);
                        }
                    }
                } catch (Exception e) {
                    log.warn("查冲突失败，不影响主流程: userId={}, error={}", userId, e.getMessage());
                }

                try {
                    // 5. 查记忆 + 分类
                    List<KnowledgeResult> memories = ragService.retrievePrivateKnowledge(
                            userId, reminder.getTitle(), 3
                    );
                    if (memories != null && !memories.isEmpty()) {
                        String memoryContent = memories.stream()
                                .map(KnowledgeResult::getContent)
                                .collect(Collectors.joining("；"));
                        contextData.put("memory", memoryContent);
                        contextData.put("memoryType", classifyMemory(memoryContent));
                    }
                } catch (Exception e) {
                    log.warn("查记忆失败，不影响主流程: userId={}, error={}", userId, e.getMessage());
                }
            }

            // 6. 调用 enrichWithAgentReply（复用现有扩展点，传入完整 contextData）
            enrichWithAgentReply(result, userId, sessionId, true, contextData);
            if (result.get("message") == null) {
                result.put("message", fallbackMsg != null ? fallbackMsg : "提醒已确认");
            }
        }

        return result;
    }

    // 处理重复确认请求：返回已存在的结果，不创建新提醒
    private Map<String, Object> handleDuplicateConfirm(String sessionId, Long userId) {
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

        SessionStatus status = session.getStatus();
        if (status == SessionStatus.CANCELLED) {
            throw new InvalidInputException("该会话已取消，不能确认");
        }
        if (status == SessionStatus.EXPIRED) {
            throw new InvalidInputException("该会话已过期，不能确认");
        }

        // 已经是 CONFIRMED：返回已创建的提醒信息
        if (status == SessionStatus.CONFIRMED) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("isDuplicate", true);
            if (session.getReminderId() != null) {
                result.put("reminderId", session.getReminderId());
                Reminder reminder = reminderRepository.findById(session.getReminderId()).orElse(null);
                if (reminder != null) {
                    result.put("title", reminder.getTitle());
                    result.put("remindTime", reminder.getRemindTime() != null
                            ? reminder.getRemindTime().format(TimeUtils.FORMATTER) : "");
                }
            }
            result.put("message", "该提醒已确认，无需重复操作～");
            log.info("重复确认请求已拦截: sessionId={}, userId={}", sessionId, userId);
            return result;
        }

        throw new InvalidInputException("会话状态异常，请重试");
    }

    // 周期提醒确认分支：调用周期生成实例，更新会话状态，不再发布事件（由统一回复流程处理）
    private Map<String, Object> confirmRecurring(ConfirmationSession session, Long userId, Map<String, Object> parsedData) {
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
        String repeatDays = (String) parsedData.get("repeatDays");
        String location = (String) parsedData.get("location");
        String priority = (String) parsedData.getOrDefault("priority", Priority.NORMAL.name());

        Map<String, Object> createResult = recurringReminderService.generateInstancesFromConfirm(
                userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, priority);

        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", createResult.get("success"));
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

        // 调用 AgentService 生成人性化回复，传入 createResult 作为上下文（含 successCount/skippedCount 等）
        try {
            AgentResponse agentResponse = agentService.handleConfirmationResult(userId, session.getSessionId(), true, createResult);
            if (agentResponse != null && agentResponse.getContent() != null) {
                result.put("message", agentResponse.getContent());
            } else {
                result.put("message", createResult.get("message"));
            }
        } catch (Exception e) {
            log.warn("AgentService 生成回复失败，使用兜底文案: sessionId={}, error={}", session.getSessionId(), e.getMessage());
            result.put("message", createResult.get("message"));
        }

        log.info("周期提醒确认创建: sessionId={}, userId={}, recurringId={}", session.getSessionId(), userId, createResult.get("recurringId"));
        return result;
    }

    // 周期提醒-直接创建全部：不覆盖冲突，冲突日多条并存
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmRecurringDirect(String sessionId, Long userId) {
        return executeRecurringConfirm(sessionId, userId, "direct", "直接");
    }

    // 周期提醒-创建无冲突部分：跳过冲突日，仅生成不冲突的实例
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmRecurringSkip(String sessionId, Long userId) {
        return executeRecurringConfirm(sessionId, userId, "skip", "跳过冲突");
    }

    // 周期提醒-全部覆盖：删除冲突提醒后再创建全部实例
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmRecurringOverwriteAll(String sessionId, Long userId) {
        return executeRecurringConfirm(sessionId, userId, "overwrite", "全部覆盖");
    }

    // 通用周期确认流程：原子标记 → 重复判断 → 解析数据 → 调用周期生成 → 更新会话 → 注入回复 → 推送卡片
    private Map<String, Object> executeRecurringConfirm(String sessionId, Long userId, String mode, String logLabel) {
        AtomicMarkResult markResult = atomicMarkConfirmed(sessionId, userId);
        if (markResult.getUpdated() == 0 && markResult.getSession().getStatus() == SessionStatus.CONFIRMED) {
            return buildDuplicateResponse();
        }
        ConfirmationSession session = markResult.getSession();
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        Map<String, Object> createResult = callRecurringGenerate(parsedData, userId, mode);

        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>(createResult);
        enrichWithAgentReply(result, userId, sessionId, true, createResult);
        pushCardConfirmed(userId, sessionId, (String) parsedData.get("title"), null);
        log.info("周期提醒{}创建: sessionId={}, userId={}", logLabel, sessionId, userId);
        return result;
    }

    // 查找并校验会话：已替换为原子 markConfirmed 模式，此方法仅用于非确认类操作（如编辑、重新解析）
    private ConfirmationSession findAndValidateSession(String sessionId, Long userId) {
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SessionStatus status = session.getStatus();
        if (status == SessionStatus.CONFIRMED) {
            throw new InvalidInputException("该会话已确认，不能重复操作");
        }
        if (status == SessionStatus.CANCELLED) {
            throw new InvalidInputException("该会话已取消");
        }
        if (status == SessionStatus.EXPIRED) {
            throw new InvalidInputException("该会话已过期，请重新创建");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);
            throw new InvalidInputException("该会话已过期，请重新创建");
        }
        return session;
    }

    // 通用：原子标记会话为 CONFIRMED + 调用 AgentService 生成人性化回复
    // 返回 AtomicMarkResult，包含 updated 标记与会话实体，用于区分“本次更新成功”和“已被其他请求更新”
    private AtomicMarkResult atomicMarkConfirmed(String sessionId, Long userId) {
        int updated = confirmationRepository.markConfirmed(sessionId, userId, LocalDateTime.now());
        if (updated == 0) {
            // 已被处理过，读取当前状态
            ConfirmationSession existing = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
            if (existing.getStatus() == SessionStatus.CANCELLED) {
                throw new InvalidInputException("该会话已取消，不能确认");
            }
            if (existing.getStatus() == SessionStatus.EXPIRED) {
                throw new InvalidInputException("该会话已过期，不能确认");
            }
            // CONFIRMED: 返回 updated=0，由调用方判断 isDuplicate
            return new AtomicMarkResult(0, existing);
        }
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        return new AtomicMarkResult(1, session);
    }

    // 内部类：封装 atomicMarkConfirmed 的返回结果，区分“本次更新成功(updated=1)”和“已被其他请求更新(updated=0)”
    private static class AtomicMarkResult {
        private final int updated;
        private final ConfirmationSession session;

        public AtomicMarkResult(int updated, ConfirmationSession session) {
            this.updated = updated;
            this.session = session;
        }

        public int getUpdated() {
            return updated;
        }

        public ConfirmationSession getSession() {
            return session;
        }
    }

    // 通用：调用 AgentService 生成确认回复，注入到 result 的 message 字段
    // contextData 可为 null（普通确认/取消场景），周期提醒传 createResult 含 successCount/skippedCount 等
    private void enrichWithAgentReply(Map<String, Object> result, Long userId, String sessionId, boolean confirmed, Map<String, Object> contextData) {
        try {
            AgentResponse agentResponse = agentService.handleConfirmationResult(userId, sessionId, confirmed, contextData);
            if (agentResponse != null && agentResponse.getContent() != null) {
                result.put("message", agentResponse.getContent());
            }
        } catch (Exception e) {
            log.warn("AgentService 生成回复失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    // 按 mode 调用周期提醒的不同生成策略：direct 全量 / skip 跳过冲突 / overwrite 覆盖冲突
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
        String repeatDays = (String) parsedData.get("repeatDays");
        String location = (String) parsedData.get("location");
        String priority = (String) parsedData.getOrDefault("priority", Priority.NORMAL.name());

        return switch (mode) {
            case "direct" -> recurringReminderService.generateAll(
                    userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, priority);
            case "skip" -> recurringReminderService.generateWithSkip(
                    userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, priority);
            case "overwrite" -> recurringReminderService.generateWithOverwrite(
                    userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, priority);
            default -> throw new InvalidInputException("未知的创建模式: " + mode);
        };
    }

    // 取消确认会话：原子标记为 CANCELLED + AgentService 生成人性化回复（事务外）
    public Map<String, Object> cancel(String sessionId, Long userId) {
        Map<String, Object> result = transactionTemplate.execute(status -> {
            int updated = confirmationRepository.markCancelled(sessionId, userId, LocalDateTime.now());
            if (updated == 0) {
                ConfirmationSession existing = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                        .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
                if (existing.getStatus() == SessionStatus.CONFIRMED) {
                    throw new InvalidInputException("该会话已确认，不能取消");
                }
                if (existing.getStatus() == SessionStatus.CANCELLED) {
                    Map<String, Object> dup = new HashMap<>();
                    dup.put("success", true);
                    dup.put("isDuplicate", true);
                    dup.put("message", "该会话已取消");
                    return dup;
                }
                if (existing.getStatus() == SessionStatus.EXPIRED) {
                    throw new InvalidInputException("该会话已过期，不能取消");
                }
            }
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            return r;
        });

        // 事务提交后，调用 AgentService 生成人性化取消回复
        if (result != null && Boolean.TRUE.equals(result.get("success")) && !Boolean.TRUE.equals(result.get("isDuplicate"))) {
            try {
                AgentResponse agentResponse = agentService.handleConfirmationResult(userId, sessionId, false, null);
                if (agentResponse != null && agentResponse.getContent() != null) {
                    result.put("message", agentResponse.getContent());
                } else {
                    result.put("message", "好的，已取消提醒。");
                }
            } catch (Exception e) {
                log.warn("AgentService 生成取消回复失败: sessionId={}, error={}", sessionId, e.getMessage());
                result.put("message", "好的，已取消提醒。");
            }
        }

        log.info("取消确认会话: sessionId={}, userId={}", sessionId, userId);
        return result;
    }

    // 覆盖创建（全量）：事务内 DB 操作 + 事务外 AgentService 回复
    public Map<String, Object> handleOverwrite(String sessionId, Long userId) {
        Map<String, Object> result = transactionTemplate.execute(status -> {
            AtomicMarkResult markResult = atomicMarkConfirmed(sessionId, userId);
            if (markResult.getUpdated() == 0 && markResult.getSession().getStatus() == SessionStatus.CONFIRMED) {
                return buildDuplicateResponse();
            }
            ConfirmationSession session = markResult.getSession();
            Map<String, Object> parsedData = parseParsedData(session.getParsedData());
            List<Long> allConflictIds = getAllConflictIds(parsedData);
            List<String> overwrittenTitles = deleteConflicts(userId, allConflictIds);
            Reminder reminder = createReminder(userId, parsedData, ConfirmStatus.CONFIRMED);
            session.setStage("REMINDER_EXECUTE");
            session.setReminderId(reminder.getId());
            confirmationRepository.save(session);
            String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            r.put("reminderId", reminder.getId());
            r.put("title", reminder.getTitle());
            r.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));
            if (reminder.getEndTime() != null) {
                r.put("endTime", reminder.getEndTime().format(TimeUtils.FORMATTER));
            }
            if (reminder.getLocation() != null) {
                r.put("location", reminder.getLocation());
            }
            if (reminder.getPriority() != null) {
                r.put("priority", reminder.getPriority().name());
            }
            if (!overwrittenTitles.isEmpty()) r.put("overwrittenTitle", overwrittenTitle);
            log.info("全量覆盖成功(事务内): sessionId={}, reminderId={}", sessionId, reminder.getId());
            return r;
        });
        if (result != null && Boolean.TRUE.equals(result.get("success")) && !Boolean.TRUE.equals(result.get("isDuplicate"))) {
            // 全量覆盖场景：向 Agent 传递覆盖标题、覆盖数量
            Map<String, Object> coverCtx = null;
            if (result.containsKey("overwrittenTitle")) {
                coverCtx = new HashMap<>();
                coverCtx.put("overwrittenTitle", result.get("overwrittenTitle"));
                Object ovt = result.get("overwrittenTitle");
                if (ovt != null) {
                    int count = ovt.toString().split("、", -1).length;
                    coverCtx.put("overwrittenCount", count);
                }
            }
            enrichWithAgentReply(result, userId, sessionId, true, coverCtx);
        }
        return result;
    }

    // 覆盖创建（指定 ID）：事务内 DB 操作 + 事务外 AgentService 回复
    public Map<String, Object> handleOverwrite(String sessionId, Long userId, List<Long> conflictIdsToOverwrite) {
        Map<String, Object> result = transactionTemplate.execute(status -> {
            AtomicMarkResult markResult = atomicMarkConfirmed(sessionId, userId);
            if (markResult.getUpdated() == 0 && markResult.getSession().getStatus() == SessionStatus.CONFIRMED) {
                return buildDuplicateResponse();
            }
            ConfirmationSession session = markResult.getSession();
            Map<String, Object> parsedData = parseParsedData(session.getParsedData());
            List<String> overwrittenTitles = deleteConflicts(userId, conflictIdsToOverwrite);
            Reminder reminder = createReminder(userId, parsedData, ConfirmStatus.CONFIRMED);
            session.setStage("REMINDER_EXECUTE");
            session.setReminderId(reminder.getId());
            confirmationRepository.save(session);
            String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            r.put("reminderId", reminder.getId());
            r.put("title", reminder.getTitle());
            r.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));
            if (reminder.getEndTime() != null) {
                r.put("endTime", reminder.getEndTime().format(TimeUtils.FORMATTER));
            }
            if (reminder.getLocation() != null) {
                r.put("location", reminder.getLocation());
            }
            if (reminder.getPriority() != null) {
                r.put("priority", reminder.getPriority().name());
            }
            if (!overwrittenTitles.isEmpty()) r.put("overwrittenTitle", overwrittenTitle);
            log.info("指定ID覆盖成功(事务内): sessionId={}, reminderId={}", sessionId, reminder.getId());
            return r;
        });
        if (result != null && Boolean.TRUE.equals(result.get("success")) && !Boolean.TRUE.equals(result.get("isDuplicate"))) {
            // 指定 ID 覆盖场景：向 Agent 传递覆盖标题、覆盖数量
            Map<String, Object> coverCtx = null;
            if (result.containsKey("overwrittenTitle")) {
                coverCtx = new HashMap<>();
                coverCtx.put("overwrittenTitle", result.get("overwrittenTitle"));
                Object ovt = result.get("overwrittenTitle");
                if (ovt != null) {
                    int count = ovt.toString().split("、", -1).length;
                    coverCtx.put("overwrittenCount", count);
                }
            }
            enrichWithAgentReply(result, userId, sessionId, true, coverCtx);
        }
        return result;
    }

    // 将 parsedData 中的 conflictList 转换为 List<ConflictResponse.ConflictItem>
    // 支持三种输入格式：JSON 字符串、List<Map>、List<ConflictItem>
    private List<ConflictResponse.ConflictItem> parseConflictList(Object conflictListObj) {
        if (conflictListObj == null) {
            return List.of();
        }
        try {
            List<?> conflicts;
            if (conflictListObj instanceof String) {
                conflicts = objectMapper.readValue((String) conflictListObj, List.class);
            } else if (conflictListObj instanceof List) {
                conflicts = (List<?>) conflictListObj;
            } else {
                return List.of();
            }
            List<ConflictResponse.ConflictItem> result = new java.util.ArrayList<>();
            for (Object c : conflicts) {
                if (c instanceof ConflictResponse.ConflictItem) {
                    result.add((ConflictResponse.ConflictItem) c);
                } else if (c instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) c;
                    ConflictResponse.ConflictItem item = new ConflictResponse.ConflictItem();
                    if (m.get("id") != null) item.setId(Long.valueOf(String.valueOf(m.get("id"))));
                    if (m.get("title") != null) item.setTitle(String.valueOf(m.get("title")));
                    if (m.get("remindTime") != null) {
                        item.setRemindTime(parseTimeFlexible(String.valueOf(m.get("remindTime"))));
                    }
                    if (m.get("endTime") != null) {
                        item.setEndTime(parseTimeFlexible(String.valueOf(m.get("endTime"))));
                    }
                    if (m.get("location") != null) item.setLocation(String.valueOf(m.get("location")));
                    result.add(item);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析冲突列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    // 从 parsedData 中汇总所有冲突提醒 ID：含单个 conflictId 与 conflictList 列表，去重返回
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

    // 按 ID 批量取消冲突提醒（校验归属），返回被取消提醒的标题列表
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

    // 处理冲突选择：按 action 分派覆盖/取消/修改三种处理方式
    // action=overwrite 删除冲突提醒后建新会话；cancel 直接放弃；modify 用新时间重新检测冲突
    @Transactional(rollbackFor = Exception.class)
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

    // 冲突处理-覆盖：删除指定冲突提醒，基于新信息创建确认会话
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
        parsedData.put("priority", priority != null ? priority : Priority.NORMAL.name());
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

    // 冲突处理-修改时间：用新时间重新检测冲突，无冲突则创建确认会话
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
                    ? conflictResponse.getSuggestedTime().format(TimeUtils.FORMATTER) : null);
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
        parsedData.put("priority", priority != null ? priority : Priority.NORMAL.name());
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

    // 重新解析会话：用新输入覆盖原解析数据，重新做冲突检测并刷新过期时间后再次推送卡片
    @Transactional(rollbackFor = Exception.class)
    public ConfirmationCardResponse reparse(String sessionId, Long userId, String newInput, Map<String, Object> newParsedData) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        // 保留原 parsedData 中的 note：表单式重新解析接口（如卡片上改时间）不会传 note，
        // 若不补回原值，buildConfirmationCard 与 createReminder 都会用 null 覆盖原备注
        if (newParsedData.get("note") == null) {
            Map<String, Object> oldParsedData = parseParsedData(session.getParsedData());
            Object oldNote = oldParsedData.get("note");
            if (oldNote != null) {
                newParsedData.put("note", oldNote);
            }
        }

        // 冲突检测（修改时间后重新检测）
        String remindTimeStr = (String) newParsedData.get("remindTime");
        if (remindTimeStr != null) {
            try {
                LocalDateTime parsedTime = LocalDateTime.parse(remindTimeStr, TimeUtils.FORMATTER);
                ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, null);
                if (conflictResponse.isHasConflict()) {
                    String description = conflictDetectionService.getConflictDescription(
                            conflictResponse.getConflicts(), parsedTime);
                    newParsedData.put("hasConflict", true);
                    newParsedData.put("conflictMessage", description);
                    newParsedData.put("conflictList", conflictResponse.getConflicts());
                    newParsedData.put("suggestedTime", conflictResponse.getSuggestedTime() != null 
                            ? conflictResponse.getSuggestedTime().format(TimeUtils.FORMATTER) : null);
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

        // 周期字段恢复：若 newParsedData 缺失周期字段，从旧会话的 parsedData 中补齐
        boolean missingIsRecurring = !Boolean.TRUE.equals(newParsedData.get("isRecurring"));
        boolean missingStartDate = newParsedData.get("startDate") == null;
        boolean missingEndDate = newParsedData.get("endDate") == null;
        boolean missingRecurringRemindTime = newParsedData.get("recurringRemindTime") == null;
        boolean missingRepeatType = newParsedData.get("repeatType") == null;

        if (missingIsRecurring || missingStartDate || missingEndDate
                || missingRecurringRemindTime || missingRepeatType) {
            Map<String, Object> oldData = parseParsedData(session.getParsedData());
            if (Boolean.TRUE.equals(oldData.get("isRecurring"))) {
                // 旧会话是周期提醒，说明当前请求体缺失周期字段，逐个恢复
                if (missingIsRecurring && oldData.get("isRecurring") != null) {
                    newParsedData.put("isRecurring", oldData.get("isRecurring"));
                }
                if (missingStartDate && oldData.get("startDate") != null) {
                    newParsedData.put("startDate", oldData.get("startDate"));
                }
                if (missingEndDate && oldData.get("endDate") != null) {
                    newParsedData.put("endDate", oldData.get("endDate"));
                }
                if (missingRecurringRemindTime && oldData.get("recurringRemindTime") != null) {
                    newParsedData.put("recurringRemindTime", oldData.get("recurringRemindTime"));
                }
                if (missingRepeatType && oldData.get("repeatType") != null) {
                    newParsedData.put("repeatType", oldData.get("repeatType"));
                }
                if (oldData.get("repeatDays") != null && newParsedData.get("repeatDays") == null) {
                    newParsedData.put("repeatDays", oldData.get("repeatDays"));
                }
            }
        }

        // 重新计算 totalDays：startDate、endDate 都存在时重新计算天数差 + 1
        Object sdObj = newParsedData.get("startDate");
        Object edObj = newParsedData.get("endDate");
        if (sdObj != null && edObj != null) {
            try {
                LocalDate sd = LocalDate.parse(String.valueOf(sdObj));
                LocalDate ed = LocalDate.parse(String.valueOf(edObj));
                long totalDays = java.time.temporal.ChronoUnit.DAYS.between(sd, ed) + 1;
                if (totalDays > 0) {
                    newParsedData.put("totalDays", totalDays);
                }
            } catch (Exception e) {
                log.warn("reparse计算totalDays失败: startDate={}, endDate={}, err={}",
                        sdObj, edObj, e.getMessage());
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

        // 周期提醒：清理标题中的显示格式（📅 周期提醒：前缀 + （每天/每周/每月）后缀）
        // 防止装饰字符串被再次保存为原始标题，导致二次 build 时拼接重复
        String title = (String) newParsedData.get("title");
        boolean reparseIsRecurring = Boolean.TRUE.equals(newParsedData.get("isRecurring"));
        if (reparseIsRecurring && title != null) {
            title = title.replaceAll("^📅\\s*周期提醒：", "");
            title = title.replaceAll("（(每天|每周|每月)）$", "");
            newParsedData.put("title", title);
        }
        session.setParsedTitle(title);
        session.setParsedTime(parsedTime);
        session.setParsedLocation((String) newParsedData.get("location"));
        session.setParsedPriority((String) newParsedData.getOrDefault("priority", Priority.NORMAL.name()));
        session.setParsedAdvanceMinutes((Integer) newParsedData.getOrDefault("advanceMinutes", 10));
        session.setRetryCount(session.getRetryCount() + 1);
        // 根据 conflict/recurring 决定是否超时，与 createSession 逻辑一致
        boolean hasConflict = Boolean.TRUE.equals(newParsedData.get("hasConflict"));
        boolean isRecurring = Boolean.TRUE.equals(newParsedData.get("isRecurring"));
        LocalDateTime expiresAt = (isRecurring || hasConflict) ? null : LocalDateTime.now().plusSeconds(TIMEOUT_SECONDS);
        session.setExpiresAt(expiresAt);
        confirmationRepository.save(session);

        ConfirmationCardResponse card = buildConfirmationCard(session, newParsedData);
        pushConfirmationCard(userId, card);

        log.info("阶段一修改: sessionId={}, userId={}, retryCount={}", sessionId, userId, session.getRetryCount());
        return card;
    }

    // ===== 多项事务确认会话方法 =====

    // 创建多项事务确认会话：为每项事务检测冲突后组装 parsedData，构建并推送多项确认卡片
    @Transactional(rollbackFor = Exception.class)
    public ConfirmationCardResponse createMultiSession(Long userId, List<Map<String, Object>> items, String source) {
        if (items == null || items.isEmpty()) {
            throw new InvalidInputException("事务列表不能为空");
        }

        String sessionId = UUID.randomUUID().toString();
        int defaultAdvance = userService.getUserById(userId)
                .map(u -> u.getDefaultAdvanceMinutes())
                .orElse(10);

        // 按 remindTime 排序后处理，确保同批次互相冲突检测按时间顺序进行
        List<Map<String, Object>> sortedItems = new ArrayList<>(items);
        sortedItems.sort((a, b) -> {
            String timeA = a.get("remindTime") != null ? String.valueOf(a.get("remindTime")) : "";
            String timeB = b.get("remindTime") != null ? String.valueOf(b.get("remindTime")) : "";
            return timeA.compareTo(timeB);
        });

        List<Map<String, Object>> processedItems = new ArrayList<>();
        // 已确认无冲突的 item 列表，用于后续 item 的同批次互相冲突检测
        List<Map<String, Object>> confirmedItems = new ArrayList<>();
        for (int i = 0; i < sortedItems.size(); i++) {
            Map<String, Object> raw = sortedItems.get(i);
            String title = raw.get("title") != null ? String.valueOf(raw.get("title")).trim() : null;
            String remindTime = raw.get("remindTime") != null ? String.valueOf(raw.get("remindTime")).trim() : null;
            if (title == null || title.isBlank() || remindTime == null || remindTime.isBlank()) {
                throw new InvalidInputException("第 " + (i + 1) + " 项缺少标题或提醒时间");
            }

            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("title", title);
            item.put("remindTime", remindTime);
            item.put("endTime", raw.get("endTime") != null ? String.valueOf(raw.get("endTime")) : null);
            item.put("location", raw.get("location") != null ? String.valueOf(raw.get("location")) : null);
            item.put("note", raw.get("note") != null ? String.valueOf(raw.get("note")) : null);
            item.put("priority", raw.get("priority") != null ? String.valueOf(raw.get("priority")) : Priority.NORMAL.name());
            item.put("advanceMinutes", safeToInteger(raw.get("advanceMinutes"), defaultAdvance));
            item.put("status", "PENDING");

            // 1. 与已有提醒的冲突检测
            boolean hasDbConflict = false;
            try {
                LocalDateTime parsedTime = parseTimeFlexible(remindTime);
                ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, null);
                if (conflictResponse.isHasConflict()) {
                    hasDbConflict = true;
                    String description = conflictDetectionService.getConflictDescription(
                            conflictResponse.getConflicts(), parsedTime);
                    item.put("hasConflict", true);
                    item.put("conflictMessage", description);
                    if (conflictResponse.getConflicts() != null && !conflictResponse.getConflicts().isEmpty()) {
                        ConflictResponse.ConflictItem first = conflictResponse.getConflicts().get(0);
                        item.put("conflictId", first.getId());
                        item.put("conflictTitle", first.getTitle());
                        item.put("conflictRemindTime", first.getRemindTime() != null
                                ? first.getRemindTime().format(TimeUtils.FORMATTER) : null);
                    }
                } else {
                    item.put("hasConflict", false);
                }
            } catch (Exception e) {
                log.warn("多项事务冲突检测失败: item={}, err={}", title, e.getMessage());
                item.put("hasConflict", false);
            }

            // 2. 同批次互相冲突检测：遍历 confirmedItems 检查时间重叠
            String sameBatchConflictTitle = findSameBatchConflictTitle(item, confirmedItems);
            if (sameBatchConflictTitle != null) {
                item.put("hasConflict", true);
                String existingMsg = item.get("conflictMessage") != null
                        ? String.valueOf(item.get("conflictMessage")) : null;
                String batchMsg = "与同批次中的「" + sameBatchConflictTitle + "」时间重叠";
                if (existingMsg != null && !existingMsg.isBlank()) {
                    item.put("conflictMessage", existingMsg + "；" + batchMsg);
                } else {
                    item.put("conflictMessage", batchMsg);
                }
            }

            // 3. 只有无冲突的 item 才加入 confirmedItems，供后续 item 检测同批次冲突
            if (!hasDbConflict && sameBatchConflictTitle == null) {
                confirmedItems.add(item);
            }

            processedItems.add(item);
        }

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("items", processedItems);
        parsedData.put("totalCount", processedItems.size());
        parsedData.put("confirmedCount", 0);
        parsedData.put("cancelledCount", 0);
        parsedData.put("source", source);
        parsedData.put("isBatch", true);
        parsedData.put("isMulti", true);
        parsedData.put("type", "multi_confirmation");

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(parsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        ConfirmationSession session = ConfirmationSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .originalInput("多项事务来源: " + source)
                .parsedData(parsedDataJson)
                .stage("INFO_CONFIRM")
                .status(SessionStatus.PENDING)
                .expiresAt(null) // 多项事务无超时，须用户手动处理
                .retryCount(0)
                .attemptCount(0)
                .maxAttempts(3)
                .intervalMinutes(10)
                .build();

        confirmationRepository.save(session);

        ConfirmationCardResponse card = buildMultiConfirmationCard(session, parsedData);
        pushConfirmationCard(userId, card);

        log.info("创建多项事务确认会话: sessionId={}, userId={}, itemCount={}", sessionId, userId, processedItems.size());
        return card;
    }

    // 确认选中的多条事务：跳过已处理项和有冲突项，无冲突项创建提醒并标记 CONFIRMED
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmSelected(String sessionId, Long userId, List<Integer> itemIndexes) {
        ConfirmationSession session = findValidSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        List<Map<String, Object>> items = getMultiItems(parsedData);
        if (items.isEmpty()) {
            throw new InvalidInputException("会话中无多项事务数据");
        }

        int confirmedCount = safeToInteger(parsedData.get("confirmedCount"), 0);
        int cancelledCount = safeToInteger(parsedData.get("cancelledCount"), 0);
        int totalCount = items.size();

        List<Map<String, Object>> conflictList = new ArrayList<>();
        int successCount = 0;

        for (Integer idx : itemIndexes) {
            if (idx == null || idx < 0 || idx >= items.size()) {
                continue;
            }
            Map<String, Object> item = items.get(idx);
            String status = String.valueOf(item.getOrDefault("status", "PENDING"));
            if (!"PENDING".equals(status)) {
                continue;
            }
            boolean hasConflict = safeToBoolean(item.get("hasConflict"), false);
            if (hasConflict) {
                Map<String, Object> conflictInfo = new HashMap<>();
                conflictInfo.put("itemIndex", idx);
                conflictInfo.put("title", item.get("title"));
                conflictInfo.put("conflictMessage", item.get("conflictMessage"));
                conflictList.add(conflictInfo);
                continue;
            }
            createReminder(userId, item, ConfirmStatus.CONFIRMED);
            item.put("status", "CONFIRMED");
            successCount++;
            confirmedCount++;
        }

        parsedData.put("items", items);
        parsedData.put("confirmedCount", confirmedCount);
        parsedData.put("cancelledCount", cancelledCount);
        saveMultiParsedData(session, parsedData);

        boolean allProcessed = (confirmedCount + cancelledCount) == totalCount;

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("confirmedCount", successCount);
        result.put("conflictCount", conflictList.size());
        result.put("conflictList", conflictList);
        result.put("allProcessed", allProcessed);
        result.put("sessionId", sessionId);
        if (allProcessed) {
            result.put("message", buildMultiReply(session, items));
        } else {
            result.put("message", buildMultiResultMessage(successCount, conflictList.size(), "确认"));
        }
        log.info("多项确认选中: sessionId={}, userId={}, success={}, conflict={}",
                sessionId, userId, successCount, conflictList.size());
        return result;
    }

    // 取消选中的多条事务：仅将 PENDING 项标记为 CANCELLED，不创建提醒
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelSelected(String sessionId, Long userId, List<Integer> itemIndexes) {
        ConfirmationSession session = findValidSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        List<Map<String, Object>> items = getMultiItems(parsedData);
        if (items.isEmpty()) {
            throw new InvalidInputException("会话中无多项事务数据");
        }

        int confirmedCount = safeToInteger(parsedData.get("confirmedCount"), 0);
        int cancelledCount = safeToInteger(parsedData.get("cancelledCount"), 0);
        int totalCount = items.size();
        int cancelCount = 0;

        for (Integer idx : itemIndexes) {
            if (idx == null || idx < 0 || idx >= items.size()) {
                continue;
            }
            Map<String, Object> item = items.get(idx);
            String status = String.valueOf(item.getOrDefault("status", "PENDING"));
            if (!"PENDING".equals(status)) {
                continue;
            }
            item.put("status", "CANCELLED");
            cancelCount++;
            cancelledCount++;
        }

        parsedData.put("items", items);
        parsedData.put("confirmedCount", confirmedCount);
        parsedData.put("cancelledCount", cancelledCount);
        saveMultiParsedData(session, parsedData);

        boolean allProcessed = (confirmedCount + cancelledCount) == totalCount;

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("cancelledCount", cancelCount);
        result.put("allProcessed", allProcessed);
        result.put("sessionId", sessionId);
        if (allProcessed) {
            result.put("message", buildMultiReply(session, items));
        } else {
            result.put("message", buildMultiResultMessage(cancelCount, 0, "取消"));
        }
        log.info("多项取消选中: sessionId={}, userId={}, cancelled={}", sessionId, userId, cancelCount);
        return result;
    }

    // 覆盖选中的多条事务：有冲突项先删除冲突提醒再创建，无冲突项直接创建
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> overwriteSelected(String sessionId, Long userId, List<Integer> itemIndexes) {
        ConfirmationSession session = findValidSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        List<Map<String, Object>> items = getMultiItems(parsedData);
        if (items.isEmpty()) {
            throw new InvalidInputException("会话中无多项事务数据");
        }

        int confirmedCount = safeToInteger(parsedData.get("confirmedCount"), 0);
        int cancelledCount = safeToInteger(parsedData.get("cancelledCount"), 0);
        int totalCount = items.size();
        int successCount = 0;
        int overwrittenCount = 0;

        for (Integer idx : itemIndexes) {
            if (idx == null || idx < 0 || idx >= items.size()) {
                continue;
            }
            Map<String, Object> item = items.get(idx);
            String status = String.valueOf(item.getOrDefault("status", "PENDING"));
            if (!"PENDING".equals(status)) {
                continue;
            }
            boolean hasConflict = safeToBoolean(item.get("hasConflict"), false);
            if (hasConflict && item.get("conflictId") != null) {
                Long conflictId = Long.valueOf(String.valueOf(item.get("conflictId")));
                deleteConflicts(userId, List.of(conflictId));
                overwrittenCount++;
            }
            createReminder(userId, item, ConfirmStatus.CONFIRMED);
            item.put("status", "CONFIRMED");
            successCount++;
            confirmedCount++;
        }

        parsedData.put("items", items);
        parsedData.put("confirmedCount", confirmedCount);
        parsedData.put("cancelledCount", cancelledCount);
        saveMultiParsedData(session, parsedData);

        boolean allProcessed = (confirmedCount + cancelledCount) == totalCount;

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("confirmedCount", successCount);
        result.put("overwrittenCount", overwrittenCount);
        result.put("allProcessed", allProcessed);
        result.put("sessionId", sessionId);
        if (allProcessed) {
            result.put("message", buildMultiReply(session, items));
        } else {
            result.put("message", buildMultiResultMessage(successCount, overwrittenCount, "覆盖"));
        }
        log.info("多项覆盖选中: sessionId={}, userId={}, success={}, overwritten={}",
                sessionId, userId, successCount, overwrittenCount);
        return result;
    }

    // 修改单条事务：更新字段后重新检测冲突，返回更新后的卡片数据
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> modifyMultiItem(String sessionId, Long userId, Integer itemIndex, Map<String, Object> newData) {
        ConfirmationSession session = findValidSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        List<Map<String, Object>> items = getMultiItems(parsedData);

        if (itemIndex == null || itemIndex < 0 || itemIndex >= items.size()) {
            throw new InvalidInputException("条目索引无效");
        }
        Map<String, Object> item = items.get(itemIndex);
        String status = String.valueOf(item.getOrDefault("status", "PENDING"));
        if (!"PENDING".equals(status)) {
            throw new InvalidInputException("该条目已处理，不能修改");
        }

        if (newData.get("title") != null) {
            item.put("title", String.valueOf(newData.get("title")).trim());
        }
        if (newData.get("remindTime") != null) {
            item.put("remindTime", String.valueOf(newData.get("remindTime")).trim());
        }
        if (newData.get("endTime") != null) {
            item.put("endTime", String.valueOf(newData.get("endTime")));
        }
        if (newData.get("location") != null) {
            item.put("location", String.valueOf(newData.get("location")));
        }
        if (newData.get("note") != null) {
            item.put("note", String.valueOf(newData.get("note")));
        }
        if (newData.get("priority") != null) {
            item.put("priority", String.valueOf(newData.get("priority")));
        }
        if (newData.get("advanceMinutes") != null) {
            item.put("advanceMinutes", safeToInteger(newData.get("advanceMinutes"), 10));
        }

        // 重新检测冲突
        String remindTime = String.valueOf(item.get("remindTime"));
        try {
            LocalDateTime parsedTime = parseTimeFlexible(remindTime);
            ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, null);
            if (conflictResponse.isHasConflict()) {
                String description = conflictDetectionService.getConflictDescription(
                        conflictResponse.getConflicts(), parsedTime);
                item.put("hasConflict", true);
                item.put("conflictMessage", description);
                if (conflictResponse.getConflicts() != null && !conflictResponse.getConflicts().isEmpty()) {
                    ConflictResponse.ConflictItem first = conflictResponse.getConflicts().get(0);
                    item.put("conflictId", first.getId());
                    item.put("conflictTitle", first.getTitle());
                    item.put("conflictRemindTime", first.getRemindTime() != null
                            ? first.getRemindTime().format(TimeUtils.FORMATTER) : null);
                }
            } else {
                item.put("hasConflict", false);
                item.remove("conflictMessage");
                item.remove("conflictId");
                item.remove("conflictTitle");
                item.remove("conflictRemindTime");
            }
        } catch (Exception e) {
            log.warn("修改后冲突检测失败: {}", e.getMessage());
            item.put("hasConflict", false);
        }

        recountMultiStats(parsedData, items);
        saveMultiParsedData(session, parsedData);

        ConfirmationCardResponse card = buildMultiConfirmationCard(session, parsedData);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已修改该条目");
        result.put("data", card.getData());
        result.put("sessionId", sessionId);
        return result;
    }

    // 获取单条冲突详情（供前端弹窗使用）：按 conflictId 查询冲突提醒详情
    public ConflictDetailResponse getMultiConflictDetail(String sessionId, Long userId, Integer itemIndex) {
        ConfirmationSession session = findValidSession(sessionId, userId);
        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        List<Map<String, Object>> items = getMultiItems(parsedData);

        if (itemIndex == null || itemIndex < 0 || itemIndex >= items.size()) {
            throw new InvalidInputException("条目索引无效");
        }
        Map<String, Object> item = items.get(itemIndex);
        if (!safeToBoolean(item.get("hasConflict"), false)) {
            throw new InvalidInputException("该条目无冲突");
        }

        Long conflictId = item.get("conflictId") != null ? Long.valueOf(String.valueOf(item.get("conflictId"))) : null;
        String conflictTitle = null;
        String conflictRemindTime = null;
        String conflictLocation = null;
        if (conflictId != null) {
            Reminder conflictReminder = reminderRepository.findById(conflictId)
                    .filter(r -> r.getUserId().equals(userId))
                    .orElse(null);
            if (conflictReminder != null) {
                conflictTitle = conflictReminder.getTitle();
                conflictRemindTime = conflictReminder.getRemindTime() != null
                        ? conflictReminder.getRemindTime().format(TimeUtils.FORMATTER) : null;
                conflictLocation = conflictReminder.getLocation();
            }
        }

        return ConflictDetailResponse.builder()
                .currentId(itemIndex.longValue())
                .currentTitle(String.valueOf(item.get("title")))
                .currentRemindTime(String.valueOf(item.get("remindTime")))
                .currentLocation(item.get("location") != null ? String.valueOf(item.get("location")) : null)
                .conflictReminderId(conflictId)
                .conflictTitle(conflictTitle)
                .conflictRemindTime(conflictRemindTime)
                .conflictLocation(conflictLocation)
                .conflictMessage(item.get("conflictMessage") != null ? String.valueOf(item.get("conflictMessage")) : null)
                .build();
    }

    // 构建多项事务确认卡片：从 parsedData 提取条目与统计字段，组装卡片数据与按钮
    private ConfirmationCardResponse buildMultiConfirmationCard(ConfirmationSession session, Map<String, Object> parsedData) {
        List<Map<String, Object>> items = getMultiItems(parsedData);
        int totalCount = items.size();
        int[] stats = countMultiItemStats(items);
        int confirmedCount = stats[0];
        int cancelledCount = stats[1];
        int conflictCount = stats[2];
        int pendingCount = totalCount - confirmedCount - cancelledCount;

        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .multiItems(buildMultiItemList(items))
                .totalCount(totalCount)
                .confirmedCount(confirmedCount)
                .cancelledCount(cancelledCount)
                .pendingCount(pendingCount)
                .conflictCount(conflictCount)
                .isMulti(true)
                .build();

        String message;
        if (conflictCount > 0) {
            message = "📋 识别到 " + totalCount + " 项事务，其中 " + conflictCount + " 项存在时间冲突，请处理";
        } else {
            message = "📋 识别到 " + totalCount + " 项事务，请确认";
        }

        List<Action> actions = Arrays.asList(
                Action.builder().id("confirm_selected").label("✅ 确认选中").style("primary").build(),
                Action.builder().id("cancel_selected").label("❌ 取消选中").style("danger").build(),
                Action.builder().id("overwrite_selected").label("📝 覆盖选中").style("warning").build()
        );

        return ConfirmationCardResponse.builder()
                .type("multi_confirmation_card")
                .message(message)
                .data(data)
                .actions(actions)
                .timeout(null) // 多项事务无超时
                .expiresAt(null)
                .build();
    }

    // 从 parsedData 提取多项事务条目列表，兼容反序列化后的 List<LinkedHashMap>
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getMultiItems(Map<String, Object> parsedData) {
        Object itemsObj = parsedData.get("items");
        if (itemsObj instanceof List) {
            return (List<Map<String, Object>>) itemsObj;
        }
        return new ArrayList<>();
    }

    // 重新序列化多项事务 parsedData 并保存会话
    private void saveMultiParsedData(ConfirmationSession session, Map<String, Object> parsedData) {
        try {
            session.setParsedData(objectMapper.writeValueAsString(parsedData));
        } catch (JsonProcessingException e) {
            log.warn("序列化多项事务 parsedData 失败: {}", e.getMessage());
        }
        confirmationRepository.save(session);
    }

    // 统计条目状态：返回 [confirmedCount, cancelledCount, conflictCount]
    private int[] countMultiItemStats(List<Map<String, Object>> items) {
        int confirmed = 0;
        int cancelled = 0;
        int conflict = 0;
        for (Map<String, Object> item : items) {
            String status = String.valueOf(item.getOrDefault("status", "PENDING"));
            if ("CONFIRMED".equals(status)) {
                confirmed++;
            } else if ("CANCELLED".equals(status)) {
                cancelled++;
            }
            if (safeToBoolean(item.get("hasConflict"), false)) {
                conflict++;
            }
        }
        return new int[]{confirmed, cancelled, conflict};
    }

    // 根据 items 列表更新 parsedData 中的统计字段
    private void recountMultiStats(Map<String, Object> parsedData, List<Map<String, Object>> items) {
        int[] stats = countMultiItemStats(items);
        parsedData.put("totalCount", items.size());
        parsedData.put("confirmedCount", stats[0]);
        parsedData.put("cancelledCount", stats[1]);
    }

    // 将 items Map 列表映射为 MultiItem DTO 列表，确保前端字段完整
    private List<MultiItem> buildMultiItemList(List<Map<String, Object>> items) {
        List<MultiItem> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            list.add(MultiItem.builder()
                    .id(i)
                    .title(String.valueOf(item.get("title")))
                    .remindTime(item.get("remindTime") != null ? String.valueOf(item.get("remindTime")) : null)
                    .endTime(item.get("endTime") != null ? String.valueOf(item.get("endTime")) : null)
                    .location(item.get("location") != null ? String.valueOf(item.get("location")) : null)
                    .note(item.get("note") != null ? String.valueOf(item.get("note")) : null)
                    .priority(item.get("priority") != null ? String.valueOf(item.get("priority")) : null)
                    .advanceMinutes(item.get("advanceMinutes") != null ? safeToInteger(item.get("advanceMinutes"), 10) : null)
                    .status(String.valueOf(item.getOrDefault("status", "PENDING")))
                    .hasConflict(safeToBoolean(item.get("hasConflict"), false))
                    .conflictMessage(item.get("conflictMessage") != null ? String.valueOf(item.get("conflictMessage")) : null)
                    .conflictId(item.get("conflictId") != null ? Long.valueOf(String.valueOf(item.get("conflictId"))) : null)
                    .conflictTitle(item.get("conflictTitle") != null ? String.valueOf(item.get("conflictTitle")) : null)
                    .conflictRemindTime(item.get("conflictRemindTime") != null ? String.valueOf(item.get("conflictRemindTime")) : null)
                    .selected(false)
                    .build());
        }
        return list;
    }

    // 为每条事务准备前端显示数据（确保字段完整），供 getSessionData 等场景复用
    private List<Map<String, Object>> prepareItemsForFrontend(List<Map<String, Object>> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Map<String, Object> view = new HashMap<>(item);
            view.put("id", i);
            view.putIfAbsent("status", "PENDING");
            view.putIfAbsent("hasConflict", false);
            view.putIfAbsent("priority", Priority.NORMAL.name());
            result.add(view);
        }
        return result;
    }

    // 检查多项事务是否全部处理完成（已确认 + 已取消 == 总数）
    private boolean isAllItemsProcessed(Map<String, Object> parsedData) {
        int total = safeToInteger(parsedData.get("totalCount"), 0);
        int confirmed = safeToInteger(parsedData.get("confirmedCount"), 0);
        int cancelled = safeToInteger(parsedData.get("cancelledCount"), 0);
        return total > 0 && (confirmed + cancelled) == total;
    }

    // 生成多项操作结果文案
    private String buildMultiResultMessage(int count, int extra, String action) {
        StringBuilder sb = new StringBuilder();
        switch (action) {
            case "确认" -> {
                sb.append("✅ 已确认 ").append(count).append(" 项");
                if (extra > 0) {
                    sb.append("，").append(extra).append(" 项有冲突需处理");
                }
            }
            case "取消" -> sb.append("❌ 已取消 ").append(count).append(" 项");
            case "覆盖" -> sb.append("📝 已覆盖 ").append(count).append(" 项");
            default -> sb.append("操作完成");
        }
        return sb.toString();
    }

    // 所有条目处理完成后，生成四段式结构化回复
    // 将 items Map 列表转为 TaskDetail 列表，调用 ReplyTemplateService 生成完整回复
    private String buildMultiReply(ConfirmationSession session, List<Map<String, Object>> items) {
        try {
            List<TaskDetail> tasks = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                tasks.add(TaskDetail.fromMap(items.get(i)));
            }
            String userInput = session != null ? session.getOriginalInput() : null;
            return replyTemplateService.buildReply(userInput, tasks);
        } catch (Exception e) {
            log.warn("生成多任务结构化回复失败，回退简单文案: err={}", e.getMessage());
            return "✅ 多项事务已处理完成";
        }
    }

    // 定时清理过期会话：每 15 秒扫描一次，只处理有超时的会话（expiresAt 不为 null）
    @Scheduled(fixedRate = 15000)
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredSessions() {
        List<ConfirmationSession> expired = confirmationRepository.findByStatusAndExpiresAtBefore(
                SessionStatus.PENDING, LocalDateTime.now())
                .stream()
                .filter(s -> s.getExpiresAt() != null)  // 只处理有超时的会话
                .toList();

        for (ConfirmationSession session : expired) {
            if ("INFO_CONFIRM".equals(session.getStage())) {
                try {
                    Map<String, Object> parsedData = parseParsedData(session.getParsedData());

                    // 无冲突的单次提醒：超时自动确认
                    Reminder reminder = createReminderFromSessionWithoutPush(session);

                    String autoMessage = buildAutoConfirmMessage(reminder);

                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("message", autoMessage);
                    result.put("reminderId", reminder.getId());
                    result.put("sessionId", session.getSessionId());
                    result.put("title", reminder.getTitle());
                    result.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));

                    // 保留 timeout_auto_create 消息用于向后兼容
                    pushUserMessage(session.getUserId(), "timeout_auto_create", result);

                    // 超时自动确认后也让 LLM 生成一句 bot 回复（和用户手动确认/取消走同一条链路），
                    // 避免"卡片确认成功了但没有文字回复"的打断感
                    try {
                        // 向 Agent 传递具体提醒的 title 和 remindTime，让回复更准确
                        Map<String, Object> ctx = new HashMap<>();
                        ctx.put("title", reminder.getTitle());
                        ctx.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));
                        agentService.handleConfirmationResult(
                                session.getUserId(), session.getSessionId(), true, ctx);
                    } catch (Exception e) {
                        log.warn("超时自动确认后生成LLM回复失败，跳过: sessionId={}, error={}",
                                session.getSessionId(), e.getMessage());
                    }

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
            } else if ("REMINDER_EXECUTE".equals(session.getStage())) {
                // 阶段二会话不处理超时，跳过
                log.info("跳过阶段二过期会话: sessionId={}, userId={}", session.getSessionId(), session.getUserId());
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

    // 周期提醒超时自动确认：生成周期实例后推送自动创建消息，并交由 Agent 生成回复
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
        String repeatDays = (String) parsedData.get("repeatDays");
        String location = (String) parsedData.get("location");
        String priority = (String) parsedData.getOrDefault("priority", Priority.NORMAL.name());

        Map<String, Object> createResult = recurringReminderService.generateInstancesFromConfirm(
                session.getUserId(), title, remindTime, startDate, endDate, repeatType, repeatDays, location, priority);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", createResult.get("success"));
        result.put("message", "⏰ 5分钟未确认，已自动创建周期提醒：" + createResult.get("message"));
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

        // 周期提醒超时自动确认后也走 handleConfirmationResult 统一生成 bot 回复
        // 接收 Agent 返回值：如果 LLM 正常生成自然语言，则覆盖 result.message（与 bot 回复保持一致）
        try {
            AgentResponse agentResponse = agentService.handleConfirmationResult(session.getUserId(), session.getSessionId(), true, createResult);
            if (agentResponse != null && agentResponse.getContent() != null) {
                result.put("message", agentResponse.getContent());
            }
        } catch (Exception e) {
            log.warn("周期提醒超时自动确认后生成LLM回复失败，跳过: sessionId={}, error={}",
                    session.getSessionId(), e.getMessage());
        }

        log.info("周期提醒超时自动确认: sessionId={}, userId={}, recurringId={}",
                session.getSessionId(), session.getUserId(), createResult.get("recurringId"));
    }

    // 灵活解析时间字符串，依次尝试多种常见格式，全部失败则抛 DateTimeParseException
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

    // 同批次时间重叠检测：返回与当前 item 时间重叠的已确认 item 标题，无重叠返回 null
    // 重叠判定：startA < endB && startB < endA
    //   其中 start = remindTime - advanceMinutes，end = remindTime
    private String findSameBatchConflictTitle(Map<String, Object> currentItem, List<Map<String, Object>> confirmedItems) {
        if (currentItem == null || confirmedItems == null || confirmedItems.isEmpty()) {
            return null;
        }
        LocalDateTime currentRemindTime;
        try {
            currentRemindTime = parseTimeFlexible(String.valueOf(currentItem.get("remindTime")));
        } catch (Exception e) {
            return null;
        }
        int currentAdvance = safeToInteger(currentItem.get("advanceMinutes"), 0);
        LocalDateTime currentStart = currentRemindTime.minusMinutes(currentAdvance);
        LocalDateTime currentEnd = currentRemindTime;

        for (Map<String, Object> confirmed : confirmedItems) {
            LocalDateTime confirmedRemindTime;
            try {
                confirmedRemindTime = parseTimeFlexible(String.valueOf(confirmed.get("remindTime")));
            } catch (Exception e) {
                continue;
            }
            int confirmedAdvance = safeToInteger(confirmed.get("advanceMinutes"), 0);
            LocalDateTime confirmedStart = confirmedRemindTime.minusMinutes(confirmedAdvance);
            LocalDateTime confirmedEnd = confirmedRemindTime;

            // 标准区间重叠判定：startA < endB && startB < endA
            if (currentStart.isBefore(confirmedEnd) && confirmedStart.isBefore(currentEnd)) {
                return String.valueOf(confirmed.get("title"));
            }
        }
        return null;
    }

    // 查找有效会话：状态须为 PENDING 且未过期（expiresAt 为 null 表示无超时，如周期提醒/冲突会话）
    private ConfirmationSession findValidSession(String sessionId, Long userId) {
        return confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或已过期"));
    }

    // 构建 Reminder 实体并填充通用字段（状态、执行状态、来源、时区），不执行 save
    private Reminder buildReminderEntity(Long userId, String title, LocalDateTime remindTime,
            LocalDateTime endTime, String priorityStr, String location, String note,
            Integer advanceMinutes, com.dormrepair.remindme.enums.ConfirmStatus confirmStatus) {
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setTitle(title);
        reminder.setRemindTime(remindTime);
        reminder.setEndTime(endTime);
        reminder.setPriority(parsePrioritySafe(priorityStr));
        reminder.setLocation(location);
        reminder.setNote(note);
        reminder.setAdvanceMinutes(advanceMinutes);
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setConfirmStatus(confirmStatus);
        reminder.setExecutionStatus(ExecutionStatus.PENDING);
        reminder.setSource(ReminderSource.USER_INPUT);
        reminder.setTimezone("Asia/Shanghai");
        return reminder;
    }

    // 安全解析优先级字符串：空值或非法值回退为 NORMAL
    private Priority parsePrioritySafe(String priorityStr) {
        if (priorityStr == null || priorityStr.isBlank()) {
            return Priority.NORMAL;
        }
        try {
            return Priority.valueOf(priorityStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的优先级值: {}, 使用默认值 NORMAL", priorityStr);
            return Priority.NORMAL;
        }
    }

    // 根据解析数据创建并保存提醒，默认提前量取用户配置
    private Reminder createReminder(Long userId, Map<String, Object> parsedData, com.dormrepair.remindme.enums.ConfirmStatus confirmStatus) {
        int defaultAdvance = userService.getUserById(userId)
                .map(u -> u.getDefaultAdvanceMinutes())
                .orElse(10);
        Reminder reminder = buildReminderEntity(
                userId,
                (String) parsedData.get("title"),
                parseTimeFlexible((String) parsedData.get("remindTime")),
                parseTimeFlexible((String) parsedData.get("endTime")),
                (String) parsedData.getOrDefault("priority", Priority.NORMAL.name()),
                (String) parsedData.get("location"),
                (String) parsedData.get("note"),
                (Integer) parsedData.getOrDefault("advanceMinutes", defaultAdvance),
                confirmStatus);

        // 生成提醒话术模板（不影响主流程）
        try {
            List<KnowledgeResult> memories = ragService.retrievePrivateKnowledge(
                    userId,
                    reminder.getTitle(),
                    3
            );

            List<String> memoryContents = memories.stream()
                    .map(KnowledgeResult::getContent)
                    .collect(Collectors.toList());

            String template = llmService.generateReminderTemplate(
                    reminder.getTitle(),
                    memoryContents
            );

            if (template != null && !template.isBlank()) {
                reminder.setNoteTemplate(template);
                log.info("生成提醒模板成功: title={}, template={}", reminder.getTitle(), template);
            }
        } catch (Exception e) {
            log.warn("生成提醒模板失败，不影响主流程: title={}, error={}", reminder.getTitle(), e.getMessage());
        }

        return reminderRepository.save(reminder);
    }

    // 构建阶段一确认卡片：组装数据与按钮，按周期/冲突/普通场景选择不同文案和操作集
    private ConfirmationCardResponse buildConfirmationCard(ConfirmationSession session, Map<String, Object> parsedData) {
        // 兼容 Boolean / String "true" / 数字 1 等多种来源的布尔值
        boolean hasConflict = safeToBoolean(parsedData.get("hasConflict"), false);
        boolean isRecurring = safeToBoolean(parsedData.get("isRecurring"), false);

        Long userId = session.getUserId();
        int defaultAdvance = userService.getUserById(userId)
                .map(u -> u.getDefaultAdvanceMinutes())
                .orElse(10);

        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .title((String) parsedData.get("title"))
                .remindTime((String) parsedData.get("remindTime"))
                .endTime((String) parsedData.get("endTime"))
                .priority((String) parsedData.getOrDefault("priority", Priority.NORMAL.name()))
                .location((String) parsedData.get("location"))
                .note((String) parsedData.get("note"))
                .advanceMinutes(safeToInteger(parsedData.get("advanceMinutes"), defaultAdvance))
                .confidence(safeToDouble(parsedData.get("confidence"), 0.95))
                .hasConflict(hasConflict)
                .conflictMessage((String) parsedData.get("conflictMessage"))
                .conflictId(parsedData.get("conflictId") != null ? Long.valueOf(String.valueOf(parsedData.get("conflictId"))) : null)
                .suggestedTime((String) parsedData.get("suggestedTime"))
                .isRecurring(isRecurring)
                .startDate((String) parsedData.get("startDate"))
                .endDate((String) parsedData.get("endDate"))
                .recurringRemindTime((String) parsedData.get("recurringRemindTime"))
                .repeatType((String) parsedData.get("repeatType"))
                .build();

        log.info("buildConfirmationCard: sessionId={}, title={}, remindTime={}, location={}, note={}, parsedData.keys={}",
                session.getSessionId(), data.getTitle(), data.getRemindTime(), data.getLocation(), data.getNote(),
                parsedData.keySet());

        // 将 parsedData 中的 conflictList 转换为 List<ConflictResponse.ConflictItem>
        if (hasConflict) {
            data.setConflictList(parseConflictList(parsedData.get("conflictList")));
        }

        // 周期提醒：显示日期范围和总天数
        Object tdForLog = parsedData.get("totalDays");
        log.info("周期卡片分支判定: isRecurring={}, raw_isRecurring_type={}, totalDays={}, totalDays_type={}, startDate={}, endDate={}, recurringRemindTime={}, repeatType={}",
                isRecurring,
                parsedData.get("isRecurring") == null ? "null" : parsedData.get("isRecurring").getClass().getName(),
                tdForLog,
                tdForLog == null ? "null" : tdForLog.getClass().getName(),
                parsedData.get("startDate"),
                parsedData.get("endDate"),
                parsedData.get("recurringRemindTime"),
                parsedData.get("repeatType"));
        if (isRecurring) {
            // 构建显示标题（复用公共方法）
            String originalTitle = (String) parsedData.get("title");
            String repeatType = (String) parsedData.getOrDefault("repeatType", RepeatType.DAILY.name());
            String displayTitle = buildRecurringDisplayTitle(originalTitle, repeatType);
            data.setTitle(displayTitle);

            // 构建显示时间（复用公共方法）：优先 recurringRemindTime，兜底从 remindTime 截取
            String remindTime = (String) parsedData.get("recurringRemindTime");
            if (remindTime == null) {
                String fullTime = (String) parsedData.get("remindTime");
                if (fullTime != null && fullTime.length() > 10) {
                    remindTime = fullTime.substring(11);
                }
            }
            String startDate = (String) parsedData.get("startDate");
            String endDate = (String) parsedData.get("endDate");
            Object totalDaysObj = parsedData.get("totalDays");
            String repeatType2 = (String) parsedData.getOrDefault("repeatType", RepeatType.DAILY.name());
            String displayTime = buildRecurringDisplayTime(remindTime, startDate, endDate, totalDaysObj, repeatType2);
            if (displayTime != null) {
                data.setRemindTime(displayTime);
            }
        }

        // 冲突列表已在上文通过 parseConflictList 设置为 List<ConflictItem>，此处无需重复处理

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
            String repeatType = (String) parsedData.getOrDefault("repeatType", RepeatType.DAILY.name());
            String repeatLabel = switch (repeatType) {
                case "WEEKLY" -> "每周";
                case "MONTHLY" -> "每月";
                default -> "每天";
            };
            Object totalDaysObj2 = parsedData.get("totalDays");
            Integer totalDays = null;
            if (totalDaysObj2 instanceof Integer) {
                totalDays = (Integer) totalDaysObj2;
            } else if (totalDaysObj2 instanceof Long) {
                totalDays = ((Long) totalDaysObj2).intValue();
            } else if (totalDaysObj2 instanceof Number) {
                totalDays = ((Number) totalDaysObj2).intValue();
            }
            message = String.format("📅 我将为你创建「%s」的%s提醒%s，请确认：",
                    parsedData.get("title"),
                    repeatLabel,
                    totalDays != null ? String.format("（共%d天）", totalDays) : "");
            actions = Arrays.asList(
                    Action.builder().id("confirm").label("✅ 确认").style("primary").build(),
                    Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                    Action.builder().id("cancel").label("❌ 取消").style("danger").build()
            );
        } else if (hasConflict) {
            // 有冲突时：确认（主按钮）+ 覆盖 + 修改 + 取消
            message = "⚠️ " + parsedData.get("conflictMessage");
            actions = Arrays.asList(
                    Action.builder().id("confirm").label("✅ 确认").style("primary").build(),
                    Action.builder().id("overwrite").label("📝 覆盖").style("warning").build(),
                    Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                    Action.builder().id("cancel").label("❌ 取消").style("danger").build()
            );
        } else {
            message = "📋 我帮你解析了提醒信息，请确认：";
            actions = Arrays.asList(
                    Action.builder().id("confirm").label("✅ 确认").style("primary").build(),
                    Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                    Action.builder().id("cancel").label("❌ 取消").style("danger").build()
            );
        }

        // 周期/冲突会话无超时，单次会话有30秒超时
        Integer timeout = (session.getExpiresAt() != null) ? TIMEOUT_SECONDS : null;
        String expiresAtStr = (session.getExpiresAt() != null) ? session.getExpiresAt().format(ISO_FORMATTER) : null;

        return ConfirmationCardResponse.builder()
                .type("confirmation_card")
                .message(message)
                .data(data)
                .actions(actions)
                .timeout(timeout)
                .expiresAt(expiresAtStr)
                .build();
    }

    private void pushConfirmationCard(Long userId, ConfirmationCardResponse card) {
        log.info("pushConfirmationCard: userId={}, type={}, title={}, note={}, hasConflict={}, dataKeys={}",
                userId, card.getType(), card.getData() != null ? card.getData().getTitle() : null,
                card.getData() != null ? card.getData().getNote() : null,
                card.getData() != null ? card.getData().getHasConflict() : null,
                card.getData() != null ? card.getData().getClass().getSimpleName() : "null");
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

    // Jackson JSON 反序列化整数字段时可能返回 Integer 或 Long，统一用 Number 接收并取 intValue
    private Integer safeToInteger(Object obj, Integer defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Long) return ((Long) obj).intValue();
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // Jackson JSON 反序列浮点字段时可能返回 Double 或 Integer，统一用 Number 接收并取 doubleValue
    private Double safeToDouble(Object obj, Double defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Double) return (Double) obj;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(obj));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // JSON 布尔字段可能是 Boolean / String（"true"/"false"）/ 数字（0/1），统一做一次兼容判断
    private boolean safeToBoolean(Object obj, boolean defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof Number) return ((Number) obj).intValue() != 0;
        String s = String.valueOf(obj).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) return false;
        return defaultValue;
    }

    public Optional<ConfirmationSession> findPendingSession(Long userId) {
        return confirmationRepository.findByUserIdAndStatus(userId, SessionStatus.PENDING)
                .stream()
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(LocalDateTime.now()))
                .findFirst();
    }

    // 阶段二提醒执行卡片推送入口（无天气建议）
    @Transactional(rollbackFor = Exception.class)
    public void pushExecuteCard(Reminder reminder) {
        pushExecuteCard(reminder, null);
    }

    // 阶段二提醒执行卡片：创建执行会话并推送，可携带天气建议
    @Transactional(rollbackFor = Exception.class)
    public void pushExecuteCard(Reminder reminder, String weatherSuggestion) {
        // 清理旧的 PENDING 执行会话，防止会话泄漏
        confirmationRepository.expireOldExecuteSessions(reminder.getId());

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
        parsedData.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));
        parsedData.put("location", reminder.getLocation());
        parsedData.put("note", reminder.getNote());
        parsedData.put("priority", reminder.getPriority().name());
        parsedData.put("advanceMinutes", reminder.getAdvanceMinutes());
        parsedData.put("attempt", reminder.getConfirmAttempts());
        parsedData.put("maxAttempts", 3);
        parsedData.put("weatherSuggestion", weatherSuggestion);

        ConfirmationCardResponse card = buildExecuteCard(session, parsedData);
        // 执行提醒走消息通道（通知栏），而非确认通道（创建卡片）
        webSocketPushService.pushToUser(reminder.getUserId(), "/queue/messages", card);

        log.info("推送阶段二提醒执行卡片: sessionId={}, reminderId={}, userId={}, weatherSuggestion={}", 
                sessionId, reminder.getId(), reminder.getUserId(), weatherSuggestion);
    }

    // 阶段二-稍后提醒：推迟指定时间后由调度器重新推送，不触发自动确认
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleExecuteLater(String sessionId, Long userId) {
        ConfirmationSession session = findExecuteSession(sessionId, userId);

        Reminder reminder = reminderRepository.findById(session.getReminderId())
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在"));

        // 更新 reminder：status 重置为 PENDING 让调度器重新扫描，lastConfirmRemindAt 记录推迟时间用于间隔控制
        reminder.setLastConfirmRemindAt(LocalDateTime.now());
        reminder.setStatus(ReminderStatus.PENDING);
        reminderRepository.save(reminder);

        // 递增会话尝试次数用于日志追踪，不设置过期时间，不触发自动确认
        session.setAttemptCount(session.getAttemptCount() + 1);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "⏰ 已推迟，" + session.getIntervalMinutes() + "分钟后再次提醒");
        log.info("阶段二稍后: sessionId={}, reminderId={}, intervalMinutes={}",
                sessionId, reminder.getId(), session.getIntervalMinutes());

        return result;
    }

    // 阶段二-确认执行：标记提醒为已确认并已发送，结束会话
    @Transactional(rollbackFor = Exception.class)
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

    // 阶段二-取消：取消提醒并结束执行会话
    @Transactional(rollbackFor = Exception.class)
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

    // 查找阶段二执行会话：状态 PENDING 且处于 REMINDER_EXECUTE 阶段
    private ConfirmationSession findExecuteSession(String sessionId, Long userId) {
        return confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> "REMINDER_EXECUTE".equals(s.getStage()))
                .orElseThrow(() -> new IllegalArgumentException("执行会话不存在或已过期"));
    }

    // 构建阶段二执行卡片：携带提醒信息、天气建议与确认/稍后/取消按钮
    private ConfirmationCardResponse buildExecuteCard(ConfirmationSession session, Map<String, Object> parsedData) {
        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .title((String) parsedData.get("title"))
                .remindTime((String) parsedData.get("remindTime"))
                .priority((String) parsedData.get("priority"))
                .location((String) parsedData.get("location"))
                .note((String) parsedData.get("note"))
                .advanceMinutes((Integer) parsedData.get("advanceMinutes"))
                .weatherSuggestion((String) parsedData.get("weatherSuggestion"))
                .build();

        String message = "";
        String weatherSuggestion = (String) parsedData.get("weatherSuggestion");
        if (weatherSuggestion != null && !weatherSuggestion.isBlank()) {
            message = weatherSuggestion;
        } else {
            String title = (String) parsedData.get("title");
            if (title != null && !title.isBlank()) {
                message = title;
            }
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

    // 获取会话数据：返回标题、时间、地点及周期提醒、冲突等扩展字段
    public Map<String, Object> getSessionData(String sessionId) {
        return confirmationRepository.findBySessionId(sessionId)
                .map(session -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("sessionId", session.getSessionId());
                    data.put("title", session.getParsedTitle());
                    data.put("remindTime", session.getParsedTime() != null ? session.getParsedTime().format(TimeUtils.FORMATTER) : null);
                    data.put("location", session.getParsedLocation());
                    data.put("priority", session.getParsedPriority());
                    data.put("advanceMinutes", session.getParsedAdvanceMinutes());
                    // 从 parsedData 中提取建议时间
                    Map<String, Object> parsedData = parseParsedData(session.getParsedData());
                    data.put("note", parsedData.get("note"));
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
                    // 补充超时时间和截止时间
                    if (session.getExpiresAt() != null) {
                        data.put("expiresAt", session.getExpiresAt().format(ISO_FORMATTER));
                        data.put("timeout", (int) java.time.Duration.between(java.time.LocalDateTime.now(), session.getExpiresAt()).getSeconds());
                    } else {
                        data.put("expiresAt", null);
                        data.put("timeout", TIMEOUT_SECONDS);
                    }

                    // 周期提醒：用公共方法覆盖 title 和 remindTime 为显示格式，保证 fetchCardData 后卡片仍为周期格式
                    boolean isRecurring = safeToBoolean(parsedData.get("isRecurring"), false);
                    if (isRecurring) {
                        // 1. 构建显示标题
                        String originalTitle = session.getParsedTitle();
                        String repeatType = (String) parsedData.getOrDefault("repeatType", "DAILY");
                        String displayTitle = buildRecurringDisplayTitle(originalTitle, repeatType);
                        data.put("title", displayTitle);

                        // 2. 构建显示时间：优先 recurringRemindTime，兜底从 remindTime 截取
                        String remindTime = (String) parsedData.get("recurringRemindTime");
                        if (remindTime == null) {
                            String fullTime = (String) parsedData.get("remindTime");
                            if (fullTime != null && fullTime.length() > 10) {
                                remindTime = fullTime.substring(11);
                            }
                        }
                        String startDate = (String) parsedData.get("startDate");
                        String endDate = (String) parsedData.get("endDate");
                        Object totalDaysObj = parsedData.get("totalDays");
                        String displayTime = buildRecurringDisplayTime(remindTime, startDate, endDate, totalDaysObj, repeatType);
                        if (displayTime != null) {
                            data.put("remindTime", displayTime);
                        }
                        // displayTime 为 null 时保留原 remindTime，避免显示空白
                    }

                    // 多项事务：填充 multiItems 与统计字段，供前端 fetchCardData 刷新卡片
                    boolean isMulti = safeToBoolean(parsedData.get("isMulti"), false);
                    if (isMulti) {
                        List<Map<String, Object>> multiItems = getMultiItems(parsedData);
                        int[] stats = countMultiItemStats(multiItems);
                        data.put("isMulti", true);
                        data.put("multiItems", buildMultiItemList(multiItems));
                        data.put("totalCount", multiItems.size());
                        data.put("confirmedCount", stats[0]);
                        data.put("cancelledCount", stats[1]);
                        data.put("pendingCount", multiItems.size() - stats[0] - stats[1]);
                        data.put("conflictCount", stats[2]);
                    }
                    return data;
                })
                .orElse(new HashMap<>());
    }

    // 构建重复确认响应：会话已被确认时返回，提示无需重复操作
    private Map<String, Object> buildDuplicateResponse() {
        Map<String, Object> dup = new HashMap<>();
        dup.put("success", true);
        dup.put("isDuplicate", true);
        dup.put("message", "该提醒已确认，无需重复操作～");
        return dup;
    }

    // 构建确认成功消息（无覆盖标题）
    private String buildConfirmSuccessMessage(Reminder reminder) {
        return buildConfirmSuccessMessage(reminder, null);
    }

    // 构建确认成功消息，覆盖场景会额外说明被覆盖的提醒标题
    private String buildConfirmSuccessMessage(Reminder reminder, String overwrittenTitle) {
        String title = reminder.getTitle();
        String remindTime = reminder.getRemindTime().format(TimeUtils.DATETIME_MINUTE_FORMATTER);
        if (overwrittenTitle != null && !overwrittenTitle.isBlank()) {
            return "✅ 已覆盖「" + overwrittenTitle + "」，新提醒\"" + title + "\"已创建\n" +
                   "⏰ 我将在 " + remindTime + " 提醒您";
        }
        return "✅ 已创建提醒：\"" + title + "\"，" + remindTime + " 准时提醒你～";
    }

    // 构建超时自动确认消息，提示用户因未及时确认已自动创建提醒
    private String buildAutoConfirmMessage(Reminder reminder) {
        String title = reminder.getTitle();
        String remindTime = reminder.getRemindTime().format(TimeUtils.DATETIME_MINUTE_FORMATTER);
        return "⏳ 5分钟未确认，已自动确认提醒：\"" + title + "\"，" + remindTime + " 执行\n" +
               "⏰ 我将在 " + remindTime + " 提醒您" + title;
    }

    // 查询用户所有阶段一待确认会话（未过期、按创建时间排序）
    public List<ConfirmationSession> getPendingSessions(Long userId) {
        return confirmationRepository.findByUserIdAndStatus(userId, SessionStatus.PENDING)
                .stream()
                .filter(s -> "INFO_CONFIRM".equals(s.getStage()))
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(LocalDateTime.now()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    // 取最近一条阶段一待确认会话，无则返回 null
    public ConfirmationSession getLatestPendingSession(Long userId) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            return null;
        }
        return sessions.get(sessions.size() - 1);
    }

    // 按标题关键词确认行程：先查阶段一待确认会话，找不到再查阶段二待执行提醒
    @Transactional(rollbackFor = Exception.class)
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
            throw new InvalidInputException("当前没有待确认的行程");
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
                targetReminder.getRemindTime().format(TimeUtils.FORMATTER) : null);
        pushUserMessage(userId, "card_confirmed", data);
        
        return targetReminder;
    }

    // 按标题关键词取消阶段一待确认会话，成功返回 true
    @Transactional(rollbackFor = Exception.class)
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

    // 一键确认所有阶段一待确认会话，批量创建提醒并推送汇总消息
    @Transactional(rollbackFor = Exception.class)
    public List<Reminder> confirmAll(Long userId) {
        List<ConfirmationSession> sessions = getPendingSessions(userId);
        if (sessions.isEmpty()) {
            throw new InvalidInputException("当前没有待确认的行程");
        }

        List<Reminder> reminders = sessions.stream()
                .map(this::createReminderFromSession)
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "✅ 已确认全部 " + reminders.size() + " 个提醒");
        result.put("count", reminders.size());
        pushUserMessage(userId, "batch_confirm_result", result);

        return reminders;
    }

    // 一键取消所有阶段一待确认会话，返回取消数量
    @Transactional(rollbackFor = Exception.class)
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
        result.put("message", "❌ 已取消全部 " + sessions.size() + " 个提醒");
        result.put("count", sessions.size());
        pushUserMessage(userId, "batch_cancel_result", result);

        return sessions.size();
    }

    // 按 sessionId 确认单个阶段一会话并创建提醒
    @Transactional(rollbackFor = Exception.class)
    public Reminder confirmSingle(Long userId, String sessionId) {
        ConfirmationSession session = confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> "INFO_CONFIRM".equals(s.getStage()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或已过期"));

        return createReminderFromSession(session);
    }

    // 按 sessionId 取消单个阶段一会话并推送取消消息
    @Transactional(rollbackFor = Exception.class)
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
                            session.getParsedTime().format(TimeUtils.FORMATTER) : null);
                    pushUserMessage(userId, "card_cancelled", data);
                    
                    return true;
                })
                .orElse(false);
    }

    // 从会话创建提醒并推送卡片确认消息（手动确认场景）
    private Reminder createReminderFromSession(ConfirmationSession session) {
        Reminder savedReminder = createReminderFromSessionWithoutPush(session);
        
        // 推送卡片确认消息，通知前端关闭卡片（用于手动确认场景）
        pushCardConfirmed(session.getUserId(), session.getSessionId(), 
                session.getParsedTitle(), session.getParsedTime());
        
        return savedReminder;
    }
    
    // 从会话创建提醒但不推送（超时自动确认场景），更新会话为已确认并进入执行阶段
    private Reminder createReminderFromSessionWithoutPush(ConfirmationSession session) {
        // 从 parsedData JSON 中提取 note 字段（ConfirmationSession 未单独建列）
        String note = null;
        try {
            Map<String, Object> parsedData = parseParsedData(session.getParsedData());
            if (parsedData != null) {
                Object noteObj = parsedData.get("note");
                if (noteObj != null) {
                    note = String.valueOf(noteObj);
                }
            }
        } catch (Exception e) {
            log.warn("解析 parsedData 提取 note 失败: sessionId={}, error={}", session.getSessionId(), e.getMessage());
        }
        Reminder reminder = buildReminderEntity(
                session.getUserId(),
                session.getParsedTitle(),
                session.getParsedTime(),
                session.getParsedEndTime(),
                session.getParsedPriority(),
                session.getParsedLocation(),
                note,
                session.getParsedAdvanceMinutes() != null ? session.getParsedAdvanceMinutes() : 10,
                ConfirmStatus.CONFIRMED);

        Reminder savedReminder = reminderRepository.save(reminder);

        session.setStatus(SessionStatus.CONFIRMED);
        session.setStage("REMINDER_EXECUTE");
        session.setReminderId(savedReminder.getId());
        confirmationRepository.save(session);

        log.info("从会话创建提醒(不推送): sessionId={}, userId={}, reminderId={}, title={}", 
                session.getSessionId(), session.getUserId(), savedReminder.getId(), session.getParsedTitle());
        
        return savedReminder;
    }

    // 推送卡片确认消息，通知关闭对应卡片
    private void pushCardConfirmed(Long userId, String sessionId, String title, LocalDateTime remindTime) {
        log.info("pushCardConfirmed 被调用: userId={}, sessionId={}", userId, sessionId);
        if (userId == null || sessionId == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("title", title);
        data.put("remindTime", remindTime != null ? remindTime.format(TimeUtils.FORMATTER) : null);
        pushUserMessage(userId, "card_confirmed", data);
    }

    // 推送单条待确认卡片，按是否今天调整时间显示格式
    public void pushSingleConfirmCard(Long userId, ConfirmationSession session) {
        Map<String, Object> cardData = new HashMap<>();
        cardData.put("type", "single_confirm_card");
        cardData.put("sessionId", session.getSessionId());
        cardData.put("title", session.getParsedTitle());
        
        String timeStr;
        if (session.getParsedTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            if (session.getParsedTime().toLocalDate().equals(now.toLocalDate())) {
                timeStr = session.getParsedTime().format(TimeUtils.TIME_FORMATTER);
            } else {
                timeStr = session.getParsedTime().format(TimeUtils.DATETIME_MINUTE_FORMATTER);
            }
        } else {
            timeStr = "未设置";
        }
        cardData.put("remindTime", timeStr);
        cardData.put("location", session.getParsedLocation());
        cardData.put("expiresAt", session.getExpiresAt() != null ? session.getExpiresAt().format(ISO_FORMATTER) : null);

        pushMessage(userId, cardData);
    }

    // 推送多条待确认卡片，列出各会话标题与时间
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
                        timeStr = session.getParsedTime().format(TimeUtils.TIME_FORMATTER);
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

    // 推送待确认行程汇总，按编号列出全部待确认会话
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
                    ? session.getParsedTime().format(TimeUtils.DATETIME_MINUTE_FORMATTER) 
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

    // ========== 周期提醒显示格式公共方法（buildConfirmationCard / getSessionData 共用）==========

    // 安全 Object → int 转换：支持 Integer、Long、Number、String 数字，失败返回 defaultValue
    private int safeToInt(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Long) return ((Long) obj).intValue();
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(String.valueOf(obj).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // 构建周期提醒的显示标题："📅 周期提醒：{originalTitle}（{每天/每周/每月}）"
    private String buildRecurringDisplayTitle(String originalTitle, String repeatType) {
        if (originalTitle == null || originalTitle.isBlank()) {
            return originalTitle;
        }
        String label = switch (repeatType == null ? "" : repeatType) {
            case "WEEKLY" -> "每周";
            case "MONTHLY" -> "每月";
            default -> "每天";
        };
        return "📅 周期提醒：" + originalTitle + "（" + label + "）";
    }

    // 构建周期提醒的显示时间："{每天/每周/每月} HH:mm | {startDate} 至 {endDate} 共X天"
    private String buildRecurringDisplayTime(String remindTime, String startDate, String endDate,
                                              Object totalDaysObj, String repeatType) {
        if (remindTime == null || remindTime.isBlank()
                || startDate == null || startDate.isBlank()
                || endDate == null || endDate.isBlank()) {
            return null;
        }
        // totalDays 安全转换：无法转换或转换后 <=0 都视为非法
        int totalDays = safeToInt(totalDaysObj, -1);
        if (totalDays < 0) {
            return null;
        }
        String label = switch (repeatType == null ? "" : repeatType) {
            case "WEEKLY" -> "每周";
            case "MONTHLY" -> "每月";
            default -> "每天";
        };
        String displayTime = remindTime.length() >= 5 ? remindTime.substring(0, 5) : remindTime;
        return label + " " + displayTime + " | " + startDate + " 至 " + endDate + " 共" + totalDays + "天";
    }

    // 记忆分类：根据记忆文本内容判断类型，用于 Agent 回复润色时决定是否提及
    // ACTION：可执行的行动（带物品、准备物品）；CONSTRAINT：约束条件；PREFERENCE：偏好；IRRELEVANT：无关
    private String classifyMemory(String memory) {
        if (memory == null || memory.isEmpty()) {
            return "IRRELEVANT";
        }

        // ACTION：可执行的行动（带物品、准备物品）
        if (memory.matches(".*(带|准备|会|习惯|总是|每次).*") &&
                memory.matches(".*(茶|水|伞|外套|耳机|书|本子|充电宝|药|帽子|毛巾|背包).*")) {
            return "ACTION";
        }

        // CONSTRAINT：约束条件（不能、注意、不超过）
        if (memory.matches(".*(不|不能|不要|避免|注意|不超过|少于|大于|限制|只能).*")) {
            return "CONSTRAINT";
        }

        // PREFERENCE：偏好（喜欢、讨厌）
        if (memory.matches(".*(喜欢|讨厌|不喜欢|爱|恨|偏好|偏爱).*")) {
            return "PREFERENCE";
        }

        return "IRRELEVANT";
    }
}