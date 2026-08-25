package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.enums.FinalStatus;
import com.dormrepair.remindme.enums.TaskStatus;
import com.dormrepair.remindme.utils.TaskSummaryUtils;
import com.dormrepair.remindme.utils.TaskSummaryUtils.SummaryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// 多任务确认的最终回复文本生成服务。
// 根据 TaskDetail 列表生成结构化回复，按 replyFinalStatus 选择不同的开头 / 分段 / 结尾模板。
@Slf4j
@Service
public class ReplyTemplateService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");

    // 构建回复文本：根据 replyFinalStatus 选择不同模板
    public String buildReply(String userInput, List<TaskDetail> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "✅ 操作完成。";
        }

        SummaryResult summary = TaskSummaryUtils.computeSummary(tasks);
        FinalStatus rs = summary.replyFinalStatus != null ? summary.replyFinalStatus : summary.finalStatus;

        // 按状态将任务分组（用于分段渲染列表）
        List<TaskDetail> createdTasks = new ArrayList<>(); // CONFIRMED / OVERWRITTEN
        List<TaskDetail> cancelledTasks = new ArrayList<>();
        List<TaskDetail> skippedTasks = new ArrayList<>();
        List<TaskDetail> failedTasks = new ArrayList<>();
        for (TaskDetail t : tasks) {
            TaskStatus st = t.getStatus() == null ? TaskStatus.PENDING : t.getStatus();
            switch (st) {
                case CONFIRMED, OVERWRITTEN -> createdTasks.add(t);
                case CANCELLED -> cancelledTasks.add(t);
                case SKIPPED -> skippedTasks.add(t);
                case FAILED -> failedTasks.add(t);
                default -> createdTasks.add(t); // PENDING 兜底放入已创建段（避免丢失）
            }
        }

        StringBuilder sb = new StringBuilder();
        int createdCount = summary.successCount + summary.overwrittenCount;

        // ===== 第一段：操作确认（按 finalStatus 区分） =====
        switch (rs) {
            case ALL_CONFIRMED, ALL_SUCCESS, PARTIAL_OVERWRITE, ALL_OVERWRITTEN ->
                    sb.append("✅ 已创建 ").append(createdCount).append(" 项提醒：\n\n");
            case ALL_CANCELLED ->
                    sb.append("❌ 已取消所有提醒，未保存任何内容。\n\n");
            case ALL_SKIPPED ->
                    sb.append("⏭️ 已跳过所有提醒，未创建任何内容。\n\n");
            case PARTIAL_CONFIRM_SKIP ->
                    sb.append("✅ 已创建 ").append(createdCount).append(" 项提醒：\n\n");
            case PARTIAL_CONFIRM_CANCEL ->
                    sb.append("✅ 已创建 ").append(createdCount).append(" 项提醒：\n\n");
            case PARTIAL_CANCEL_SKIP ->
                    sb.append("❌ 未创建任何提醒（已取消 ").append(summary.cancelledCount).append(" 项，已跳过 ").append(summary.skippedCount).append(" 项）。\n\n");
            case PARTIAL_FAILED ->
                    sb.append("⚠️ 已创建 ").append(createdCount).append(" 项提醒，").append(summary.failedCount).append(" 项创建失败：\n\n");
        }

        // ===== 第二段：任务清单（按分段分组渲染；ALL_CANCELLED / ALL_SKIPPED 也保留清单便于回溯） =====
        if (!createdTasks.isEmpty() && needCreatedList(rs)) {
            for (TaskDetail task : createdTasks) {
                sb.append(formatTaskLine(task)).append("\n");
                sb.append(formatTaskMessage(task));
            }
        }
        if (!cancelledTasks.isEmpty() && (rs == FinalStatus.PARTIAL_CONFIRM_CANCEL || rs == FinalStatus.ALL_CANCELLED || rs == FinalStatus.PARTIAL_CANCEL_SKIP)) {
            if (rs != FinalStatus.ALL_CANCELLED && rs != FinalStatus.PARTIAL_CANCEL_SKIP) {
                sb.append("\n❌ 已取消 ").append(cancelledTasks.size()).append(" 项：\n\n");
            }
            if (rs == FinalStatus.ALL_CANCELLED || rs == FinalStatus.PARTIAL_CANCEL_SKIP) {
                // 全取消 / 取消+跳过 时，任务列表紧跟在开头之后（不再加一段小标题）
            }
            for (TaskDetail task : cancelledTasks) {
                sb.append(formatTaskLine(task)).append("\n");
                sb.append(formatTaskMessage(task));
            }
        }
        if (!skippedTasks.isEmpty() && (rs == FinalStatus.PARTIAL_CONFIRM_SKIP || rs == FinalStatus.ALL_SKIPPED || rs == FinalStatus.PARTIAL_CANCEL_SKIP)) {
            if (rs == FinalStatus.PARTIAL_CONFIRM_SKIP) {
                sb.append("\n⏭️ 已跳过 ").append(skippedTasks.size()).append(" 项：\n\n");
            }
            for (TaskDetail task : skippedTasks) {
                sb.append(formatTaskLine(task)).append("\n");
                sb.append(formatTaskMessage(task));
            }
        }
        if (!failedTasks.isEmpty()) {
            for (TaskDetail task : failedTasks) {
                sb.append(formatTaskLine(task)).append("\n");
                sb.append(formatTaskMessage(task));
            }
        }
        // ALL_CANCELLED / ALL_SKIPPED 兜底：如果没有渲染任何列表（空 created/cancelled/skipped），也列出全部
        if ((rs == FinalStatus.ALL_CANCELLED && cancelledTasks.isEmpty()) ||
                (rs == FinalStatus.ALL_SKIPPED && skippedTasks.isEmpty()) ||
                (rs == FinalStatus.PARTIAL_CANCEL_SKIP && cancelledTasks.isEmpty() && skippedTasks.isEmpty())) {
            for (TaskDetail task : tasks) {
                sb.append(formatTaskLine(task)).append("\n");
                sb.append(formatTaskMessage(task));
            }
        }

        // ===== 第三段：结论行 =====
        // 用户要求：ALL_CONFIRMED 结尾是"届时会准时通知你。"（直接写入结论行，不加额外前缀）
        String summaryLine = TaskSummaryUtils.getSummaryLine(
                summary.finalStatus, summary.total, summary.successCount,
                summary.overwrittenCount, summary.skippedCount, summary.failedCount);
        // 若为新交互维度，则按用户需求的简洁结论行重写（避免与旧版重复"已创建N项"）
        switch (rs) {
            case ALL_CONFIRMED -> summaryLine = "届时会准时通知你。";
            case ALL_CANCELLED, ALL_SKIPPED, PARTIAL_CANCEL_SKIP ->
                    summaryLine = ""; // 这类场景开头已经写清楚了，结论段留空
            case PARTIAL_CONFIRM_SKIP, PARTIAL_CONFIRM_CANCEL ->
                    summaryLine = ""; // 这类场景开头 + 中间小标题也写清楚了，结论段留空
        }
        if (!summaryLine.isBlank()) {
            sb.append("\n").append(summaryLine);
        }

        // ===== 第四段：操作引导（按 finalStatus 区分） =====
        String guide = switch (rs) {
            case ALL_SUCCESS, ALL_CONFIRMED, PARTIAL_OVERWRITE, ALL_OVERWRITTEN, PARTIAL_FAILED ->
                    "💡 如需修改或取消，随时告诉我就好～";
            case ALL_CANCELLED, ALL_SKIPPED, PARTIAL_CANCEL_SKIP ->
                    "💡 如需重新设置，随时告诉我就好～";
            case PARTIAL_CONFIRM_SKIP, PARTIAL_CONFIRM_CANCEL, PARTIAL_SKIPPED ->
                    "💡 如需调整，随时告诉我就好～";
        };
        sb.append("\n").append(guide);

        log.info("生成多任务回复: total={}, finalStatus={}, replyStatus={}", summary.total, summary.finalStatus, rs);
        return sb.toString();
    }

    // 哪些状态需要渲染"已创建"段任务列表
    private boolean needCreatedList(FinalStatus rs) {
        return rs == FinalStatus.ALL_CONFIRMED
                || rs == FinalStatus.ALL_SUCCESS
                || rs == FinalStatus.PARTIAL_OVERWRITE
                || rs == FinalStatus.ALL_OVERWRITTEN
                || rs == FinalStatus.PARTIAL_CONFIRM_SKIP
                || rs == FinalStatus.PARTIAL_CONFIRM_CANCEL
                || rs == FinalStatus.PARTIAL_FAILED;
    }

    // 单行任务条目：序号. 状态emoji 标题 — 时间 · 地点
    private String formatTaskLine(TaskDetail task) {
        String emoji = task.getStatus() != null ? task.getStatus().getEmoji() : "⏳";
        String timeStr = formatTime(task.getRemindTime());
        String locationStr = task.getLocation() != null && !task.getLocation().isBlank()
                ? " · " + task.getLocation() : "";
        return emoji + " " +
                (task.getIndex() + 1) + ". " +
                task.getTitle() +
                " — " + timeStr +
                locationStr;
    }

    // 附加消息（如冲突覆盖提示、失败原因）
    private String formatTaskMessage(TaskDetail task) {
        if (task.getMessage() == null || task.getMessage().isBlank()) {
            return "";
        }
        return "    " + task.getMessage() + "\n";
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
