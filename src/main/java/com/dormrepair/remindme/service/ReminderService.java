package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.agent.event.EventPublisher;
import com.dormrepair.remindme.dto.request.ReminderCreateRequest;
import com.dormrepair.remindme.dto.request.ReminderUpdateRequest;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.dto.response.ReminderExportDTO;
import com.dormrepair.remindme.dto.response.ReminderResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.exception.InvalidInputException;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// 提醒核心业务服务
// 提供提醒的 CRUD，并通过 7 个 AgentTool 暴露给 Agent 调用；含周期关键词路由逻辑
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final ConfirmationService confirmationService;
    private final EventPublisher eventPublisher;
    private final ConflictDetectionService conflictDetectionService;
    private final RecurringReminderService recurringReminderService;

    // HTTP 层创建提醒入口：校验用户后转交给 Agent 版本处理
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createReminder(ReminderCreateRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        String remindTimeStr = request.getRemindTime().format(TimeUtils.FORMATTER);
        String endTimeStr = request.getEndTime() != null ? request.getEndTime().format(TimeUtils.FORMATTER) : null;
        String priority = request.getPriority() != null ? request.getPriority() : Priority.NORMAL.name();
        String location = request.getLocation();
        String note = request.getNote();

        return createReminderForAgent(
                request.getUserId(),
                request.getTitle(),
                remindTimeStr,
                endTimeStr,
                location,
                note,
                priority,
                null,
                null,  // originalInput：HTTP 表单创建路径无自然语言原始输入，传 null 跳过倒计时检测
                null   // conflictStrategy：表单场景不传，走默认 ASK
        );
    }

    // HTTP 层更新提醒：仅覆盖入参非 null 的字段
    @Transactional(rollbackFor = Exception.class)
    public ReminderResponse updateReminder(Long id, ReminderUpdateRequest request, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);

        if (request.getTitle() != null) {
            reminder.setTitle(request.getTitle());
        }
        if (request.getRemindTime() != null) {
            reminder.setRemindTime(request.getRemindTime());
        }
        if (request.getEndTime() != null) {
            reminder.setEndTime(request.getEndTime());
        }
        if (request.getTimezone() != null) {
            reminder.setTimezone(request.getTimezone());
        }
        if (request.getAdvanceMinutes() != null) {
            reminder.setAdvanceMinutes(request.getAdvanceMinutes());
        }
        if (request.getCronExpression() != null) {
            reminder.setCronExpression(request.getCronExpression());
        }
        if (request.getLocation() != null) {
            reminder.setLocation(request.getLocation());
        }
        if (request.getNote() != null) {
            reminder.setNote(request.getNote());
        }
        if (request.getPriority() != null) {
            reminder.setPriority(Priority.valueOf(request.getPriority()));
        }

        Reminder saved = reminderRepository.save(reminder);
        return toResponse(saved);
    }

    // 取消提醒并发布事件
    @Transactional(rollbackFor = Exception.class)
    public void cancelReminder(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        reminder.setStatus(ReminderStatus.CANCELLED);
        reminderRepository.save(reminder);
        eventPublisher.publishReminderCancelled(userId, id, reminder.getTitle());
    }

    // 查询某用户的全部 PENDING 提醒
    public List<ReminderResponse> getRemindersByUser(Long userId) {
        return reminderRepository.findByUserIdAndStatus(userId, ReminderStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // 确认提醒：状态置为 CONFIRMED 并发布事件
    @Transactional(rollbackFor = Exception.class)
    public ReminderResponse confirmReminder(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
        Reminder saved = reminderRepository.save(reminder);
        eventPublisher.publishReminderConfirmed(userId, id, reminder.getTitle());
        return toResponse(saved);
    }

    // 查询待确认（PENDING 确认状态）的提醒
    public List<ReminderResponse> getPendingReminders(Long userId) {
        return reminderRepository.findByUserIdAndConfirmStatus(userId, ConfirmStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // 导出查询：按用户 + 状态集合 + created_at 日期范围查提醒，返回精简导出 DTO 列表（创建时间升序）
    // startDateStr/endDateStr 可选（yyyy-MM-dd，endDate 含当天）；statusStrs 可选，
    // 不传或为空时默认导出 PENDING/COMPLETED/CANCELLED/EXPIRED 四种状态
    public List<ReminderExportDTO> exportReminders(Long userId, String startDateStr, String endDateStr, List<String> statusStrs) {
        // 日期解析：严格 yyyy-MM-dd，格式错误抛 400
        LocalDate startDate = parseExportDate(startDateStr, "startDate");
        LocalDate endDate = parseExportDate(endDateStr, "endDate");

        // 状态解析与合法性校验：只接受 ReminderStatus 合法枚举值
        List<ReminderStatus> statuses = resolveExportStatuses(statusStrs);

        // 左闭右开区间：endDate 含当天 → 上界取 endDate+1 天零点；不传的边界不限制
        LocalDateTime startTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endTime = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;

        List<Reminder> reminders = reminderRepository.findForExport(userId, statuses, startTime, endTime);

        return reminders.stream()
                .map(r -> new ReminderExportDTO(
                        r.getId(), r.getTitle(), r.getRemindTime(), r.getEndTime(),
                        r.getLocation(), r.getNote(), r.getPriority().name(),
                        r.getStatus().name(), r.getCreatedAt()))
                .collect(Collectors.toList());
    }

    // 解析导出日期参数：空白返回 null（不限制该边界），非 yyyy-MM-dd 抛 InvalidInputException
    private LocalDate parseExportDate(String dateStr, String paramName) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidInputException("参数 " + paramName + " 格式错误，应为 yyyy-MM-dd：" + dateStr);
        }
    }

    // 解析导出状态参数：全空白默认四种导出状态；含非法值抛 InvalidInputException
    private List<ReminderStatus> resolveExportStatuses(List<String> statusStrs) {
        if (statusStrs == null || statusStrs.isEmpty()
                || statusStrs.stream().allMatch(s -> s == null || s.isBlank())) {
            return List.of(ReminderStatus.PENDING, ReminderStatus.COMPLETED,
                    ReminderStatus.CANCELLED, ReminderStatus.EXPIRED);
        }
        return statusStrs.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(s -> {
                    try {
                        return ReminderStatus.valueOf(s);
                    } catch (IllegalArgumentException e) {
                        throw new InvalidInputException("非法的状态值：" + s
                                + "，可选值：PENDING/REMINDED/SENT/COMPLETED/CANCELLED/EXPIRED");
                    }
                })
                .collect(Collectors.toList());
    }

    // Agent 工具：导出提醒
    // 触发前端下载导出文件；只回传导出信号与条数，不回传明细数据（避免提醒全量数据占用 LLM 上下文）
    // 前端识别 action 标记后，再调 /api/reminders/export 取数生成文件
    // userId 用户ID；startDate/endDate 导出范围（按 created_at 筛选，yyyy-MM-dd，可选，默认全部）
    @AgentTool(name = "export_reminders", description = "导出提醒清单为文件，用户要求导出/下载提醒时调用")
    public Map<String, Object> exportRemindersForAgent(Long userId, String startDate, String endDate) {
        log.info("Agent调用导出提醒: userId={}, startDate={}, endDate={}", userId, startDate, endDate);

        Map<String, Object> result = new HashMap<>();
        try {
            // 复用导出查询（状态固定四种：待推送/已完成/已取消/已过期），这里只需要条数
            List<ReminderExportDTO> data = exportReminders(userId, startDate, endDate, null);

            result.put("success", true);
            result.put("action", "export_reminders");   // 前端识别标记
            result.put("startDate", startDate);          // 原样回传，供前端二次取数
            result.put("endDate", endDate);
            result.put("count", data.size());
            result.put("downloadHint", "excel");         // 默认 Excel 格式
            result.put("message", "已为你导出 " + data.size() + " 条提醒");
            log.info("Agent导出提醒成功: userId={}, count={}", userId, data.size());
        } catch (InvalidInputException e) {
            // 参数校验失败（如日期格式错误），交给 LLM 降级回复
            result.put("success", false);
            result.put("error", e.getMessage());
        } catch (Exception e) {
            log.error("Agent导出提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "导出提醒失败，请稍后再试");
        }
        return result;
    }

    // 按 id 查询单条提醒完整详情，附带所属用户校验；不存在或越权时抛 IllegalArgumentException
    public ReminderResponse getReminderDetailById(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        return toResponse(reminder);
    }

    // 检测冲突（委托给 ConflictDetectionService）
    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime) {
        return conflictDetectionService.checkConflict(userId, remindTime, null);
    }

    // 按主键查提醒，附带所属用户校验
    private Reminder getReminderByIdAndUserId(Long id, Long userId) {
        return reminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在或无权操作"));
    }

    // 实体转响应 DTO，逐字段映射
    private ReminderResponse toResponse(Reminder reminder) {
        ReminderResponse response = new ReminderResponse();
        response.setId(reminder.getId());
        response.setUserId(reminder.getUserId());
        response.setTitle(reminder.getTitle());
        response.setRemindTime(reminder.getRemindTime());
        response.setEndTime(reminder.getEndTime());
        response.setTimezone(reminder.getTimezone());
        response.setAdvanceMinutes(reminder.getAdvanceMinutes());
        response.setCronExpression(reminder.getCronExpression());
        response.setLocation(reminder.getLocation());
        response.setNote(reminder.getNote());
        response.setPriority(reminder.getPriority().name());
        response.setStatus(reminder.getStatus().name());
        response.setConfirmStatus(reminder.getConfirmStatus().name());
        response.setExecutionStatus(reminder.getExecutionStatus().name());
        response.setConfirmAttempts(reminder.getConfirmAttempts());
        response.setLastConfirmRemindAt(reminder.getLastConfirmRemindAt());
        response.setSource(reminder.getSource().name());
        response.setCreatedAt(reminder.getCreatedAt());
        response.setUpdatedAt(reminder.getUpdatedAt());
        response.setParentRecurringId(reminder.getParentRecurringId());
        return response;
    }

    // Agent 工具：创建提醒
    // 触发阶段一确认流程；标题命中周期关键词时路由到周期提醒
    // userId 用户ID；title 标题；remindTime 提醒时间（yyyy-MM-dd HH:mm:ss）
    // endTime 结束时间（可选）；location 地点（可选）；note 备注（可选）；priority 优先级（LOW/NORMAL/HIGH，默认 NORMAL）
    // advanceMinutes 提前提醒分钟数（可选）。相对时间（如'30分钟后'）传0准时触发，'等会/马上'与明确时间（如'明天下午3点'）不传。
    @AgentTool(name = "create_reminder", description = "创建提醒，用户要求设置提醒时调用。"
            + "advanceMinutes: 提前提醒分钟数（可选）。相对时间（如'30分钟后'）传0准时触发；'等会/马上'与明确时间（如'明天下午3点'）不传。"
            + "conflictStrategy: 冲突处理策略。ASK（默认，冲突时推卡片询问用户）/OVERWRITE（覆盖已有冲突提醒）/ALLOW_COEXIST（同时保留，与现有日程并存）。"
            + "注意：单条提醒不需要 SKIP，跳过等同于用户点'取消'。")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createReminderForAgent(Long userId, String title, String remindTime,
            String endTime, String location, String note, String priority, Integer advanceMinutes,
            String originalInput, String conflictStrategy) {

        log.info("Agent调用创建提醒: userId={}, title={}, remindTime={}, endTime={}, location={}, note={}, priority={}, advanceMinutes={}, originalInput={}",
                userId, title, remindTime, endTime, location, note, priority, advanceMinutes, originalInput);

        // 倒计时场景检测：用户说"提醒我"/"提醒下我"/"等会提醒"/"等下提醒"时，应准时触发（advanceMinutes=0）
        // 区分"30分钟后提醒我"（倒计时，准时触发）与"30分钟后我去开会"（事件预告，提前提醒）
        boolean isCountdown = false;
        if (originalInput != null && !originalInput.isBlank()) {
            String lowerInput = originalInput.toLowerCase();
            if (lowerInput.contains("提醒我") || lowerInput.contains("提醒下我")
                    || lowerInput.contains("等会提醒") || lowerInput.contains("等下提醒")) {
                isCountdown = true;
                log.info("检测到倒计时场景(准时触发): originalInput={}", originalInput);
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 周期识别：检查标题是否包含周期关键词
            RecurringParams recurringParams = detectDateRange(title, remindTime);
            if (recurringParams != null) {
                log.info("检测到日期范围，路由到周期提醒: startDate={}, endDate={}, repeatType={}, repeatDays={}",
                        recurringParams.startDate, recurringParams.endDate, recurringParams.repeatType, recurringParams.repeatDays);
                return recurringReminderService.createRecurringReminder(
                        userId, title, recurringParams.remindTime,
                        recurringParams.startDate, recurringParams.endDate,
                        recurringParams.repeatType, recurringParams.repeatDays,
                        location, priority != null ? priority : Priority.NORMAL.name(), null);
            }

            // 解析时间
            LocalDateTime parsedTime;
            int finalAdvance;

            // 相对时间检测：优先解析 "X分钟后" 等相对时间表达，避免 diffMinutes=0 误判
            LocalDateTime relativeTime = parseRelativeTimeInRemindTime(remindTime);
            if (relativeTime != null) {
                parsedTime = relativeTime;
                remindTime = parsedTime.format(TimeUtils.FORMATTER);
                // 相对时间准时触发，提前量置 0
                long relDiff = java.time.Duration.between(TimeUtils.now(), parsedTime).toMinutes();
                finalAdvance = 0;
                log.info("相对时间解析成功(准时触发): remindTime={}, parsedTime={}, 距触发{}分钟, advanceMinutes=0", remindTime, parsedTime, relDiff);
            } else {
                // 原有逻辑：解析绝对时间，通过 diffMinutes 判断语义
                parsedTime = LocalDateTime.parse(remindTime, TimeUtils.FORMATTER);

                int defaultAdvance = userRepository.findById(userId)
                        .map(User::getDefaultAdvanceMinutes)
                        .orElse(10);

                LocalDateTime now = TimeUtils.now();
                long diffMinutes = java.time.Duration.between(now, parsedTime).toMinutes();

                if (advanceMinutes != null && advanceMinutes > 0) {
                    // LLM 明确传入 advanceMinutes > 0 → 保留 parsedTime 原值，直接使用
                    finalAdvance = advanceMinutes;
                    log.info("使用LLM传入的advanceMinutes={}, parsedTime保持不变={}", advanceMinutes, parsedTime);
                } else if (advanceMinutes != null && advanceMinutes == 0) {
                    if (diffMinutes > 0) {
                        // 相对时间已折算为未来时间 → 倒计时准时触发，保留原时间
                        finalAdvance = 0;
                        log.info("相对时间准时触发: 距现在{}分钟, parsedTime={}", diffMinutes, parsedTime);
                    } else {
                        // "现在/马上"等模糊时间 → 按默认提前量安排临近时间
                        parsedTime = now.plusMinutes(defaultAdvance);
                        remindTime = parsedTime.format(TimeUtils.FORMATTER);
                        finalAdvance = defaultAdvance;
                        log.info("advanceMinutes=0(现在/马上), 使用默认提前量={}", defaultAdvance);
                    }
                } else if (diffMinutes == 0 || (diffMinutes > -1 && diffMinutes < 0)) {
                    // 未传 advanceMinutes，用户说"等会"/"等一下" → 使用默认提前量
                    parsedTime = now.plusMinutes(defaultAdvance);
                    remindTime = parsedTime.format(TimeUtils.FORMATTER);
                    finalAdvance = defaultAdvance;
                } else {
                    // 明确指定的时间（如 18:30、明天下午3点）→ 使用默认提前量
                    finalAdvance = defaultAdvance;
                }
            }

            // 倒计时场景覆盖：用户说"提醒我"且 LLM 未显式传入 advanceMinutes 时，强制为 0 确保准时触发
            // 保护条件：LLM 已提取到 advanceMinutes > 0（如"提前两小时四十分钟"）→ 尊重用户明确的提前意图，不清零
            // 典型场景区分：
            //   "30分钟后提醒我"  → advanceMinutes=null  → 倒计时覆盖，advance=0  ✅
            //   "晚上7点提醒我吃饭，提前2小时40分钟" → advanceMinutes=160 → 尊重用户，advance=160  ✅
            if (isCountdown && finalAdvance > 0 && (advanceMinutes == null || advanceMinutes == 0)) {
                log.info("倒计时场景覆盖: finalAdvance {} -> 0 (用户说'提醒我'且无显式提前量, 准时触发)", finalAdvance);
                finalAdvance = 0;
            }

            LocalDateTime parsedEndTime = endTime != null ? LocalDateTime.parse(endTime, TimeUtils.FORMATTER) : null;
            
            // 构建确认会话的解析数据
            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("title", title);
            parsedData.put("remindTime", remindTime);
            parsedData.put("location", location);
            parsedData.put("note", note);
            parsedData.put("priority", priority != null ? priority : Priority.NORMAL.name());
            parsedData.put("advanceMinutes", finalAdvance);
            parsedData.put("source", "USER_INPUT");
            parsedData.put("intentType", "REMINDER");
            
            // 添加 endTime 到 parsedData
            if (parsedEndTime != null) {
                parsedData.put("endTime", endTime);
            }
            
            // 冲突检测（支持时间段重叠检测）
            ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, parsedEndTime, null);
            boolean hasConflict = conflictResponse.isHasConflict();
            // 创建成功后的提示文案，根据冲突处理策略调整
            String createMessage = "提醒已创建 ✅";

            if (hasConflict) {
                String description = conflictDetectionService.getConflictDescription(
                        conflictResponse.getConflicts(), parsedTime);

                parsedData.put("hasConflict", true);
                parsedData.put("conflictMessage", description);
                parsedData.put("conflictList", conflictResponse.getConflicts());
                parsedData.put("suggestedTime", conflictResponse.getSuggestedTime() != null
                        ? conflictResponse.getSuggestedTime().format(TimeUtils.FORMATTER) : null);

                // 取第一个冲突提醒的 ID，便于后续覆盖
                if (conflictResponse.getConflicts() != null && !conflictResponse.getConflicts().isEmpty()) {
                    parsedData.put("conflictId", conflictResponse.getConflicts().get(0).getId());
                }

                log.info("Agent创建提醒检测到冲突: userId={}, title={}, conflicts={}, strategy={}",
                        userId, title, conflictResponse.getConflicts().size(), conflictStrategy);

                // 冲突处理策略分发
                String strategy = conflictStrategy != null ? conflictStrategy.toUpperCase() : "ASK";
                switch (strategy) {
                    case "OVERWRITE":
                        // 覆盖：将所有冲突提醒置为 CANCELLED，再创建新提醒
                        if (conflictResponse.getConflicts() != null) {
                            for (ConflictResponse.ConflictItem item : conflictResponse.getConflicts()) {
                                Reminder conflictReminder = reminderRepository.findById(item.getId()).orElse(null);
                                if (conflictReminder != null) {
                                    conflictReminder.setStatus(ReminderStatus.CANCELLED);
                                    reminderRepository.save(conflictReminder);
                                    log.info("冲突覆盖：已取消冲突提醒 reminderId={}, title={}", item.getId(), item.getTitle());
                                }
                            }
                        }
                        createMessage = "已覆盖冲突提醒并创建";
                        break;
                    case "ALLOW_COEXIST":
                        // 同时保留：不删除冲突提醒，直接创建新提醒
                        createMessage = "已创建提醒（与现有日程并存）";
                        break;
                    case "ASK":
                    default:
                        // 默认 ASK：推冲突卡片，由用户决策
                        ConfirmationCardResponse card = confirmationService.createSession(userId, title, parsedData);
                        result.put("success", true);
                        result.put("sessionId", card.getData().getSessionId());
                        result.put("confirmationRequired", true);
                        result.put("message", "检测到冲突，请选择处理方式");
                        result.put("title", title);
                        result.put("remindTime", remindTime);
                        result.put("location", location);
                        result.put("hasConflict", true);
                        result.put("conflictMessage", description);
                        return result;
                }
            } else {
                parsedData.put("hasConflict", false);
            }
            
            // 直接创建提醒（不再走确认会话，Agent 拥有执行权）
            Reminder reminder = new Reminder();
            reminder.setUserId(userId);
            reminder.setTitle(title);
            reminder.setRemindTime(parsedTime);
            reminder.setEndTime(parsedEndTime);
            reminder.setPriority(priority != null ? Priority.valueOf(priority.toUpperCase()) : Priority.NORMAL);
            reminder.setLocation(location);
            reminder.setNote(note);
            reminder.setAdvanceMinutes(finalAdvance);
            reminder.setStatus(ReminderStatus.PENDING);
            reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
            reminder.setExecutionStatus(ExecutionStatus.PENDING);
            reminder.setSource(ReminderSource.USER_INPUT);
            reminder.setTimezone("Asia/Shanghai");
            Reminder saved = reminderRepository.save(reminder);

            // 发布提醒创建事件
            eventPublisher.publishReminderCreated(userId, saved.getId(), title, remindTime, location, false);

            result.put("success", true);
            result.put("executed", true);
            result.put("reminderId", saved.getId());
            result.put("message", createMessage);
            result.put("title", title);
            result.put("remindTime", remindTime);
            result.put("location", location);
            result.put("note", note);
            result.put("priority", priority != null ? priority : Priority.NORMAL.name());
            result.put("hasConflict", hasConflict);
            // 冲突详情：拼接冲突提醒标题，供 Agent 引导语使用
            if (conflictResponse.isHasConflict() && conflictResponse.getConflicts() != null) {
                String conflictMessage = conflictResponse.getConflicts().stream()
                        .map(ConflictResponse.ConflictItem::getTitle)
                        .collect(Collectors.joining("、"));
                result.put("conflictMessage", "与「" + conflictMessage + "」时间冲突");

                List<Map<String, Object>> conflictDetails = conflictResponse.getConflicts().stream()
                        .map(item -> {
                            Map<String, Object> detail = new HashMap<>();
                            detail.put("id", item.getId());
                            detail.put("title", item.getTitle());
                            detail.put("remindTime", item.getRemindTime() != null
                                    ? item.getRemindTime().format(TimeUtils.FORMATTER) : null);
                            detail.put("isRecurringInstance", item.getIsRecurringInstance());
                            detail.put("parentRecurringId", item.getParentRecurringId());
                            return detail;
                        })
                        .collect(Collectors.toList());
                result.put("conflictDetails", conflictDetails);
            }
            result.put("taskCompleted", true);

            log.info("Agent创建提醒成功: title={}, reminderId={}, hasConflict={}",
                    title, saved.getId(), conflictResponse.isHasConflict());
            
        } catch (Exception e) {
            log.error("Agent创建提醒失败: userId={}, title={}, error={}", userId, title, e.getMessage(), e);
            throw new InvalidInputException("创建提醒失败: " + e.getMessage(), e);
        }
        
        return result;
    }

    // Agent 工具：查询提醒
    // 查询指定日期的提醒列表
    // userId 用户ID；date 查询日期（yyyy-MM-dd，为空时取今天）；返回提醒列表
    @AgentTool(name = "query_reminders", description = "查询提醒，用户询问日程安排时调用")
    public Map<String, Object> queryRemindersForAgent(Long userId, String date) {
        log.info("Agent调用查询提醒: userId={}, date={}", userId, date);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 解析日期，默认今天
            LocalDateTime parsedDate;
            if (date != null && !date.isBlank()) {
                parsedDate = LocalDateTime.parse(date + " 00:00:00", TimeUtils.FORMATTER);
            } else {
                parsedDate = TimeUtils.now().withHour(0).withMinute(0).withSecond(0);
            }
            
            LocalDateTime startOfDay = parsedDate;
            LocalDateTime endOfDay = parsedDate.plusDays(1);
            
            // 查询当天的提醒
            List<Reminder> reminders = reminderRepository.findByUserIdAndRemindTimeBetween(userId, startOfDay, endOfDay);
            
            List<Map<String, Object>> reminderList = reminders.stream()
                    .map(r -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", r.getId());
                        item.put("title", r.getTitle());
                        item.put("remindTime", r.getRemindTime().format(TimeUtils.FORMATTER));
                        item.put("location", r.getLocation());
                        item.put("note", r.getNote());
                        item.put("priority", r.getPriority().name());
                        item.put("status", r.getStatus().name());
                        item.put("confirmStatus", r.getConfirmStatus().name());
                        return item;
                    })
                    .collect(Collectors.toList());
            
            result.put("success", true);
            result.put("date", date != null ? date : TimeUtils.now().format(TimeUtils.DATE_FORMATTER));
            result.put("count", reminderList.size());
            result.put("reminders", reminderList);
            
            log.info("Agent查询提醒成功: userId={}, count={}", userId, reminderList.size());
            
        } catch (Exception e) {
            log.error("Agent查询提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "查询提醒失败，请稍后再试。");
        }
        
        return result;
    }

    // Agent 工具：更新提醒
    // 修改指定提醒的信息，仅覆盖入参非空字段
    // userId 用户ID；reminderId 提醒ID；title 标题（可选）
    // remindTime 提醒时间（可选，yyyy-MM-dd HH:mm:ss）；endTime 结束时间（可选）；location 地点（可选）；note 备注（可选）
    @AgentTool(name = "update_reminder", description = "修改提醒，用户要求更改提醒时调用")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateReminderForAgent(Long userId, Long reminderId, String title,
            String remindTime, String endTime, String location, String note) {

        log.info("Agent调用更新提醒: userId={}, reminderId={}, endTime={}", userId, reminderId, endTime);

        Map<String, Object> result = new HashMap<>();

        try {
            Reminder reminder = getReminderByIdAndUserId(reminderId, userId);

            if (title != null && !title.isBlank()) {
                reminder.setTitle(title);
            }
            if (remindTime != null && !remindTime.isBlank()) {
                LocalDateTime parsedTime = LocalDateTime.parse(remindTime, TimeUtils.FORMATTER);
                reminder.setRemindTime(parsedTime);
            }
            if (endTime != null && !endTime.isBlank()) {
                LocalDateTime parsedEndTime = LocalDateTime.parse(endTime, TimeUtils.FORMATTER);
                reminder.setEndTime(parsedEndTime);
            }
            if (location != null) {
                reminder.setLocation(location);
            }
            if (note != null) {
                reminder.setNote(note);
            }

            Reminder saved = reminderRepository.save(reminder);

            result.put("success", true);
            result.put("message", "提醒已更新");
            result.put("id", saved.getId());
            result.put("title", saved.getTitle());
            result.put("remindTime", saved.getRemindTime().format(TimeUtils.FORMATTER));
            if (saved.getEndTime() != null) {
                result.put("endTime", saved.getEndTime().format(TimeUtils.FORMATTER));
            }
            result.put("location", saved.getLocation());
            result.put("note", saved.getNote());
            result.put("priority", saved.getPriority().name());

            log.info("Agent更新提醒成功: reminderId={}", reminderId);
            
        } catch (Exception e) {
            log.error("Agent更新提醒失败: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage(), e);
            throw new InvalidInputException("更新提醒失败: " + e.getMessage(), e);
        }
        
        return result;
    }

    // Agent 工具：取消提醒
    // 将指定提醒状态置为 CANCELLED
    // userId 用户ID；reminderId 提醒ID；返回取消结果
    @AgentTool(name = "cancel_reminder", description = "取消提醒，用户要求删除提醒时调用")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelReminderForAgent(Long userId, Long reminderId) {
        log.info("Agent调用取消提醒: userId={}, reminderId={}", userId, reminderId);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            Reminder reminder = getReminderByIdAndUserId(reminderId, userId);
            String title = reminder.getTitle();
            
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminderRepository.save(reminder);
            
            result.put("success", true);
            result.put("message", "提醒已取消");
            result.put("id", reminderId);
            result.put("title", title);
            
            log.info("Agent取消提醒成功: reminderId={}, title={}", reminderId, title);
            
        } catch (Exception e) {
            log.error("Agent取消提醒失败: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage(), e);
            throw new InvalidInputException("取消提醒失败: " + e.getMessage(), e);
        }

        return result;
    }

    // Agent 工具：获取提醒详情
    // 查询单个提醒的完整信息
    // userId 用户ID；reminderId 提醒ID；返回提醒详情
    @AgentTool(name = "get_reminder_detail", description = "获取单个提醒的完整详情，用户询问'第一个提醒是什么时间'、'具体信息'时调用")
    public Map<String, Object> getReminderDetail(Long userId, Long reminderId) {
        log.info("Agent调用获取提醒详情: userId={}, reminderId={}", userId, reminderId);

        Map<String, Object> result = new HashMap<>();

        try {
            Reminder reminder = getReminderByIdAndUserId(reminderId, userId);

            result.put("success", true);
            result.put("id", reminder.getId());
            result.put("title", reminder.getTitle());
            result.put("remindTime", reminder.getRemindTime().format(TimeUtils.FORMATTER));
            result.put("timezone", reminder.getTimezone());
            result.put("advanceMinutes", reminder.getAdvanceMinutes());
            result.put("cronExpression", reminder.getCronExpression());
            result.put("location", reminder.getLocation());
            result.put("note", reminder.getNote());
            result.put("priority", reminder.getPriority().name());
            result.put("status", reminder.getStatus().name());
            result.put("confirmStatus", reminder.getConfirmStatus().name());
            result.put("executionStatus", reminder.getExecutionStatus().name());
            result.put("confirmAttempts", reminder.getConfirmAttempts());
            result.put("source", reminder.getSource().name());
            result.put("createdAt", reminder.getCreatedAt() != null ? reminder.getCreatedAt().format(TimeUtils.FORMATTER) : null);
            result.put("updatedAt", reminder.getUpdatedAt() != null ? reminder.getUpdatedAt().format(TimeUtils.FORMATTER) : null);

            log.info("Agent获取提醒详情成功: reminderId={}, title={}", reminderId, reminder.getTitle());

        } catch (IllegalArgumentException e) {
            log.warn("Agent获取提醒详情失败: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage());
            result.put("success", false);
            result.put("message", "获取提醒详情失败，请确认提醒是否存在。");
        } catch (Exception e) {
            log.error("Agent获取提醒详情异常: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取提醒详情失败，请稍后再试。");
        }

        return result;
    }

    // Agent 工具：创建周期提醒
    // 先做参数与格式校验，再按 conflictStrategy 分发到周期生成方法
    @AgentTool(name = "create_recurring_reminder", description = "创建周期提醒，用户输入包含日期范围时调用。"
            + "location: 地点（可选）；"
            + "note: 备注/补充说明（可选），如'记得带笔'、'带雨伞'等。"
            + "conflictStrategy: 周期提醒冲突处理策略。"
            + "ASK（默认，有冲突时推一张汇总卡片询问用户）/"
            + "OVERWRITE（覆盖所有冲突日）/"
            + "SKIP（跳过冲突日，创建无冲突部分）/"
            + "ALLOW_COEXIST（全部创建，冲突日并存）")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRecurringReminderForAgent(Long userId, String title,
            String remindTime, String startDate, String endDate, String repeatType, String repeatDays,
            String location, String note, String conflictStrategy) {

        log.info("Agent调用创建周期提醒: userId={}, title={}, remindTime={}, startDate={}, endDate={}, repeatType={}, repeatDays={}, location={}, note={}, conflictStrategy={}",
                userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, note, conflictStrategy);

        Map<String, Object> result = new HashMap<>();

        try {
            if (title == null || title.isBlank()) {
                result.put("success", false);
                result.put("error", "标题不能为空");
                return result;
            }
            if (remindTime == null || remindTime.isBlank()) {
                result.put("success", false);
                result.put("error", "提醒时间不能为空");
                return result;
            }
            if (startDate == null || startDate.isBlank()) {
                result.put("success", false);
                result.put("error", "开始日期不能为空");
                return result;
            }
            if (endDate == null || endDate.isBlank()) {
                result.put("success", false);
                result.put("error", "结束日期不能为空");
                return result;
            }

            if (!remindTime.matches("\\d{2}:\\d{2}:\\d{2}")) {
                result.put("success", false);
                result.put("error", "提醒时间格式错误，应为 HH:mm:ss");
                return result;
            }

            if (!startDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                result.put("success", false);
                result.put("error", "开始日期格式错误，应为 yyyy-MM-dd");
                return result;
            }

            if (!endDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                result.put("success", false);
                result.put("error", "结束日期格式错误，应为 yyyy-MM-dd");
                return result;
            }

            String normalizedRepeatType = (repeatType != null && !repeatType.isBlank())
                    ? repeatType.toUpperCase() : RepeatType.DAILY.name();

            // 按 conflictStrategy 分发到周期生成方法
            String strategy = (conflictStrategy != null && !conflictStrategy.isBlank())
                    ? conflictStrategy.toUpperCase() : "ASK";

            switch (strategy) {
                case "OVERWRITE":
                    result = recurringReminderService.generateWithOverwrite(
                            userId, title, remindTime, startDate, endDate,
                            normalizedRepeatType, repeatDays, location, Priority.NORMAL.name(), note);
                    result.put("executed", true);
                    if (result.containsKey("successCount")) {
                        result.put("createdCount", result.get("successCount"));
                    }
                    break;
                case "SKIP":
                    result = recurringReminderService.generateWithSkip(
                            userId, title, remindTime, startDate, endDate,
                            normalizedRepeatType, repeatDays, location, Priority.NORMAL.name(), note);
                    result.put("executed", true);
                    if (result.containsKey("successCount")) {
                        result.put("createdCount", result.get("successCount"));
                    }
                    break;
                case "ALLOW_COEXIST":
                    result = recurringReminderService.generateAll(
                            userId, title, remindTime, startDate, endDate,
                            normalizedRepeatType, repeatDays, location, Priority.NORMAL.name(), note);
                    result.put("executed", true);
                    if (result.containsKey("successCount")) {
                        result.put("createdCount", result.get("successCount"));
                    }
                    break;
                case "ASK":
                default:
                    // ASK：复用 createRecurringReminder，推一张汇总卡片
                    result = recurringReminderService.createRecurringReminder(
                            userId, title, remindTime, startDate, endDate,
                            normalizedRepeatType, repeatDays, location, Priority.NORMAL.name(), note);
                    // createRecurringReminder 返回 sessionId，走确认分支，不补 executed
                    break;
            }

        } catch (Exception e) {
            log.error("创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            throw new InvalidInputException("创建周期提醒失败: " + e.getMessage(), e);
        }

        return result;
    }

    // Agent 工具：按日期（或日期范围）取消提醒
    // 命中范围内全部 PENDING 提醒，可选用 keyword 进一步过滤；keyword 为"课程/课"等时按来源=COURSE 过滤
    @AgentTool(name = "cancel_reminders_by_date", description = "取消指定日期或日期范围的提醒，用户要求取消某天/某几天/本周的提醒时调用")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelRemindersByDate(Long userId, String date, String endDate, String keyword) {
        log.info("取消提醒: userId={}, date={}, endDate={}, keyword={}", userId, date, endDate, keyword);

        Map<String, Object> result = new HashMap<>();

        try {
            LocalDate startDate = LocalDate.parse(date);
            LocalDate endLocalDate = (endDate != null && !endDate.isBlank()) ? LocalDate.parse(endDate) : startDate;
            LocalDateTime rangeStart = startDate.atStartOfDay();
            LocalDateTime rangeEnd = endLocalDate.atTime(LocalTime.MAX);

            // 课程相关关键词：课程提醒标题是课程名（如"高等数学"），不含"课程"二字，故按来源=COURSE 过滤
            String kw = keyword == null ? "" : keyword.trim();
            boolean isCourseFilter = kw.equals("课程") || kw.equals("课") || kw.equals("上课")
                    || kw.equalsIgnoreCase("course") || kw.equals("课程表") || kw.equals("课表");

            List<Reminder> reminders = reminderRepository.findAll().stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .filter(r -> r.getRemindTime() != null)
                    .filter(r -> !r.getRemindTime().isBefore(rangeStart) && !r.getRemindTime().isAfter(rangeEnd))
                    .filter(r -> r.getStatus() == ReminderStatus.PENDING)
                    .filter(r -> {
                        if (kw.isEmpty()) return true;
                        if (isCourseFilter) {
                            return ReminderSource.COURSE.equals(r.getSource());
                        }
                        return r.getTitle() != null && r.getTitle().contains(kw);
                    })
                    .collect(Collectors.toList());

            if (reminders.isEmpty()) {
                result.put("success", true);
                result.put("cancelCount", 0);
                String rangeDesc = endLocalDate.isAfter(startDate)
                        ? (startDate + " 至 " + endLocalDate) : startDate.toString();
                result.put("message", "未找到" + rangeDesc + (kw.isEmpty() ? "" : "包含「" + kw + "」") + "的提醒");
                return result;
            }

            // 先从查询结果构建详情列表（查询时数据已完整，不依赖后续save）
            List<Map<String, Object>> cancelledList = new java.util.ArrayList<>();
            for (Reminder r : reminders) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", r.getId());
                item.put("title", r.getTitle());
                item.put("remindTime", r.getRemindTime().format(TimeUtils.FORMATTER));
                cancelledList.add(item);
                log.info("待取消提醒: reminderId={}, title={}", r.getId(), r.getTitle());
            }

            // 提取ID列表 + 原子批量更新（替代逐条 save，避免部分提交）
            List<Long> ids = reminders.stream().map(Reminder::getId).collect(Collectors.toList());
            int updated = reminderRepository.bulkCancelByIds(ids, userId, ReminderStatus.CANCELLED);
            log.info("批量取消提醒完成: userId={}, 期望={}, 实际更新={}", userId, ids.size(), updated);

            result.put("success", true);
            result.put("cancelCount", cancelledList.size());
            result.put("updatedCount", updated);
            result.put("cancelledReminders", cancelledList);
            result.put("message", String.format("已取消 %d 条提醒：%s",
                    cancelledList.size(),
                    cancelledList.stream().map(m -> (String) m.get("title")).collect(Collectors.joining("、"))));

        } catch (Exception e) {
            log.error("取消提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            throw new InvalidInputException("取消提醒失败: " + e.getMessage(), e);
        }

        return result;
    }

    // 相对时间解析：检测 remindTime 参数是否为 "X分钟后"、"X小时后"、"X天后" 等相对时间表达
    // 命中则返回对应的 LocalDateTime，未命中返回 null
    private LocalDateTime parseRelativeTimeInRemindTime(String remindTime) {
        if (remindTime == null || remindTime.isBlank()) {
            return null;
        }

        // 匹配 "X分钟后" / "X分后"
        Pattern minutePattern = Pattern.compile("(\\d+)\\s*分(?:钟)?后");
        Matcher minuteMatcher = minutePattern.matcher(remindTime);
        if (minuteMatcher.find()) {
            int minutes = Integer.parseInt(minuteMatcher.group(1));
            return TimeUtils.now().plusMinutes(minutes);
        }

        // 匹配 "X小时后"
        Pattern hourPattern = Pattern.compile("(\\d+)\\s*小时后");
        Matcher hourMatcher = hourPattern.matcher(remindTime);
        if (hourMatcher.find()) {
            int hours = Integer.parseInt(hourMatcher.group(1));
            return TimeUtils.now().plusHours(hours);
        }

        // 匹配 "X天后"
        Pattern dayPattern = Pattern.compile("(\\d+)\\s*天后");
        Matcher dayMatcher = dayPattern.matcher(remindTime);
        if (dayMatcher.find()) {
            int days = Integer.parseInt(dayMatcher.group(1));
            return TimeUtils.now().plusDays(days);
        }

        return null;
    }

    // 周期关键词识别：扫描标题里的"每天/每周/每月"等词，推断重复类型与默认跨度
    // 命中则返回拼好的起止日期与重复类型，未命中返回 null（按单次提醒处理）
    // WEEKLY 时还会从标题解析具体星期几填入 repeatDays（如"每周一"→"1"），解析不到则留空
    private RecurringParams detectDateRange(String title, String remindTime) {
        if (title == null || remindTime == null) {
            return null;
        }

        String lowerTitle = title.toLowerCase();
        String repeatType = null;
        String repeatDays = null;
        int spanDays = 30;

        if (lowerTitle.contains("每周") || lowerTitle.contains("星期") || lowerTitle.contains("周")) {
            repeatType = RepeatType.WEEKLY.name();
            spanDays = 90;
            repeatDays = parseWeekDays(lowerTitle);
        } else if (lowerTitle.contains("每月") || lowerTitle.contains("每个月")) {
            repeatType = RepeatType.MONTHLY.name();
            spanDays = 365;
        } else if (lowerTitle.contains("每天") || lowerTitle.contains("每日") || lowerTitle.contains("天天") || lowerTitle.contains("定期")) {
            repeatType = RepeatType.DAILY.name();
            spanDays = 30;
        }

        if (repeatType == null) {
            return null;
        }

        LocalDateTime parsedTime;
        try {
            parsedTime = LocalDateTime.parse(remindTime, TimeUtils.FORMATTER);
        } catch (Exception e) {
            return null;
        }

        String timePart = parsedTime.toLocalTime().format(TimeUtils.TIME_SECONDS_FORMATTER);
        String startDate = parsedTime.toLocalDate().toString();
        String endDate = parsedTime.toLocalDate().plusDays(spanDays).toString();

        log.info("周期识别: title={}, repeatType={}, repeatDays={}, timePart={}, startDate={}, endDate={}",
                title, repeatType, repeatDays, timePart, startDate, endDate);

        return new RecurringParams(timePart, startDate, endDate, repeatType, repeatDays);
    }

    // 从标题解析 WEEKLY 的星期几，返回逗号分隔的 ISO DayOfWeek（1=周一…7=周日）。
    // 识别"周一/星期一/礼拜一/每周一"及"每周末"等，识别不到返回 null
    private String parseWeekDays(String title) {
        java.util.Set<Integer> days = new java.util.TreeSet<>();
        // 先处理"周末"=周六日
        if (title.contains("周末")) {
            days.add(6);
            days.add(7);
        }
        // 按出现顺序扫描"周X/星期X/礼拜X"，X 为 一二三四五六日/天 或 1-7
        Matcher m = Pattern.compile("(?:周|星期|礼拜)([一二三四五六日天1-7])").matcher(title);
        while (m.find()) {
            Integer d = chineseDayOfWeek(m.group(1));
            if (d != null) days.add(d);
        }
        if (days.isEmpty()) return null;
        return days.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // 单个中文/数字星期标记 → ISO DayOfWeek
    private Integer chineseDayOfWeek(String token) {
        return switch (token) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            case "六", "6" -> 6;
            case "日", "天", "7" -> 7;
            default -> null;
        };
    }

    // 周期识别结果的内部载体：纯时间 + 起止日期 + 重复类型 + WEEKLY 星期几
    private static class RecurringParams {
        String remindTime;
        String startDate;
        String endDate;
        String repeatType;
        String repeatDays;

        RecurringParams(String remindTime, String startDate, String endDate, String repeatType, String repeatDays) {
            this.remindTime = remindTime;
            this.startDate = startDate;
            this.endDate = endDate;
            this.repeatType = repeatType;
            this.repeatDays = repeatDays;
        }
    }
}
