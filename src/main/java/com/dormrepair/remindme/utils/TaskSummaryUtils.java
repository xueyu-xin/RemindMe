package com.dormrepair.remindme.utils;

import com.dormrepair.remindme.dto.TaskDetail;
import com.dormrepair.remindme.enums.FinalStatus;
import com.dormrepair.remindme.enums.TaskStatus;

import java.util.List;

// 多任务执行结果汇总工具类。
// 遍历 TaskDetail 列表统计各状态数量，并按规则计算 FinalStatus。
// 提供 getSummaryLine() 根据最终状态返回对应的结论行文案。
public class TaskSummaryUtils {

    // 统计各状态数量，计算最终状态
    public static SummaryResult computeSummary(List<TaskDetail> tasks) {
        int total = tasks != null ? tasks.size() : 0;
        int successCount = 0;
        int overwrittenCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        if (tasks != null) {
            for (TaskDetail task : tasks) {
                if (task.getStatus() == null) continue;
                switch (task.getStatus()) {
                    case CONFIRMED -> successCount++;
                    case OVERWRITTEN -> overwrittenCount++;
                    case SKIPPED -> skippedCount++;
                    case FAILED -> failedCount++;
                    default -> {} // PENDING 不计入
                }
            }
        }

        FinalStatus finalStatus = computeFinalStatus(total, successCount, overwrittenCount, skippedCount, failedCount);

        return new SummaryResult(total, successCount, overwrittenCount, skippedCount, failedCount, finalStatus);
    }

    // 根据统计数据计算最终状态
    private static FinalStatus computeFinalStatus(int total, int success, int overwritten, int skipped, int failed) {
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

    // 根据最终状态返回对应的结论行文案
    public static String getSummaryLine(FinalStatus status, int total, int success, int overwritten, int skipped, int failed) {
        if (status == null) {
            return "✅ 操作完成。";
        }
        return switch (status) {
            case ALL_SUCCESS -> "✅ " + total + " 项提醒均已创建成功，届时会准时通知你。";
            case PARTIAL_OVERWRITE -> "✅ " + total + " 项提醒均已处理完成。";
            case ALL_OVERWRITTEN -> "✅ " + total + " 项提醒均已覆盖更新。";
            case PARTIAL_SKIPPED -> "✅ " + success + " 项提醒已创建，" + skipped + " 项已跳过。";
            case PARTIAL_FAILED -> "✅ " + success + " 项提醒已创建，" + failed + " 项创建失败，请稍后重试。";
        };
    }

    // 汇总结果内部类
    public static class SummaryResult {
        public final int total;
        public final int successCount;
        public final int overwrittenCount;
        public final int skippedCount;
        public final int failedCount;
        public final FinalStatus finalStatus;

        public SummaryResult(int total, int successCount, int overwrittenCount, int skippedCount, int failedCount, FinalStatus finalStatus) {
            this.total = total;
            this.successCount = successCount;
            this.overwrittenCount = overwrittenCount;
            this.skippedCount = skippedCount;
            this.failedCount = failedCount;
            this.finalStatus = finalStatus;
        }
    }
}
