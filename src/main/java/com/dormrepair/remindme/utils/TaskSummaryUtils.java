package com.dormrepair.remindme.utils;

import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.enums.FinalStatus;
import com.dormrepair.remindme.enums.TaskStatus;

import java.util.List;

// 多任务执行结果汇总工具类。
// 双轨语义：
// - 业务结果轨道（legacy FinalStatus）：跳过/失败优先，覆盖量整体区分，用于历史调用方（如持久化 session.final_status）。
// - 交互结果轨道（reply FinalStatus）：按 CONFIRMED+OVERWRITTEN / CANCELLED / SKIPPED / FAILED 四象限组合，用于 ReplyTemplateService 选择回复模板。
// 提供 countStatuses / computeLegacyFinalStatus / computeReplyFinalStatus 三个独立公共入口 + computeSummary 一次性汇总。
public class TaskSummaryUtils {

    // ===== P2-1-1：计数阶段，独立成公共方法 =====

    /** 中间计数载体，供双轨 FinalStatus 独立方法直接消费，避免重复遍历 tasks。 */
    public static class StatusCounts {
        public final int total;
        public final int successCount;     // CONFIRMED
        public final int overwrittenCount; // OVERWRITTEN
        public final int skippedCount;     // SKIPPED
        public final int cancelledCount;   // CANCELLED
        public final int failedCount;      // FAILED

        public StatusCounts(int total, int successCount, int overwrittenCount,
                            int skippedCount, int cancelledCount, int failedCount) {
            this.total = total;
            this.successCount = successCount;
            this.overwrittenCount = overwrittenCount;
            this.skippedCount = skippedCount;
            this.cancelledCount = cancelledCount;
            this.failedCount = failedCount;
        }

        /** CONFIRMED + OVERWRITTEN：交互维度中的"已创建"类，用于 reply 轨判断 */
        public int createdCount() { return successCount + overwrittenCount; }
    }

    /** 统计各状态数量，不做任何 FinalStatus 判定。 */
    public static StatusCounts countStatuses(List<TaskDetail> tasks) {
        int total = tasks != null ? tasks.size() : 0;
        int successCount = 0;
        int overwrittenCount = 0;
        int skippedCount = 0;
        int cancelledCount = 0;
        int failedCount = 0;

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
        return new StatusCounts(total, successCount, overwrittenCount, skippedCount, cancelledCount, failedCount);
    }

    // ===== P2-1-2：两条轨道的独立 FinalStatus 计算（均暴露 public 独立方法）=====

    /**
     * 业务结果轨道：原有统计维度（跳过/失败优先，覆盖整体区分）。
     * 等价于旧版 SummaryResult.finalStatus，保持向后兼容。
     */
    public static FinalStatus computeLegacyFinalStatus(List<TaskDetail> tasks) {
        StatusCounts c = countStatuses(tasks);
        return computeLegacyFinalStatus(c.total, c.successCount, c.overwrittenCount, c.skippedCount, c.failedCount);
    }

    /**
     * 交互结果轨道：按"创建类 / 取消 / 跳过 / 失败"四象限组合判定。
     * 等价于旧版 SummaryResult.replyFinalStatus，用于 ReplyTemplateService 选择不同回复模板。
     */
    public static FinalStatus computeReplyFinalStatus(List<TaskDetail> tasks) {
        StatusCounts c = countStatuses(tasks);
        return computeReplyFinalStatus(c.total, c.successCount, c.overwrittenCount, c.skippedCount, c.cancelledCount, c.failedCount);
    }

    // ===== P2-1-3：一次性汇总入口（调用以上三块组合）=====

    /** 一次性汇总入口：计数 + 双轨 FinalStatus 计算 + 组装 SummaryResult。 */
    public static SummaryResult computeSummary(List<TaskDetail> tasks) {
        StatusCounts c = countStatuses(tasks);
        FinalStatus legacyStatus = computeLegacyFinalStatus(c.total, c.successCount, c.overwrittenCount, c.skippedCount, c.failedCount);
        FinalStatus replyStatus = computeReplyFinalStatus(c.total, c.successCount, c.overwrittenCount, c.skippedCount, c.cancelledCount, c.failedCount);
        return new SummaryResult(c.total, c.successCount, c.overwrittenCount, c.skippedCount, c.cancelledCount, c.failedCount, legacyStatus, replyStatus);
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
