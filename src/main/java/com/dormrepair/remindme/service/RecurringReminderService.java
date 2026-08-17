package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.entity.RecurringInstance;
import com.dormrepair.remindme.entity.RecurringReminder;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.RecurringInstanceRepository;
import com.dormrepair.remindme.repository.RecurringReminderRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 周期提醒服务
// 管理周期提醒模板的创建/暂停/恢复/取消/更新，以及不同策略下的周期实例生成
@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringReminderService {

    private final RecurringInstanceRepository recurringInstanceRepository;
    private final RecurringReminderRepository recurringReminderRepository;
    private final ReminderRepository reminderRepository;
    private final ConflictDetectionService conflictDetectionService;
    private final ConfirmationService confirmationService;

    // 创建周期提醒入口
    // 不直接落库，而是构建 parsedData 走确认卡片流程，由确认回调真正创建模板与实例
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRecurringReminder(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String repeatDays,
            String location, String priority) {

        log.info("创建周期提醒: userId={}, title={}, remindTime={}, startDate={}, endDate={}, repeatType={}, repeatDays={}",
                userId, title, remindTime, startDate, endDate, repeatType, repeatDays);

        Map<String, Object> result = new HashMap<>();

        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TimeUtils.TIME_SECONDS_FORMATTER);
            LocalDate parsedStartDate = LocalDate.parse(startDate);
            LocalDate parsedEndDate = LocalDate.parse(endDate);
            RepeatType parsedRepeatType = (repeatType != null && !repeatType.isBlank())
                    ? RepeatType.valueOf(repeatType) : RepeatType.DAILY;

            if (parsedStartDate.isAfter(parsedEndDate)) {
                result.put("success", false);
                result.put("error", "开始日期不能晚于结束日期");
                return result;
            }

            // 构造探针模板（不落库），供 shouldGenerateForDate 判断哪些日期真正会生成实例
            RecurringReminder probe = new RecurringReminder();
            probe.setStartDate(parsedStartDate);
            probe.setEndDate(parsedEndDate);
            probe.setRepeatType(parsedRepeatType);
            probe.setRepeatDays(repeatDays);

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
            parsedData.put("repeatDays", repeatDays);
            parsedData.put("location", location);
            parsedData.put("priority", priority != null ? priority : Priority.NORMAL.name());
            parsedData.put("source", "AGENT");

            // totalDays 只统计真正会生成实例的日期数（WEEKLY 按 repeatDays 过滤），而非整个范围跨度
            int totalDays = 0;
            LocalDate scan = parsedStartDate;
            while (!scan.isAfter(parsedEndDate)) {
                if (shouldGenerateForDate(probe, scan)) {
                    totalDays++;
                }
                scan = scan.plusDays(1);
            }
            parsedData.put("totalDays", totalDays);

            // 周期冲突检测 - 仅遍历真正会生成实例的日期
            List<Map<String, Object>> allConflicts = new ArrayList<>();
            int conflictDayCount = 0;
            LocalDate currentDate = parsedStartDate;
            while (!currentDate.isAfter(parsedEndDate)) {
                if (!shouldGenerateForDate(probe, currentDate)) {
                    currentDate = currentDate.plusDays(1);
                    continue;
                }
                LocalDateTime dateTime = LocalDateTime.of(currentDate, parsedTime);
                ConflictResponse dayConflict = conflictDetectionService.checkConflict(userId, dateTime, null);
                if (dayConflict.isHasConflict() && dayConflict.getConflicts() != null) {
                    conflictDayCount++;
                    for (ConflictResponse.ConflictItem item : dayConflict.getConflicts()) {
                        Map<String, Object> conflictMap = new HashMap<>();
                        conflictMap.put("id", item.getId());
                        conflictMap.put("title", item.getTitle());
                        conflictMap.put("remindTime", item.getRemindTime() != null
                                ? item.getRemindTime().format(TimeUtils.FORMATTER) : null);
                        conflictMap.put("conflictDate", currentDate.toString());
                        allConflicts.add(conflictMap);
                    }
                }
                currentDate = currentDate.plusDays(1);
            }
            log.info("周期冲突检测完成: 总天数={}, 冲突天数={}, 冲突项数={}", totalDays, conflictDayCount, allConflicts.size());
            if (!allConflicts.isEmpty()) {
                parsedData.put("hasConflict", true);
                parsedData.put("conflictList", allConflicts);
                parsedData.put("conflictDayCount", conflictDayCount);
                parsedData.put("conflictMessage", String.format("日期范围内有 %d 天存在时间冲突，共 %d 条冲突提醒",
                        conflictDayCount, allConflicts.size()));
            }

            ConfirmationCardResponse card = confirmationService.createSession(userId, title, parsedData);

            result.put("success", true);
            result.put("message", "周期提醒确认卡片已生成，请确认");
            result.put("title", title);
            result.put("remindTime", fullDateTime);
            result.put("location", location);
            result.put("isRecurring", true);
            result.put("startDate", startDate);
            result.put("endDate", endDate);
            result.put("repeatType", parsedRepeatType.name());
            result.put("repeatDays", repeatDays);
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
            throw new RuntimeException("创建周期提醒失败: " + e.getMessage(), e);
        }
        return result;
    }

    // 从确认流程生成周期实例
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateInstancesFromConfirm(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String repeatDays,
            String location, String priority) {
        return confirmAndCreateRecurring(userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, priority);
    }

    // 直接创建全部周期实例
    // 不检查冲突，冲突日多条提醒并存
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateAll(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String repeatDays,
            String location, String priority) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TimeUtils.TIME_SECONDS_FORMATTER);
            LocalDate parsedStartDate = LocalDate.parse(startDate);
            LocalDate parsedEndDate = LocalDate.parse(endDate);
            RepeatType parsedRepeatType = RepeatType.valueOf(repeatType);

            log.info("直接创建周期提醒: userId={}, title={}, time={}, {}~{}, repeat={}, repeatDays={}",
                    userId, title, remindTime, startDate, endDate, repeatType, repeatDays);

            RecurringReminder template = createRecurringTemplate(userId, title, parsedTime,
                    parsedStartDate, parsedEndDate, parsedRepeatType, repeatDays, location, priority);
            log.info("周期模板已保存: recurringId={}, title={}", template.getId(), template.getTitle());

            int total = 0, success = 0;
            LocalDate current = parsedStartDate;
            while (!current.isAfter(parsedEndDate)) {
                if (shouldGenerateForDate(template, current)
                        && !recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), current)) {
                    total++;
                    LocalDateTime remindDateTime = LocalDateTime.of(current, parsedTime);
                    Reminder reminder = createReminderDirect(template, remindDateTime);
                    log.info("提醒已保存: id={}, title={}, time={}", reminder.getId(), reminder.getTitle(), remindDateTime);
                    saveInstance(template.getId(), current, reminder.getId(), "CREATED");
                    success++;
                }
                current = current.plusDays(1);
            }

            log.info("直接创建完成: recurringId={}, total={}, success={}", template.getId(), total, success);

            result.put("success", true);
            result.put("message", String.format("已直接创建 %d 天提醒（冲突日多条并存）", success));
            result.put("recurringId", template.getId());
            result.put("totalDays", total);
            result.put("successCount", success);
        } catch (Exception e) {
            log.error("直接创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            throw new RuntimeException("直接创建周期提醒失败: " + e.getMessage(), e);
        }
        return result;
    }

    // 跳过冲突日，只创建无冲突的日期
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateWithSkip(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String repeatDays,
            String location, String priority) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TimeUtils.TIME_SECONDS_FORMATTER);
            LocalDate parsedStartDate = LocalDate.parse(startDate);
            LocalDate parsedEndDate = LocalDate.parse(endDate);
            RepeatType parsedRepeatType = RepeatType.valueOf(repeatType);

            log.info("跳过冲突创建周期提醒: userId={}, title={}, time={}, {}~{}", userId, title, remindTime, startDate, endDate);

            RecurringReminder template = createRecurringTemplate(userId, title, parsedTime,
                    parsedStartDate, parsedEndDate, parsedRepeatType, repeatDays, location, priority);
            log.info("周期模板已保存: recurringId={}", template.getId());

            int total = 0, success = 0, skipped = 0;
            List<String> skippedDates = new ArrayList<>();
            LocalDate current = parsedStartDate;
            while (!current.isAfter(parsedEndDate)) {
                if (shouldGenerateForDate(template, current)
                        && !recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), current)) {
                    total++;
                    LocalDateTime remindDateTime = LocalDateTime.of(current, parsedTime);
                    ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, remindDateTime, null);

                    if (conflictResponse.isHasConflict()) {
                        // 冲突日：不创建提醒，不保存实例，只记录
                        log.info("跳过冲突日: date={}, conflictCount={}", current,
                                conflictResponse.getConflicts() != null ? conflictResponse.getConflicts().size() : 0);
                        skippedDates.add(current.toString());
                        skipped++;
                    } else {
                        Reminder reminder = createReminderDirect(template, remindDateTime);
                        log.info("提醒已保存: id={}, title={}, time={}", reminder.getId(), reminder.getTitle(), remindDateTime);
                        saveInstance(template.getId(), current, reminder.getId(), "CREATED");
                        success++;
                    }
                }
                current = current.plusDays(1);
            }

            log.info("跳过冲突创建完成: recurringId={}, total={}, success={}, skipped={}", template.getId(), total, success, skipped);

            result.put("success", true);
            result.put("message", String.format("已创建 %d 天提醒，跳过 %d 天冲突", success, skipped));
            result.put("recurringId", template.getId());
            result.put("totalDays", total);
            result.put("successCount", success);
            result.put("skippedCount", skipped);
            if (!skippedDates.isEmpty()) {
                result.put("skippedDates", skippedDates);
            }
        } catch (Exception e) {
            log.error("跳过冲突创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            throw new RuntimeException("创建周期提醒失败: " + e.getMessage(), e);
        }
        return result;
    }

    // 全部覆盖：把冲突日原有提醒置为 CANCELLED，再创建全部日期
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateWithOverwrite(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String repeatDays,
            String location, String priority) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TimeUtils.TIME_SECONDS_FORMATTER);
            LocalDate parsedStartDate = LocalDate.parse(startDate);
            LocalDate parsedEndDate = LocalDate.parse(endDate);
            RepeatType parsedRepeatType = RepeatType.valueOf(repeatType);

            log.info("覆盖创建周期提醒: userId={}, title={}, time={}, {}~{}", userId, title, remindTime, startDate, endDate);

            RecurringReminder template = createRecurringTemplate(userId, title, parsedTime,
                    parsedStartDate, parsedEndDate, parsedRepeatType, repeatDays, location, priority);
            log.info("周期模板已保存: recurringId={}", template.getId());

            int total = 0, success = 0, overwritten = 0;
            List<String> overwrittenTitles = new ArrayList<>();
            LocalDate current = parsedStartDate;
            while (!current.isAfter(parsedEndDate)) {
                if (shouldGenerateForDate(template, current)
                        && !recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), current)) {
                    total++;
                    LocalDateTime remindDateTime = LocalDateTime.of(current, parsedTime);
                    ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, remindDateTime, null);

                    if (conflictResponse.isHasConflict() && conflictResponse.getConflicts() != null) {
                        for (ConflictResponse.ConflictItem conflict : conflictResponse.getConflicts()) {
                            Reminder conflictReminder = reminderRepository.findById(conflict.getId())
                                    .filter(r -> r.getUserId().equals(userId))
                                    .orElse(null);
                            if (conflictReminder != null) {
                                overwrittenTitles.add(conflictReminder.getTitle());
                                conflictReminder.setStatus(ReminderStatus.CANCELLED);
                                reminderRepository.save(conflictReminder);
                                log.info("覆盖冲突提醒: id={}, title={}, date={}", conflictReminder.getId(), conflictReminder.getTitle(), current);
                                overwritten++;
                            }
                        }
                    }

                    Reminder reminder = createReminderDirect(template, remindDateTime);
                    log.info("提醒已保存: id={}, title={}, time={}", reminder.getId(), reminder.getTitle(), remindDateTime);
                    saveInstance(template.getId(), current, reminder.getId(), "CREATED");
                    success++;
                }
                current = current.plusDays(1);
            }

            log.info("覆盖创建完成: recurringId={}, total={}, success={}, overwritten={}", template.getId(), total, success, overwritten);

            String overwrittenTitle = overwrittenTitles.isEmpty() ? null : String.join("、", overwrittenTitles);
            result.put("success", true);
            result.put("message", String.format("已创建 %d 天提醒，覆盖 %d 条冲突提醒", success, overwritten));
            result.put("recurringId", template.getId());
            result.put("totalDays", total);
            result.put("successCount", success);
            result.put("overwrittenCount", overwritten);
            if (overwrittenTitle != null) {
                result.put("overwrittenTitle", overwrittenTitle);
            }
        } catch (Exception e) {
            log.error("覆盖创建周期提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            throw new RuntimeException("覆盖创建周期提醒失败: " + e.getMessage(), e);
        }
        return result;
    }

    // ===== 私有辅助方法 =====

    // 创建并保存周期模板，状态置为 ACTIVE，来源标记为 AGENT
    private RecurringReminder createRecurringTemplate(Long userId, String title, LocalTime parsedTime,
            LocalDate parsedStartDate, LocalDate parsedEndDate, RepeatType parsedRepeatType,
            String repeatDays, String location, String priority) {
        RecurringReminder template = new RecurringReminder();
        template.setUserId(userId);
        template.setTitle(title);
        template.setRemindTime(parsedTime);
        template.setStartDate(parsedStartDate);
        template.setEndDate(parsedEndDate);
        template.setRepeatType(parsedRepeatType);
        template.setRepeatDays(repeatDays);
        template.setLocation(location);
        template.setPriority(priority != null ? priority : Priority.NORMAL.name());
        template.setStatus("ACTIVE");
        template.setSource("AGENT");
        template.setTimezone("Asia/Shanghai");
        return recurringReminderRepository.save(template);
    }

    // 根据模板与具体时间，直接落库一条 PENDING 提醒
    private Reminder createReminderDirect(RecurringReminder template, LocalDateTime remindDateTime) {
        Reminder reminder = new Reminder();
        reminder.setUserId(template.getUserId());
        reminder.setTitle(template.getTitle());
        reminder.setRemindTime(remindDateTime);
        reminder.setTimezone(template.getTimezone());
        reminder.setAdvanceMinutes(template.getAdvanceMinutes());
        reminder.setLocation(template.getLocation());
        reminder.setPriority(com.dormrepair.remindme.enums.Priority.valueOf(template.getPriority()));
        reminder.setStatus(ReminderStatus.PENDING);
        // 系统自动创建，跳过信息确认阶段，直接进入执行阶段，否则调度器扫描不到
        reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
        reminder.setSource(ReminderSource.USER_INPUT);
        return reminderRepository.save(reminder);
    }

    // 保存周期实例记录，关联周期 ID、日期与对应提醒 ID
    private RecurringInstance saveInstance(Long recurringId, LocalDate date, Long reminderId, String status) {
        RecurringInstance instance = new RecurringInstance();
        instance.setRecurringId(recurringId);
        instance.setRemindDate(date);
        instance.setReminderId(reminderId);
        instance.setStatus(status);
        return recurringInstanceRepository.save(instance);
    }

    // 确认回调后实际创建周期模板和实例
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmAndCreateRecurring(Long userId, String title, String remindTime,
            String startDate, String endDate, String repeatType, String repeatDays,
            String location, String priority) {

        Map<String, Object> result = new HashMap<>();

        try {
            LocalTime parsedTime = LocalTime.parse(remindTime, TimeUtils.TIME_SECONDS_FORMATTER);
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
            template.setRepeatDays(repeatDays);
            template.setLocation(location);
            template.setPriority(priority != null ? priority : Priority.NORMAL.name());
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
            throw new RuntimeException("创建周期提醒失败: " + e.getMessage(), e);
        }
        return result;
    }

    // 遍历日期范围生成实例，返回总数/成功/冲突/失败四项计数
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

    // 判断某日是否应生成实例：先校验是否落在周期范围内，再按重复类型过滤
    // DAILY 每天生成；WEEKLY 按 repeatDays 指定的星期几生成（为空时兼容旧数据退化为每天）；
    // MONTHLY 仅在每月与起始日相同的那一天生成
    // public 供 CalendarController 判断日历某天是否被周期模板覆盖（用于小红点/⟳标记）
    public boolean shouldGenerateForDate(RecurringReminder template, LocalDate date) {
        LocalDate start = template.getStartDate();
        LocalDate end = template.getEndDate();
        if (date.isBefore(start) || date.isAfter(end)) {
            return false;
        }

        RepeatType repeatType = template.getRepeatType();
        if (repeatType == RepeatType.DAILY) {
            return true;
        } else if (repeatType == RepeatType.WEEKLY) {
            String repeatDays = template.getRepeatDays();
            if (repeatDays == null || repeatDays.isBlank()) {
                // 兼容旧数据：未指定星期几时退化为每天都生成
                return true;
            }
            int dow = date.getDayOfWeek().getValue();  // ISO 1=周一…7=周日
            for (String token : repeatDays.split(",")) {
                try {
                    if (Integer.parseInt(token.trim()) == dow) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return false;
        } else if (repeatType == RepeatType.MONTHLY) {
            return date.getDayOfMonth() == start.getDayOfMonth();
        }
        return true;
    }

    // 为指定日期生成实例
    // 无冲突：直接落库提醒与实例；有冲突：不落实例，转交确认卡片流程处理
    // 两种情况都会更新模板的 lastGeneratedDate
    public Map<String, Object> generateInstanceForDate(RecurringReminder template, LocalDate date) {
        Map<String, Object> result = new HashMap<>();

        if (recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), date)) {
            log.info("🟡 周期实例已存在，跳过: recurringId={}, date={}", template.getId(), date);
            result.put("status", "skipped");
            result.put("message", "该日期已生成，跳过");
            return result;
        }

        LocalDateTime remindDateTime = LocalDateTime.of(date, template.getRemindTime());

        ConflictResponse conflictResponse = conflictDetectionService.checkConflict(
                template.getUserId(), remindDateTime, null);

        if (conflictResponse.isHasConflict()) {
            // 冲突分支：不创建 RecurringInstance（避免 reminder_id 为 null），由确认卡片统一处理
            log.info("⚠️ 日期 {} 检测到冲突，跳过实例创建，走确认卡片流程: title={}, time={}",
                    date, template.getTitle(), remindDateTime);

            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("title", template.getTitle());
            parsedData.put("remindTime", remindDateTime.format(TimeUtils.FORMATTER));
            parsedData.put("location", template.getLocation());
            parsedData.put("priority", template.getPriority());
            parsedData.put("advanceMinutes", template.getAdvanceMinutes());
            parsedData.put("source", "AGENT");
            parsedData.put("conflict", true);
            parsedData.put("conflictList", conflictResponse.getConflicts());
            // 标记为周期提醒，供确认服务判断
            parsedData.put("isRecurring", true);
            parsedData.put("recurringId", template.getId());
            parsedData.put("startDate", template.getStartDate().toString());
            parsedData.put("endDate", template.getEndDate().toString());
            parsedData.put("repeatType", template.getRepeatType().name());
            parsedData.put("recurringRemindTime", template.getRemindTime().format(TimeUtils.TIME_SECONDS_FORMATTER));

            confirmationService.createSession(template.getUserId(), template.getTitle(), parsedData);

            result.put("status", "conflict");
            result.put("message", "检测到冲突，已触发确认卡片");
        } else {
            log.info("🟡 准备保存提醒: title={}, time={}", template.getTitle(), remindDateTime);
            Reminder reminder = new Reminder();
            reminder.setUserId(template.getUserId());
            reminder.setTitle(template.getTitle());
            reminder.setRemindTime(remindDateTime);
            reminder.setTimezone(template.getTimezone());
            reminder.setAdvanceMinutes(template.getAdvanceMinutes());
            reminder.setLocation(template.getLocation());
            reminder.setPriority(com.dormrepair.remindme.enums.Priority.valueOf(template.getPriority()));
            reminder.setStatus(ReminderStatus.PENDING);
            // 系统自动创建，跳过信息确认阶段，直接进入执行阶段，否则调度器扫描不到
            reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
            reminder.setSource(ReminderSource.USER_INPUT);

            Reminder savedReminder = reminderRepository.save(reminder);
            log.info("🟢 提醒保存成功: id={}, title={}", savedReminder.getId(), savedReminder.getTitle());
            if (savedReminder.getId() == null) {
                log.error("🔴 警告：保存后 ID 为 null！");
                throw new IllegalStateException("保存提醒后 ID 为 null");
            }

            RecurringInstance instance = new RecurringInstance();
            instance.setRecurringId(template.getId());
            instance.setRemindDate(date);
            instance.setStatus("PENDING");

            instance.setReminderId(savedReminder.getId());
            instance.setStatus("CREATED");
            RecurringInstance savedInstance = recurringInstanceRepository.save(instance);
            log.info("🟢 周期实例保存成功: instanceId={}, reminderId={}", savedInstance.getId(), savedReminder.getId());

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

    // 查询某用户的全部 ACTIVE 周期模板
    public List<RecurringReminder> getRecurringRemindersByUser(Long userId) {
        return recurringReminderRepository.findByUserIdAndStatus(userId, "ACTIVE");
    }

    // 按主键查单个周期模板，附带所属用户校验
    public Optional<RecurringReminder> getRecurringReminderById(Long id, Long userId) {
        return recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId));
    }

    // 取消周期提醒：模板置为 CANCELLED，并级联取消所有已生成实例
    @Transactional(rollbackFor = Exception.class)
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

    // 暂停周期提醒：仅改状态为 PAUSED，不触动已有实例
    @Transactional(rollbackFor = Exception.class)
    public void pauseRecurringReminder(Long id, Long userId) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));
        reminder.setStatus("PAUSED");
        recurringReminderRepository.save(reminder);
    }

    // 恢复已暂停的周期提醒，状态回到 ACTIVE
    @Transactional(rollbackFor = Exception.class)
    public void resumeRecurringReminder(Long id, Long userId) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));
        reminder.setStatus("ACTIVE");
        recurringReminderRepository.save(reminder);
    }

    // 为所有 ACTIVE 模板补生成本日实例，返回实际生成条数
    @Transactional(rollbackFor = Exception.class)
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

    // 更新周期模板字段：仅入参非 null 的字段才覆盖，避免误清空
    @Transactional(rollbackFor = Exception.class)
    public RecurringReminder updateRecurringReminder(Long id, Long userId, String title,
            String remindTime, LocalDate startDate, LocalDate endDate, RepeatType repeatType,
            String repeatDays, String location, String priority) {
        RecurringReminder reminder = recurringReminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("周期提醒不存在或无权操作"));

        if (title != null) reminder.setTitle(title);
        if (remindTime != null) {
            reminder.setRemindTime(java.time.LocalTime.parse(remindTime, TimeUtils.TIME_SECONDS_FORMATTER));
        }
        if (startDate != null) reminder.setStartDate(startDate);
        if (endDate != null) reminder.setEndDate(endDate);
        if (repeatType != null) reminder.setRepeatType(repeatType);
        if (repeatDays != null) reminder.setRepeatDays(repeatDays);
        if (location != null) reminder.setLocation(location);
        if (priority != null) reminder.setPriority(priority);

        return recurringReminderRepository.save(reminder);
    }
}
