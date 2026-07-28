package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.entity.RecurringInstance;
import com.dormrepair.remindme.entity.RecurringReminder;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.RecurringInstanceRepository;
import com.dormrepair.remindme.repository.RecurringReminderRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringReminderService {

    private final RecurringReminderRepository recurringReminderRepository;
    private final RecurringInstanceRepository recurringInstanceRepository;
    private final ReminderRepository reminderRepository;
    private final ConflictDetectionService conflictDetectionService;
    private final ConfirmationService confirmationService;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Transactional
    public Map<String, Object> createRecurringReminder(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String location, String priority) {

        log.info("创建周期提醒: userId={}, title={}, remindTime={}, startDate={}, endDate={}, repeatType={}",
                userId, title, remindTime, startDate, endDate, repeatType);

        Map<String, Object> result = new HashMap<>();

        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TIME_FORMATTER);
            LocalDate parsedStartDate = LocalDate.parse(startDate);
            LocalDate parsedEndDate = LocalDate.parse(endDate);
            RepeatType parsedRepeatType = (repeatType != null && !repeatType.isBlank())
                    ? RepeatType.valueOf(repeatType) : RepeatType.DAILY;

            if (parsedStartDate.isAfter(parsedEndDate)) {
                result.put("success", false);
                result.put("error", "开始日期不能晚于结束日期");
                return result;
            }

            // 构建周期提醒的 parsedData，走确认卡片流程
            String fullDateTime = startDate + " " + remindTime;  // "2026-08-03 19:00:00"
            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("isRecurring", true);
            parsedData.put("title", title);
            parsedData.put("remindTime", fullDateTime);  // 完整日期时间，供 createSession 解析
            parsedData.put("recurringRemindTime", remindTime);  // 纯时间 HH:mm:ss，供确认后创建用
            parsedData.put("startDate", startDate);
            parsedData.put("endDate", endDate);
            parsedData.put("repeatType", parsedRepeatType.name());
            parsedData.put("recurringStartDate", startDate);
            parsedData.put("recurringEndDate", endDate);
            parsedData.put("recurringRepeatType", parsedRepeatType.name());
            parsedData.put("location", location);
            parsedData.put("priority", priority != null ? priority : "NORMAL");
            parsedData.put("source", "AGENT");

            int totalDays = (int) (parsedEndDate.toEpochDay() - parsedStartDate.toEpochDay()) + 1;
            parsedData.put("totalDays", totalDays);

            // 周期冲突检测
            LocalDateTime sampleTime = LocalDateTime.of(parsedStartDate, parsedTime);
            ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, sampleTime, null);
            if (conflictResponse.isHasConflict()) {
                parsedData.put("hasConflict", true);
                parsedData.put("conflictList", conflictResponse.getConflicts());
                parsedData.put("suggestedTime", conflictResponse.getSuggestedTime() != null
                        ? conflictResponse.getSuggestedTime().format(DATE_TIME_FORMATTER) : null);
            }

            ConfirmationCardResponse card = confirmationService.createSession(userId, title, parsedData);

            result.put("success", true);
            result.put("message", "周期提醒确认卡片已生成，请确认");
            result.put("isRecurring", true);
            result.put("startDate", startDate);
            result.put("endDate", endDate);
            result.put("repeatType", parsedRepeatType.name());
            result.put("totalDays", totalDays);
            result.put("sessionId", card.getData().getSessionId());
            result.put("confirmationRequired", true);
            result.put("actions", card.getActions());
            result.put("timeout", card.getTimeout());
            result.put("expiresAt", card.getExpiresAt());
            result.put("shouldStop", true);
            result.put("taskCompleted", true);

        } catch (Exception e) {
            log.error("创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "创建周期提醒失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 从确认流程生成周期实例（供 confirm() 和 cleanupExpiredSessions() 调用）
     * <p>
     * 与 confirmAndCreateRecurring 功能相同，作为语义更清晰的入口
     * </p>
     */
    @Transactional
    public Map<String, Object> generateInstancesFromConfirm(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String location, String priority) {
        return confirmAndCreateRecurring(userId, title, remindTime, startDate, endDate, repeatType, location, priority);
    }

    /**
     * 确认回调后实际创建周期模板和实例
     */
    @Transactional
    public Map<String, Object> confirmAndCreateRecurring(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String location, String priority) {

        Map<String, Object> result = new HashMap<>();

        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TIME_FORMATTER);
            LocalDate parsedStartDate = LocalDate.parse(startDate);
            LocalDate parsedEndDate = LocalDate.parse(endDate);
            RepeatType parsedRepeatType = RepeatType.valueOf(repeatType);

            RecurringReminder template = new RecurringReminder();
            template.setUserId(userId);
            template.setTitle(title);
            template.setRemindTime(parsedTime);
            template.setStartDate(parsedStartDate);
            template.setEndDate(parsedEndDate);
            template.setRepeatType(parsedRepeatType);
            template.setLocation(location);
            template.setPriority(priority != null ? priority : "NORMAL");
            template.setStatus("ACTIVE");
            template.setSource("AGENT");
            template.setTimezone("Asia/Shanghai");

            RecurringReminder savedTemplate = recurringReminderRepository.save(template);
            log.info("周期模板已创建: id={}, title={}", savedTemplate.getId(), title);

            Map<String, Integer> generationResult = generateInstancesForDateRange(savedTemplate, parsedStartDate, parsedEndDate);

            result.put("success", true);
            result.put("message", String.format("周期提醒创建成功，共 %d 天，成功 %d 条，冲突 %d 条，失败 %d 条",
                    generationResult.get("total"),
                    generationResult.get("success"),
                    generationResult.get("conflict"),
                    generationResult.get("failed")));
            result.put("recurringId", savedTemplate.getId());
            result.put("totalDays", generationResult.get("total"));
            result.put("successCount", generationResult.get("success"));
            result.put("conflictCount", generationResult.get("conflict"));
            result.put("failedCount", generationResult.get("failed"));

        } catch (Exception e) {
            log.error("创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "创建周期提醒失败: " + e.getMessage());
        }

        return result;
    }

    private Map<String, Integer> generateInstancesForDateRange(RecurringReminder template,
            LocalDate startDate, LocalDate endDate) {

        int total = 0;
        int success = 0;
        int conflict = 0;
        int failed = 0;

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            if (shouldGenerateForDate(template, current)) {
                // 重复检查：该日期是否已存在实例
                if (recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), current)) {
                    log.info("周期实例已存在，跳过: recurringId={}, date={}", template.getId(), current);
                    current = current.plusDays(1);
                    continue;
                }

                total++;
                try {
                    Map<String, Object> instanceResult = generateInstanceForDate(template, current);
                    String status = (String) instanceResult.get("status");
                    if ("success".equals(status)) {
                        success++;
                    } else if ("conflict".equals(status)) {
                        conflict++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    log.error("生成周期实例失败: recurringId={}, date={}, error={}",
                            template.getId(), current, e.getMessage());
                    failed++;
                }
            }
            current = current.plusDays(1);
        }

        Map<String, Integer> counts = new HashMap<>();
        counts.put("total", total);
        counts.put("success", success);
        counts.put("conflict", conflict);
        counts.put("failed", failed);
        return counts;
    }

    private boolean shouldGenerateForDate(RecurringReminder template, LocalDate date) {
        LocalDate start = template.getStartDate();
        LocalDate end = template.getEndDate();
        if (date.isBefore(start) || date.isAfter(end)) {
            return false;
        }

        RepeatType repeatType = template.getRepeatType();
        if (repeatType == RepeatType.DAILY) {
            return true;
        } else if (repeatType == RepeatType.WEEKLY) {
            return true;
        } else if (repeatType == RepeatType.MONTHLY) {
            return date.getDayOfMonth() == start.getDayOfMonth();
        }
        return true;
    }

    public Map<String, Object> generateInstanceForDate(RecurringReminder template, LocalDate date) {
        Map<String, Object> result = new HashMap<>();

        if (recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), date)) {
            result.put("status", "skipped");
            result.put("message", "该日期已生成，跳过");
            return result;
        }

        LocalDateTime remindDateTime = LocalDateTime.of(date, template.getRemindTime());

        ConflictResponse conflictResponse = conflictDetectionService.checkConflict(
                template.getUserId(), remindDateTime, null);

        RecurringInstance instance = new RecurringInstance();
        instance.setRecurringId(template.getId());
        instance.setRemindDate(date);
        instance.setStatus("PENDING");

        if (conflictResponse.isHasConflict()) {
            instance.setStatus("CONFLICT");
            RecurringInstance saved = recurringInstanceRepository.save(instance);

            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("title", template.getTitle());
            parsedData.put("remindTime", remindDateTime.format(DATE_TIME_FORMATTER));
            parsedData.put("location", template.getLocation());
            parsedData.put("priority", template.getPriority());
            parsedData.put("advanceMinutes", template.getAdvanceMinutes());
            parsedData.put("source", "AGENT");
            parsedData.put("conflict", true);
            parsedData.put("conflictList", conflictResponse.getConflicts());

            confirmationService.createSession(template.getUserId(), template.getTitle(), parsedData);

            result.put("status", "conflict");
            result.put("instanceId", saved.getId());
        } else {
            Reminder reminder = new Reminder();
            reminder.setUserId(template.getUserId());
            reminder.setTitle(template.getTitle());
            reminder.setRemindTime(remindDateTime);
            reminder.setTimezone(template.getTimezone());
            reminder.setAdvanceMinutes(template.getAdvanceMinutes());
            reminder.setLocation(template.getLocation());
            reminder.setPriority(com.dormrepair.remindme.enums.Priority.valueOf(template.getPriority()));
            reminder.setStatus(ReminderStatus.PENDING);
            reminder.setSource(ReminderSource.USER_INPUT);

            Reminder savedReminder = reminderRepository.save(reminder);

            instance.setReminderId(savedReminder.getId());
            instance.setStatus("CREATED");
            RecurringInstance savedInstance = recurringInstanceRepository.save(instance);

            result.put("status", "success");
            result.put("reminderId", savedReminder.getId());
            result.put("instanceId", savedInstance.getId());
        }

        recurringReminderRepository.findById(template.getId()).ifPresent(r -> {
            r.setLastGeneratedDate(date);
            recurringReminderRepository.save(r);
        });

        return result;
    }

    public List<RecurringReminder> getRecurringRemindersByUser(Long userId) {
        return recurringReminderRepository.findByUserIdAndStatus(userId, "ACTIVE");
    }

    public Optional<RecurringReminder> getRecurringReminderById(Long id, Long userId) {
        return recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId));
    }

    @Transactional
    public void cancelRecurringReminder(Long id, Long userId) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));
        reminder.setStatus("CANCELLED");
        recurringReminderRepository.save(reminder);

        List<RecurringInstance> instances = recurringInstanceRepository.findByRecurringId(id);
        for (RecurringInstance inst : instances) {
            inst.setStatus("CANCELLED");
            recurringInstanceRepository.save(inst);
        }
    }

    @Transactional
    public void pauseRecurringReminder(Long id, Long userId) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));
        reminder.setStatus("PAUSED");
        recurringReminderRepository.save(reminder);
    }

    @Transactional
    public void resumeRecurringReminder(Long id, Long userId) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));
        reminder.setStatus("ACTIVE");
        recurringReminderRepository.save(reminder);
    }

    @Transactional
    public int generatePendingInstancesForToday() {
        LocalDate today = LocalDate.now();
        List<RecurringReminder> activeReminders = recurringReminderRepository.findByStatus("ACTIVE");

        int generated = 0;
        for (RecurringReminder template : activeReminders) {
            try {
                if (shouldGenerateForDate(template, today)) {
                    if (!recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), today)) {
                        Map<String, Object> result = generateInstanceForDate(template, today);
                        if ("success".equals(result.get("status"))) {
                            generated++;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("定时生成周期实例失败: recurringId={}, error={}", template.getId(), e.getMessage());
            }
        }
        return generated;
    }

    @Transactional
    public RecurringReminder updateRecurringReminder(Long id, Long userId, String title,
            String remindTime, LocalDate startDate, LocalDate endDate, RepeatType repeatType,
            String location, String priority) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));

        if (title != null) reminder.setTitle(title);
        if (remindTime != null) {
            reminder.setRemindTime(java.time.LocalTime.parse(remindTime, TIME_FORMATTER));
        }
        if (startDate != null) reminder.setStartDate(startDate);
        if (endDate != null) reminder.setEndDate(endDate);
        if (repeatType != null) reminder.setRepeatType(repeatType);
        if (location != null) reminder.setLocation(location);
        if (priority != null) reminder.setPriority(priority);

        return recurringReminderRepository.save(reminder);
    }
}
