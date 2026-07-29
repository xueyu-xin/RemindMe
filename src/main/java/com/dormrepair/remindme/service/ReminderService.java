package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.agent.event.EventPublisher;
import com.dormrepair.remindme.dto.request.ReminderCreateRequest;
import com.dormrepair.remindme.dto.request.ReminderUpdateRequest;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.dto.response.ReminderResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提醒业务逻辑服务
 */
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

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Transactional
    public Map<String, Object> createReminder(ReminderCreateRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        String remindTimeStr = request.getRemindTime().format(FORMATTER);
        String endTimeStr = request.getEndTime() != null ? request.getEndTime().format(FORMATTER) : null;
        String priority = request.getPriority() != null ? request.getPriority() : "NORMAL";
        String location = request.getLocation();

        return createReminderForAgent(
                request.getUserId(),
                request.getTitle(),
                remindTimeStr,
                endTimeStr,
                location,
                priority
        );
    }

    @Transactional
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
        if (request.getPriority() != null) {
            reminder.setPriority(Priority.valueOf(request.getPriority()));
        }

        Reminder saved = reminderRepository.save(reminder);
        return toResponse(saved);
    }

    @Transactional
    public void cancelReminder(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        reminder.setStatus(ReminderStatus.CANCELLED);
        reminderRepository.save(reminder);
        eventPublisher.publishReminderCancelled(userId, id, reminder.getTitle());
    }

    public List<ReminderResponse> getRemindersByUser(Long userId) {
        return reminderRepository.findByUserIdAndStatus(userId, ReminderStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReminderResponse confirmReminder(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
        Reminder saved = reminderRepository.save(reminder);
        eventPublisher.publishReminderConfirmed(userId, id, reminder.getTitle());
        return toResponse(saved);
    }

    public List<ReminderResponse> getPendingReminders(Long userId) {
        return reminderRepository.findByUserIdAndConfirmStatus(userId, ConfirmStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 检测冲突（委托给 ConflictDetectionService）
     */
    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime) {
        return conflictDetectionService.checkConflict(userId, remindTime, null);
    }

    private Reminder getReminderByIdAndUserId(Long id, Long userId) {
        return reminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在或无权操作"));
    }

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
        response.setPriority(reminder.getPriority().name());
        response.setStatus(reminder.getStatus().name());
        response.setConfirmStatus(reminder.getConfirmStatus().name());
        response.setConfirmAttempts(reminder.getConfirmAttempts());
        response.setLastConfirmRemindAt(reminder.getLastConfirmRemindAt());
        response.setSource(reminder.getSource().name());
        response.setCreatedAt(reminder.getCreatedAt());
        response.setUpdatedAt(reminder.getUpdatedAt());
        return response;
    }

    /**
     * Agent工具：创建提醒
     * <p>
     * 供Agent调用的创建提醒方法，会触发阶段一确认流程
     * </p>
     *
     * @param userId 用户ID
     * @param title 提醒事项标题
     * @param remindTime 提醒时间（格式：yyyy-MM-dd HH:mm:ss）
     * @param location 地点（可选）
     * @param priority 优先级（LOW/NORMAL/HIGH，默认NORMAL）
     * @return 创建结果
     */
    @AgentTool(name = "create_reminder", description = "创建提醒，用户要求设置提醒时调用")
    @Transactional
    public Map<String, Object> createReminderForAgent(Long userId, String title, String remindTime,
            String endTime, String location, String priority) {
        
        log.info("Agent调用创建提醒: userId={}, title={}, remindTime={}, endTime={}", userId, title, remindTime, endTime);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 周期识别：检查标题是否包含周期关键词
            RecurringParams recurringParams = detectDateRange(title, remindTime);
            if (recurringParams != null) {
                log.info("检测到日期范围，路由到周期提醒: startDate={}, endDate={}, repeatType={}",
                        recurringParams.startDate, recurringParams.endDate, recurringParams.repeatType);
                return recurringReminderService.createRecurringReminder(
                        userId, title, recurringParams.remindTime,
                        recurringParams.startDate, recurringParams.endDate,
                        recurringParams.repeatType, location, priority != null ? priority : "NORMAL");
            }

            // 解析时间
            LocalDateTime parsedTime = LocalDateTime.parse(remindTime, FORMATTER);
            LocalDateTime parsedEndTime = endTime != null ? LocalDateTime.parse(endTime, FORMATTER) : null;
            
            // 构建解析数据，用于确认会话
            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("title", title);
            parsedData.put("remindTime", remindTime);
            parsedData.put("location", location);
            parsedData.put("priority", priority != null ? priority : "NORMAL");
            parsedData.put("advanceMinutes", 10);
            parsedData.put("source", "USER_INPUT");
            parsedData.put("intentType", "REMINDER");
            
            // 添加 endTime 到 parsedData
            if (parsedEndTime != null) {
                parsedData.put("endTime", endTime);
            }
            
            // 冲突检测（支持时间段重叠检测）
            ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, parsedTime, parsedEndTime, null);
            if (conflictResponse.isHasConflict()) {
                String description = conflictDetectionService.getConflictDescription(
                        conflictResponse.getConflicts(), parsedTime);
                
                parsedData.put("hasConflict", true);
                parsedData.put("conflictMessage", description);
                parsedData.put("conflictList", conflictResponse.getConflicts());
                parsedData.put("suggestedTime", conflictResponse.getSuggestedTime() != null 
                        ? conflictResponse.getSuggestedTime().format(FORMATTER) : null);
                
                // 取第一个冲突的提醒ID，用于覆盖操作
                if (conflictResponse.getConflicts() != null && !conflictResponse.getConflicts().isEmpty()) {
                    parsedData.put("conflictId", conflictResponse.getConflicts().get(0).getId());
                }
                
                log.info("Agent创建提醒检测到冲突: userId={}, title={}, conflicts={}", 
                        userId, title, conflictResponse.getConflicts().size());
            } else {
                parsedData.put("hasConflict", false);
            }
            
            // 创建阶段一确认会话（无论是否有冲突都走确认流程）
            ConfirmationCardResponse card = confirmationService.createSession(userId, title, parsedData);

            // 发布提醒创建事件
            eventPublisher.publishReminderCreated(userId, null, title, remindTime, location, true);

            result.put("success", true);
            result.put("message", conflictResponse.isHasConflict() 
                    ? "检测到时间冲突，请确认是否覆盖" : "提醒创建请求已发送，请确认");
            result.put("title", title);
            result.put("remindTime", remindTime);
            result.put("location", location);
            result.put("priority", priority != null ? priority : "NORMAL");
            result.put("sessionCreated", true);
            result.put("sessionId", card.getData().getSessionId());
            result.put("confirmationRequired", true);
            result.put("hasConflict", conflictResponse.isHasConflict());
            // 将确认卡片的 actions、timeout、expiresAt 传递给前端
            result.put("actions", card.getActions());
            result.put("timeout", card.getTimeout());
            result.put("expiresAt", card.getExpiresAt());
            result.put("shouldStop", true);
            result.put("taskCompleted", true);
            
            log.info("Agent创建提醒成功: title={}, sessionCreated=true, hasConflict={}", 
                    title, conflictResponse.isHasConflict());
            
        } catch (Exception e) {
            log.error("Agent创建提醒失败: userId={}, title={}, error={}", userId, title, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "创建提醒失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Agent工具：查询提醒
     * <p>
     * 查询指定日期的提醒列表
     * </p>
     *
     * @param userId 用户ID
     * @param date 查询日期（格式：yyyy-MM-dd，默认今天）
     * @return 提醒列表
     */
    @AgentTool(name = "query_reminders", description = "查询提醒，用户询问日程安排时调用")
    public Map<String, Object> queryRemindersForAgent(Long userId, String date) {
        log.info("Agent调用查询提醒: userId={}, date={}", userId, date);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 解析日期，默认今天
            LocalDateTime parsedDate;
            if (date != null && !date.isBlank()) {
                parsedDate = LocalDateTime.parse(date + " 00:00:00", FORMATTER);
            } else {
                parsedDate = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
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
                        item.put("remindTime", r.getRemindTime().format(FORMATTER));
                        item.put("location", r.getLocation());
                        item.put("priority", r.getPriority().name());
                        item.put("status", r.getStatus().name());
                        item.put("confirmStatus", r.getConfirmStatus().name());
                        return item;
                    })
                    .collect(Collectors.toList());
            
            result.put("success", true);
            result.put("date", date != null ? date : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            result.put("count", reminderList.size());
            result.put("reminders", reminderList);
            
            log.info("Agent查询提醒成功: userId={}, count={}", userId, reminderList.size());
            
        } catch (Exception e) {
            log.error("Agent查询提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "查询提醒失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Agent工具：更新提醒
     * <p>
     * 修改指定提醒的信息
     * </p>
     *
     * @param userId 用户ID
     * @param reminderId 提醒ID
     * @param title 标题（可选）
     * @param remindTime 提醒时间（可选，格式：yyyy-MM-dd HH:mm:ss）
     * @param location 地点（可选）
     * @return 更新结果
     */
    @AgentTool(name = "update_reminder", description = "修改提醒，用户要求更改提醒时调用")
    @Transactional
    public Map<String, Object> updateReminderForAgent(Long userId, Long reminderId, String title,
            String remindTime, String endTime, String location) {
        
        log.info("Agent调用更新提醒: userId={}, reminderId={}, endTime={}", userId, reminderId, endTime);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            Reminder reminder = getReminderByIdAndUserId(reminderId, userId);
            
            if (title != null && !title.isBlank()) {
                reminder.setTitle(title);
            }
            if (remindTime != null && !remindTime.isBlank()) {
                LocalDateTime parsedTime = LocalDateTime.parse(remindTime, FORMATTER);
                reminder.setRemindTime(parsedTime);
            }
            if (endTime != null && !endTime.isBlank()) {
                LocalDateTime parsedEndTime = LocalDateTime.parse(endTime, FORMATTER);
                reminder.setEndTime(parsedEndTime);
            }
            if (location != null) {
                reminder.setLocation(location);
            }
            
            Reminder saved = reminderRepository.save(reminder);
            
            result.put("success", true);
            result.put("message", "提醒已更新");
            result.put("id", saved.getId());
            result.put("title", saved.getTitle());
            result.put("remindTime", saved.getRemindTime().format(FORMATTER));
            if (saved.getEndTime() != null) {
                result.put("endTime", saved.getEndTime().format(FORMATTER));
            }
            result.put("location", saved.getLocation());
            result.put("priority", saved.getPriority().name());
            
            log.info("Agent更新提醒成功: reminderId={}", reminderId);
            
        } catch (Exception e) {
            log.error("Agent更新提醒失败: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "更新提醒失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Agent工具：取消提醒
     * <p>
     * 删除指定的提醒
     * </p>
     *
     * @param userId 用户ID
     * @param reminderId 提醒ID
     * @return 取消结果
     */
    @AgentTool(name = "cancel_reminder", description = "取消提醒，用户要求删除提醒时调用")
    @Transactional
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
            result.put("success", false);
            result.put("error", "取消提醒失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * Agent工具：获取提醒详情
     * <p>
     * 查询单个提醒的完整信息
     * </p>
     *
     * @param userId     用户ID
     * @param reminderId 提醒ID
     * @return 提醒详情
     */
    @AgentTool(name = "get_reminder_detail", description = "获取单个提醒的完整详情，用户询问'第一个提醒是什么时间'、'具体信息'时调用")
    public Map<String, Object> getReminderDetail(Long userId, Long reminderId) {
        log.info("Agent调用获取提醒详情: userId={}, reminderId={}", userId, reminderId);

        Map<String, Object> result = new HashMap<>();

        try {
            Reminder reminder = getReminderByIdAndUserId(reminderId, userId);

            result.put("success", true);
            result.put("id", reminder.getId());
            result.put("title", reminder.getTitle());
            result.put("remindTime", reminder.getRemindTime().format(FORMATTER));
            result.put("timezone", reminder.getTimezone());
            result.put("advanceMinutes", reminder.getAdvanceMinutes());
            result.put("cronExpression", reminder.getCronExpression());
            result.put("location", reminder.getLocation());
            result.put("priority", reminder.getPriority().name());
            result.put("status", reminder.getStatus().name());
            result.put("confirmStatus", reminder.getConfirmStatus().name());
            result.put("executionStatus", reminder.getExecutionStatus().name());
            result.put("confirmAttempts", reminder.getConfirmAttempts());
            result.put("source", reminder.getSource().name());
            result.put("createdAt", reminder.getCreatedAt() != null ? reminder.getCreatedAt().format(FORMATTER) : null);
            result.put("updatedAt", reminder.getUpdatedAt() != null ? reminder.getUpdatedAt().format(FORMATTER) : null);

            log.info("Agent获取提醒详情成功: reminderId={}, title={}", reminderId, reminder.getTitle());

        } catch (IllegalArgumentException e) {
            log.warn("Agent获取提醒详情失败: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        } catch (Exception e) {
            log.error("Agent获取提醒详情异常: userId={}, reminderId={}, error={}", userId, reminderId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "获取提醒详情失败: " + e.getMessage());
        }

        return result;
    }

    @AgentTool(name = "create_recurring_reminder", description = "创建周期提醒，用户输入包含日期范围时调用")
    @Transactional
    public Map<String, Object> createRecurringReminderForAgent(Long userId, String title,
            String remindTime, String startDate, String endDate, String repeatType) {

        log.info("Agent调用创建周期提醒: userId={}, title={}, remindTime={}, startDate={}, endDate={}, repeatType={}",
                userId, title, remindTime, startDate, endDate, repeatType);

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
                    ? repeatType.toUpperCase() : "DAILY";

            return recurringReminderService.createRecurringReminder(
                    userId, title, remindTime, startDate, endDate,
                    normalizedRepeatType, null, "NORMAL");

        } catch (Exception e) {
            log.error("创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "创建周期提醒失败: " + e.getMessage());
        }

        return result;
    }

    @AgentTool(name = "cancel_reminders_by_date", description = "取消指定日期的提醒，用户要求取消某天的提醒时调用")
    @Transactional
    public Map<String, Object> cancelRemindersByDate(Long userId, String date, String keyword) {
        log.info("取消提醒: userId={}, date={}, keyword={}", userId, date, keyword);

        Map<String, Object> result = new HashMap<>();

        try {
            LocalDate targetDate = LocalDate.parse(date);
            LocalDateTime dayStart = targetDate.atStartOfDay();
            LocalDateTime dayEnd = targetDate.atTime(LocalTime.MAX);

            List<Reminder> reminders = reminderRepository.findAll().stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .filter(r -> r.getRemindTime() != null)
                    .filter(r -> !r.getRemindTime().isBefore(dayStart) && !r.getRemindTime().isAfter(dayEnd))
                    .filter(r -> r.getStatus() == ReminderStatus.PENDING)
                    .filter(r -> keyword == null || keyword.isBlank() ||
                            (r.getTitle() != null && r.getTitle().contains(keyword)))
                    .collect(Collectors.toList());

            if (reminders.isEmpty()) {
                result.put("success", true);
                result.put("cancelCount", 0);
                result.put("message", "未找到" + (keyword != null ? "包含「" + keyword + "」" : "") + "的提醒");
                return result;
            }

            List<Map<String, Object>> cancelledList = new java.util.ArrayList<>();
            for (Reminder r : reminders) {
                r.setStatus(ReminderStatus.CANCELLED);
                reminderRepository.save(r);

                Map<String, Object> item = new HashMap<>();
                item.put("id", r.getId());
                item.put("title", r.getTitle());
                item.put("remindTime", r.getRemindTime().format(FORMATTER));
                cancelledList.add(item);

                log.info("取消提醒: reminderId={}, title={}", r.getId(), r.getTitle());
            }

            result.put("success", true);
            result.put("cancelCount", cancelledList.size());
            result.put("cancelledReminders", cancelledList);
            result.put("message", String.format("已取消 %d 条提醒：%s",
                    cancelledList.size(),
                    cancelledList.stream().map(m -> (String) m.get("title")).collect(Collectors.joining("、"))));

        } catch (Exception e) {
            log.error("取消提醒失败: userId={}, error={}", userId, e.getMessage());
            result.put("success", false);
            result.put("error", "取消提醒失败: " + e.getMessage());
        }

        return result;
    }

    private RecurringParams detectDateRange(String title, String remindTime) {
        if (title == null || remindTime == null) {
            return null;
        }

        title = title.toLowerCase();
        String repeatType = null;
        int spanDays = 30;

        if (title.contains("每周") || title.contains("星期") || title.contains("周")) {
            repeatType = "WEEKLY";
            spanDays = 90;
        } else if (title.contains("每月") || title.contains("每个月")) {
            repeatType = "MONTHLY";
            spanDays = 365;
        } else if (title.contains("每天") || title.contains("每日") || title.contains("天天") || title.contains("定期")) {
            repeatType = "DAILY";
            spanDays = 30;
        }

        if (repeatType == null) {
            return null;
        }

        LocalDateTime parsedTime;
        try {
            parsedTime = LocalDateTime.parse(remindTime, FORMATTER);
        } catch (Exception e) {
            return null;
        }

        String timePart = parsedTime.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String startDate = parsedTime.toLocalDate().toString();
        String endDate = parsedTime.toLocalDate().plusDays(spanDays).toString();

        log.info("周期识别: title={}, repeatType={}, timePart={}, startDate={}, endDate={}",
                title, repeatType, timePart, startDate, endDate);

        return new RecurringParams(timePart, startDate, endDate, repeatType);
    }

    private static class RecurringParams {
        String remindTime;
        String startDate;
        String endDate;
        String repeatType;

        RecurringParams(String remindTime, String startDate, String endDate, String repeatType) {
            this.remindTime = remindTime;
            this.startDate = startDate;
            this.endDate = endDate;
            this.repeatType = repeatType;
        }
    }
}
