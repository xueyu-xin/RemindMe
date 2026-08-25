package com.dormrepair.remindme.utils;

import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.enums.FinalStatus;
import com.dormrepair.remindme.enums.TaskStatus;

import java.util.List;

// 多任务执行结果汇总工具类。
// 遍历 TaskDetail 列表统计各状态数量，并按规则计算 FinalStatus。
// 提供 getSummaryLine() 根据最终状态返回对应的结论行文案。
public class TaskSummaryUtils {

    // 统计各状态数量，计算最终状态（兼容旧调用：仍保留原统计维度）
    public static SummaryResult computeSummary(List<TaskDetail> tasks) {
        int total = tasks != null ? tasks.size() : 0;
        int successCount = 0;       // CONFIRMED
        int overwrittenCount = 0;   // OVERWRITTEN
        int skippedCount = 0;       // SKIPPED
        int cancelledCount = 0;     // CANCELLED
        int failedCount = 0;        // FAILED

        if (tasks != null) {
            for (TaskDetail task : tasks) {
                if (task.getStatus() == null) continue;
                switch (task.getStatus()) {
                    case CONFIRMED -> successCount++;
                    case OVERWRITTEN -> overwrittenCount++;
                    case SKIPPED -> skippedCount++;
                    case CANCELLED -> cancelledCount++;
                    case FAILED -> failedCount++;
                    default -> {} // PENDING 不计入
                }
            }
        }

        FinalStatus legacyStatus = computeLegacyFinalStatus(total, successCount, overwrittenCount, skippedCount, failedCount);
        // 交互维度的 FinalStatus（用于 ReplyTemplate 选择模板）
        FinalStatus replyStatus = computeReplyFinalStatus(total, successCount, overwrittenCount, skippedCount, cancelledCount, failedCount);

        return new SummaryResult(total, successCount, overwrittenCount, skippedCount, cancelledCount, failedCount, legacyStatus, replyStatus);
    }

    // 原有统计维度（保持向后兼容：跳过/失败优先）
    private static FinalStatus computeLegacyFinalStatus(int total, int success, int overwritten, int skipped, int failed) {
        if (failed > 0) {
            return FinalStatus.PARTIAL_FAILED;
        }
        if (skipped > 0) {
            return FinalStatus.PARTIAL_SKIPPED;
        }
        if (overwritten == total && total > 0) {
            return FinalStatus.ALL_OVERWRITTEN;
        }
        if (overwritten > 0) {
            return FinalStatus.PARTIAL_OVERWRITE;
        }
        return FinalStatus.ALL_SUCCESS;
    }

    // 交互维度 FinalStatus（用于 ReplyTemplateService 选择不同的回复开头/结尾模板）
    // 规则：按 CONFIRMED/OVERWRITTEN（=创建类）、CANCELLED、SKIPPED、FAILED 四类组合判断
    private static FinalStatus computeReplyFinalStatus(int total, int success, int overwritten, int skipped, int cancelled, int failed) {
        if (total <= 0) {
            return FinalStatus.ALL_SUCCESS;
        }
        // 只要有 FAILED，优先走 PARTIAL_FAILED
        if (failed > 0) {
            return FinalStatus.PARTIAL_FAILED;
        }

        int createdCount = success + overwritten;   // 已创建（CONFIRMED + OVERWRITTEN）
        boolean hasCreated = createdCount > 0;
        boolean hasCancelled = cancelled > 0;
        boolean hasSkipped = skipped > 0;

        if (hasCreated && !hasCancelled && !hasSkipped) {
            return FinalStatus.ALL_CONFIRMED;
        }
        if (!hasCreated && hasCancelled && !hasSkipped) {
            return FinalStatus.ALL_CANCELLED;
        }
        if (!hasCreated && !hasCancelled && hasSkipped) {
            return FinalStatus.ALL_SKIPPED;
        }
        if (hasCreated && !hasCancelled && hasSkipped) {
            return FinalStatus.PARTIAL_CONFIRM_SKIP;
        }
        if (hasCreated && hasCancelled && !hasSkipped) {
            return FinalStatus.PARTIAL_CONFIRM_CANCEL;
        }
        if (!hasCreated && hasCancelled && hasSkipped) {
            return FinalStatus.PARTIAL_CANCEL_SKIP;
        }
        // 三态混合（创建+取消+跳过 或 仍含 PENDING）：回退到原有维度判断
        return computeLegacyFinalStatus(total, success, overwritten, skipped, failed);
    }

    // 根据最终状态返回对应的结论行文案（兼容新旧 FinalStatus）
    public static String getSummaryLine(FinalStatus status, int total, int success, int overwritten, int skipped, int failed) {
        if (status == null) {
            return "✅ 操作完成。";
        }
        int createdCount = success + overwritten;
        return switch (status) {
            // ===== 旧维度（向后兼容） =====
            case ALL_SUCCESS -> "✅ " + total + " 项提醒均已创建成功，届时会准时通知你。";
            case PARTIAL_OVERWRITE -> "✅ " + total + " 项提醒均已处理完成。";
            case ALL_OVERWRITTEN -> "✅ " + total + " 项提醒均已覆盖更新。";
            case PARTIAL_SKIPPED -> "✅ " + createdCount + " 项提醒已创建，" + skipped + " 项已跳过。";
            case PARTIAL_FAILED -> "✅ " + createdCount + " 项提醒已创建，" + failed + " 项创建失败，请稍后重试。";
            // ===== 新交互维度 =====
            case ALL_CONFIRMED -> "✅ 已创建 " + createdCount + " 项提醒，届时会准时通知你。";
            case ALL_CANCELLED -> "❌ 已取消所有提醒，未保存任何内容。";
            case ALL_SKIPPED -> "⏭️ 已跳过所有提醒，未创建任何内容。";
            case PARTIAL_CONFIRM_SKIP -> "✅ 已创建 " + createdCount + " 项提醒，⏭️ 跳过 " + skipped + " 项。";
            case PARTIAL_CONFIRM_CANCEL -> "✅ 已创建 " + createdCount + " 项提醒，❌ 取消 " + cancelled(total, createdCount, skipped, failed) + " 项。";
            case PARTIAL_CANCEL_SKIP -> "❌ 取消 " + cancelled(total, createdCount, skipped, failed) + " 项，⏭️ 跳过 " + skipped + " 项。";
        };
    }

    // 由 total - 其他三类反推 cancelled 数量（用于旧签名兜底，避免 getSummaryLine 扩参）
    private static int cancelled(int total, int created, int skipped, int failed) {
        int c = total - created - skipped - failed;
        return Math.max(c, 0);
    }

    // 汇总结果内部类
    public static class SummaryResult {
        public final int total;
        public final int successCount;       // CONFIRMED
        public final int overwrittenCount;   // OVERWRITTEN
        public final int skippedCount;       // SKIPPED
        public final int cancelledCount;     // CANCELLED（新增）
        public final int failedCount;        // FAILED
        /** 旧维度 FinalStatus（原有统计逻辑，含覆盖/跳过/失败） */
        public final FinalStatus finalStatus;
        /** 新交互维度 FinalStatus（供 ReplyTemplate 选择回复模板） */
        public final FinalStatus replyFinalStatus;

        @Deprecated
        public SummaryResult(int total, int successCount, int overwrittenCount, int skippedCount, int failedCount, FinalStatus finalStatus) {
            this(total, successCount, overwrittenCount, skippedCount, 0, failedCount, finalStatus, finalStatus);
        }

        public SummaryResult(int total, int successCount, int overwrittenCount, int skippedCount, int cancelledCount, int failedCount,
                             FinalStatus finalStatus, FinalStatus replyFinalStatus) {
            this.total = total;
            this.successCount = successCount;
            this.overwrittenCount = overwrittenCount;
            this.skippedCount = skippedCount;
            this.cancelledCount = cancelledCount;
            this.failedCount = failedCount;
            this.finalStatus = finalStatus;
            this.replyFinalStatus = replyFinalStatus;
        }
    }
}
