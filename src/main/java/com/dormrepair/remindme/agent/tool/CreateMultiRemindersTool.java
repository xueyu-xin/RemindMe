package com.dormrepair.remindme.agent.tool;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.agent.event.EventPublisher;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.service.ConfirmationService;
import com.dormrepair.remindme.service.ConflictDetectionService;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 多项事务批量创建 Agent 工具。
// 当 LLM 识别到用户一句话包含多项提醒意图（如"明天3点开会、4点拿快递、5点去超市"）时调用，
// 直接批量创建提醒并返回执行结果。
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMultiRemindersTool {

    private final ReminderRepository reminderRepository;
    private final EventPublisher eventPublisher;
    private final ConflictDetectionService conflictDetectionService;
    private final ConfirmationService confirmationService;

    // 去重检查时考虑的"仍有效"状态：待推送 / 已推送待确认 / 已推送
    // 已取消、已完成、已过期的不计入重复
    private static final List<ReminderStatus> ACTIVE_STATUSES = List.of(
            ReminderStatus.PENDING, ReminderStatus.SENT, ReminderStatus.REMINDED);

    // Agent 工具：批量创建多个提醒确认卡片
    // userId 用户 ID（系统注入）；items 事务列表，每项含 title/remindTime 等字段；source 来源 text/excel/pdf/image
    @AgentTool(name = "create_multi_reminders",
            description = "批量创建多个提醒。当用户一句话中包含多项事务（如'明天3点开会、4点拿快递、5点去超市'）时调用。"
                    + "items: 事务数组，每项含 title(标题,必填)、remindTime(提醒时间 yyyy-MM-dd HH:mm:ss,必填)、"
                    + "location(地点,可选)、note(备注,可选)、priority(优先级 LOW/NORMAL/HIGH,可选)、advanceMinutes(提前分钟数,可选)。"
                    + "source: 来源标识 text/excel/pdf/image。"
                    + "conflictStrategy: 冲突处理策略。"
                    + "ASK（默认，无冲突项直接创建，冲突项汇总推卡片询问用户）/"
                    + "OVERWRITE（无冲突项直接创建，冲突项覆盖已有后创建）/"
                    + "SKIP（无冲突项直接创建，冲突项跳过）/"
                    + "ALLOW_COEXIST（全部直接创建，冲突项并存）")
    public Map<String, Object> createMultiRemindersForAgent(Long userId, List<Map<String, Object>> items,
            String source, String conflictStrategy) {
        log.info("批量创建提醒: userId={}, itemCount={}, source={}, conflictStrategy={}", userId,
                items != null ? items.size() : 0, source, conflictStrategy);

        Map<String, Object> result = new HashMap<>();

        if (userId == null) {
            result.put("success", false);
            result.put("message", "用户未登录");
            return result;
        }
        if (items == null || items.isEmpty()) {
            result.put("success", false);
            result.put("message", "事务列表不能为空");
            return result;
        }

        // 标准化每条事务：确保 title、remindTime 为字符串，补齐优先级默认值
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> raw = items.get(i);
            if (raw == null) {
                continue;
            }
            Object titleObj = raw.get("title");
            Object timeObj = raw.get("remindTime");
            String title = titleObj != null ? String.valueOf(titleObj).trim() : null;
            String remindTime = timeObj != null ? String.valueOf(timeObj).trim() : null;
            if (title == null || title.isBlank() || remindTime == null || remindTime.isBlank()) {
                result.put("success", false);
                result.put("message", "第 " + (i + 1) + " 项缺少标题或提醒时间，请补充完整");
                return result;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("title", title);
            item.put("remindTime", remindTime);
            item.put("endTime", raw.get("endTime") != null ? String.valueOf(raw.get("endTime")) : null);
            item.put("location", raw.get("location") != null ? String.valueOf(raw.get("location")) : null);
            item.put("note", raw.get("note") != null ? String.valueOf(raw.get("note")) : null);
            item.put("priority", raw.get("priority") != null ? String.valueOf(raw.get("priority")) : Priority.NORMAL.name());
            item.put("advanceMinutes", raw.get("advanceMinutes"));
            normalized.add(item);
        }

        if (normalized.isEmpty()) {
            result.put("success", false);
            result.put("message", "事务列表不能为空");
            return result;
        }

        // ===== 去重检查：按 (userId, title, remindTime) 精确匹配，状态在 PENDING/SENT/REMINDED 的视为已存在 =====
        List<Map<String, Object>> toCreate = new ArrayList<>();
        List<String> skippedTitles = new ArrayList<>();

        for (Map<String, Object> item : normalized) {
            String title = (String) item.get("title");
            String remindTimeStr = (String) item.get("remindTime");
            try {
                LocalDateTime remindTime = LocalDateTime.parse(remindTimeStr, TimeUtils.FORMATTER);
                long exists = reminderRepository
                        .countByUserIdAndTitleAndRemindTimeAndStatusIn(userId, title, remindTime, ACTIVE_STATUSES);
                if (exists > 0) {
                    skippedTitles.add(title + "(" + remindTimeStr + ")");
                    log.info("去重检查：跳过已存在提醒 userId={}, title={}, time={}", userId, title, remindTimeStr);
                    continue;
                }
            } catch (Exception e) {
                // 时间解析失败时不阻塞流程，保留该条让后续创建逻辑报错
                log.warn("去重检查时间解析失败，保留该条: title={}, time={}, err={}", title, remindTimeStr, e.getMessage());
            }
            toCreate.add(item);
        }

        if (toCreate.isEmpty()) {
            // 所有 item 已存在，无需重复创建
            result.put("success", false);
            result.put("message", "以下提醒已存在，无需重复创建：" + String.join("、", skippedTitles));
            result.put("skippedCount", skippedTitles.size());
            result.put("skippedTitles", String.join("、", skippedTitles));
            return result;
        }

        // ===== 逐条冲突检测：分流为无冲突项 / 冲突项 =====
        List<Map<String, Object>> noConflictItems = new ArrayList<>();
        List<Map<String, Object>> conflictItems = new ArrayList<>();
        for (Map<String, Object> item : toCreate) {
            try {
                LocalDateTime remindTime = LocalDateTime.parse((String) item.get("remindTime"), TimeUtils.FORMATTER);
                LocalDateTime endTime = item.get("endTime") != null
                        ? LocalDateTime.parse((String) item.get("endTime"), TimeUtils.FORMATTER) : null;
                ConflictResponse conflictResponse = conflictDetectionService.checkConflict(userId, remindTime, endTime, null);
                if (conflictResponse.isHasConflict()) {
                    item.put("conflicts", conflictResponse.getConflicts());
                    conflictItems.add(item);
                } else {
                    noConflictItems.add(item);
                }
            } catch (Exception e) {
                // 冲突检测失败不阻塞流程，按无冲突处理
                log.warn("批量创建冲突检测失败，按无冲突处理: item={}, err={}", item, e.getMessage());
                noConflictItems.add(item);
            }
        }

        String strategy = (conflictStrategy != null && !conflictStrategy.isBlank())
                ? conflictStrategy.toUpperCase() : "ASK";

        // ===== 无冲突项：无论什么策略都直接创建 =====
        List<Long> reminderIds = new ArrayList<>();
        int created = 0;
        int failed = 0;
        for (Map<String, Object> item : noConflictItems) {
            try {
                Reminder saved = createSingleReminder(userId, item);
                reminderIds.add(saved.getId());
                created++;
            } catch (Exception e) {
                failed++;
                log.warn("批量创建单条失败: userId={}, item={}, err={}", userId, item, e.getMessage());
            }
        }

        // ===== 冲突项：按策略分流 =====
        String sessionId = null;
        switch (strategy) {
            case "OVERWRITE":
                for (Map<String, Object> item : conflictItems) {
                    try {
                        // 取消冲突提醒后创建新提醒
                        @SuppressWarnings("unchecked")
                        List<ConflictResponse.ConflictItem> conflicts =
                                (List<ConflictResponse.ConflictItem>) item.get("conflicts");
                        if (conflicts != null) {
                            for (ConflictResponse.ConflictItem c : conflicts) {
                                reminderRepository.findById(c.getId()).ifPresent(r -> {
                                    r.setStatus(ReminderStatus.CANCELLED);
                                    reminderRepository.save(r);
                                });
                            }
                        }
                        Reminder saved = createSingleReminder(userId, item);
                        reminderIds.add(saved.getId());
                        created++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("覆盖创建单条失败: userId={}, item={}, err={}", userId, item, e.getMessage());
                    }
                }
                break;
            case "SKIP":
                for (Map<String, Object> item : conflictItems) {
                    skippedTitles.add(item.get("title") + "(冲突跳过)");
                }
                log.info("批量创建跳过冲突项: userId={}, count={}", userId, conflictItems.size());
                break;
            case "ALLOW_COEXIST":
                for (Map<String, Object> item : conflictItems) {
                    try {
                        Reminder saved = createSingleReminder(userId, item);
                        reminderIds.add(saved.getId());
                        created++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("并存创建单条失败: userId={}, item={}, err={}", userId, item, e.getMessage());
                    }
                }
                break;
            case "ASK":
            default:
                if (!conflictItems.isEmpty()) {
                    ConfirmationCardResponse card = confirmationService.createMultiSession(
                            userId, conflictItems, source != null ? source : "text", true);
                    sessionId = card.getData().getSessionId();
                    log.info("批量创建推送冲突汇总卡片: userId={}, sessionId={}, conflictCount={}",
                            userId, sessionId, conflictItems.size());
                }
                break;
        }

        // 全部失败：返回 success=false
        if (created == 0 && failed > 0) {
            result.put("success", false);
            result.put("executed", true);
            result.put("reminderIds", reminderIds);
            result.put("createdCount", created);
            result.put("failedCount", failed);
            result.put("message", "已创建 " + created + " 项，失败 " + failed + " 项");
            result.put("taskCompleted", true);
            return result;
        }

        result.put("success", true);
        result.put("executed", true);
        result.put("reminderIds", reminderIds);
        result.put("createdCount", created);
        result.put("failedCount", failed);
        if (!skippedTitles.isEmpty()) {
            result.put("skippedCount", skippedTitles.size());
            result.put("skippedTitles", String.join("、", skippedTitles));
        }

        // ASK 策略且有冲突待处理：返回会话信息，走确认分支
        if (sessionId != null) {
            result.put("sessionId", sessionId);
            result.put("confirmationRequired", true);
            result.put("conflictCount", conflictItems.size());
        }

        // message 文案
        if (sessionId != null) {
            result.put("message", created > 0
                    ? "已创建 " + created + " 项提醒，" + conflictItems.size() + " 项存在冲突，请处理"
                    : conflictItems.size() + " 项提醒存在冲突，请选择处理方式");
        } else if ("SKIP".equals(strategy) && !conflictItems.isEmpty()) {
            result.put("message", created > 0
                    ? "已创建 " + created + " 项提醒，跳过 " + conflictItems.size() + " 项冲突"
                    : conflictItems.size() + " 项提醒因冲突已全部跳过");
        } else if (failed > 0) {
            result.put("message", "已创建 " + created + " 项，失败 " + failed + " 项");
        } else {
            result.put("message", "已创建 " + created + " 项提醒 ✅");
        }
        result.put("taskCompleted", true);

        return result;
    }

    // 创建单条提醒并发布创建事件
    private Reminder createSingleReminder(Long userId, Map<String, Object> item) {
        String title = (String) item.get("title");
        LocalDateTime remindTime = LocalDateTime.parse((String) item.get("remindTime"), TimeUtils.FORMATTER);
        String location = (String) item.get("location");
        String note = (String) item.get("note");
        String priority = (String) item.get("priority");
        Object advMinObj = item.get("advanceMinutes");
        int advMin = 0;
        if (advMinObj != null) {
            advMin = Integer.parseInt(String.valueOf(advMinObj));
        }

        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setTitle(title);
        reminder.setRemindTime(remindTime);
        if (item.get("endTime") != null) {
            reminder.setEndTime(LocalDateTime.parse((String) item.get("endTime"), TimeUtils.FORMATTER));
        }
        reminder.setLocation(location);
        reminder.setNote(note);
        reminder.setAdvanceMinutes(advMin);
        reminder.setPriority(priority != null ? Priority.valueOf(priority.toUpperCase()) : Priority.NORMAL);
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
        reminder.setExecutionStatus(ExecutionStatus.PENDING);
        reminder.setSource(ReminderSource.USER_INPUT);
        reminder.setTimezone("Asia/Shanghai");
        Reminder saved = reminderRepository.save(reminder);

        eventPublisher.publishReminderCreated(userId, saved.getId(), title,
                (String) item.get("remindTime"), location, false);
        return saved;
    }
}
