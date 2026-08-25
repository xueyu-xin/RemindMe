package com.dormrepair.remindme.agent.tool;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.service.ConfirmationService;
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
// 内部委托 ConfirmationService.createMultiSession 创建多项确认卡片，由用户逐条确认。
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMultiRemindersTool {

    private final ConfirmationService confirmationService;
    private final ReminderRepository reminderRepository;

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
                    + "source: 来源标识 text/excel/pdf/image。")
    public Map<String, Object> createMultiRemindersForAgent(Long userId, List<Map<String, Object>> items, String source) {
        log.info("Agent调用批量创建提醒: userId={}, itemCount={}, source={}", userId,
                items != null ? items.size() : 0, source);

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
                // 时间解析失败时不阻塞流程，保留该条让后续 createMultiSession 报错
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

        String src = source != null ? source : "text";
        try {
            ConfirmationCardResponse card = confirmationService.createMultiSession(userId, toCreate, src);

            result.put("success", true);
            result.put("sessionId", card.getData() != null ? card.getData().getSessionId() : null);
            result.put("totalCount", card.getData() != null ? card.getData().getTotalCount() : toCreate.size());
            if (!skippedTitles.isEmpty()) {
                // 部分跳过场景：向 LLM 透传跳过信息，便于回复时说明
                result.put("skippedCount", skippedTitles.size());
                result.put("skippedTitles", String.join("、", skippedTitles));
                result.put("message", "已识别 " + toCreate.size() + " 项待创建事务，跳过 "
                        + skippedTitles.size() + " 项（已存在）：" + String.join("、", skippedTitles));
            } else {
                result.put("message", "已识别 " + toCreate.size() + " 项事务，请确认");
            }
            result.put("confirmationRequired", true);
            result.put("shouldStop", true);
        } catch (Exception e) {
            log.error("批量创建提醒失败: userId={}, error={}", userId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "批量创建失败: " + e.getMessage());
        }

        return result;
    }
}
