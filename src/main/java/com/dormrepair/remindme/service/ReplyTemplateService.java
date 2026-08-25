package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.utils.TaskSummaryUtils;
import com.dormrepair.remindme.utils.TaskSummaryUtils.SummaryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// 多任务确认的最终回复文本生成服务。
// 根据 TaskDetail 列表生成四段式回复：操作确认 → 任务清单 → 结论行 → 操作引导。
// 集成点：ConfirmationService 中 allProcessed=true 时调用 buildReply() 替代简单 message。
@Slf4j
@Service
public class ReplyTemplateService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");

    // 构建四段式回复文本
    // userInput 用户原始输入（当前未使用，预留扩展）
    // tasks 任务列表
    // 返回四段式回复文本
    public String buildReply(String userInput, List<TaskDetail> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "✅ 操作完成。";
        }

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);

        StringBuilder sb = new StringBuilder();

        // 第一段：操作确认
        sb.append("⏰ 好的，已按你的要求完成设置：\n\n");

        // 第二段：任务清单
        for (TaskDetail task : tasks) {
            String emoji = task.getStatus() != null ? task.getStatus().getEmoji() : "⏳";
            String timeStr = formatTime(task.getRemindTime());
            String locationStr = task.getLocation() != null && !task.getLocation().isBlank()
                    ? " · " + task.getLocation() : "";
            sb.append(emoji).append(" ")
                    .append(task.getIndex() + 1).append(". ")
                    .append(task.getTitle())
                    .append(" — ").append(timeStr)
                    .append(locationStr)
                    .append("\n");
            // 如果有附加消息，换行缩进显示
            if (task.getMessage() != null && !task.getMessage().isBlank()) {
                sb.append("    ").append(task.getMessage()).append("\n");
            }
        }

        // 第三段：结论行
        sb.append("\n");
        sb.append(TaskSummaryUtils.getSummaryLine(
                summary.finalStatus, summary.total, summary.successCount,
                summary.overwrittenCount, summary.skippedCount, summary.failedCount));

        // 第四段：操作引导
        sb.append("\n💡 如需修改或取消，随时告诉我就好～");

        log.info("生成多任务回复: total={}, finalStatus={}", summary.total, summary.finalStatus);
        return sb.toString();
    }

    // 时间格式化：今天/明天/其他日期
    private String formatTime(LocalDateTime remindTime) {
        if (remindTime == null) {
            return "时间待定";
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate taskDate = remindTime.toLocalDate();
        String timeStr = remindTime.toLocalTime().format(TIME_FORMATTER);

        if (taskDate.equals(today)) {
            int hour = remindTime.getHour();
            if (hour >= 18) {
                return "今晚 " + timeStr;
            } else {
                return "今天 " + timeStr;
            }
        } else if (taskDate.equals(tomorrow)) {
            return "明天 " + timeStr;
        } else {
            return remindTime.format(DATE_TIME_FORMATTER);
        }
    }
}
