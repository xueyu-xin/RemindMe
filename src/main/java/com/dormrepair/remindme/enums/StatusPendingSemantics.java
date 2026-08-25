package com.dormrepair.remindme.enums;

import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;

/**
 * P2-3：5 套 PENDING 语义统一入口。
 * 保持各枚举独立（不同业务粒度：会话级 / 子任务级 / 生命周期推送前 / 推送前信息确认 / 推送后执行确认），
 * 但对外提供 isPending() / getPendingLabel() 的统一判断与解释能力，避免业务代码出现 5 层 instanceof 判断。
 */
public final class StatusPendingSemantics {

    private StatusPendingSemantics() {
    }

    // ===== 统一 PENDING 判断：覆盖 5 个枚举 =====

    public static boolean isPending(TaskStatus status) {
        return status == TaskStatus.PENDING;
    }

    public static boolean isPending(ReminderStatus status) {
        return status == ReminderStatus.PENDING;
    }

    public static boolean isPending(ConfirmStatus status) {
        return status == ConfirmStatus.PENDING;
    }

    public static boolean isPending(ExecutionStatus status) {
        return status == ExecutionStatus.PENDING;
    }

    public static boolean isPending(SessionStatus status) {
        return status == SessionStatus.PENDING;
    }

    /** 通用 PENDING 判断入口（Object 重载，用于 JSON/Map 动态传值场景）。 */
    public static boolean isPending(Object statusValue) {
        if (statusValue == null) {
            return false;
        }
        return switch (statusValue) {
            case TaskStatus ts -> isPending(ts);
            case ReminderStatus rs -> isPending(rs);
            case ConfirmStatus cs -> isPending(cs);
            case ExecutionStatus es -> isPending(es);
            case SessionStatus ss -> isPending(ss);
            case String s -> s.equalsIgnoreCase("PENDING");
            case Enum<?> e -> e.name().equals("PENDING");
            default -> false;
        };
    }

    // ===== 语义分类：用户交互阶段 PENDING vs 系统后台阶段 PENDING =====

    /**
     * 是否属于"等待用户交互"阶段的 PENDING。
     * - TaskStatus.PENDING（子任务待勾选/确认）
     * - ConfirmStatus.PENDING（单提醒信息确认待点按钮）
     * - SessionStatus.PENDING（整会话卡片待操作）
     * - ExecutionStatus.PENDING（已推送待点"已执行"，也是用户交互）
     */
    public static boolean isUserDecisionPending(Object statusValue) {
        if (statusValue == null) {
            return false;
        }
        return (statusValue instanceof TaskStatus ts && isPending(ts))
                || (statusValue instanceof ConfirmStatus cs && isPending(cs))
                || (statusValue instanceof SessionStatus ss && isPending(ss))
                || (statusValue instanceof ExecutionStatus es && isPending(es));
    }

    /**
     * 是否属于"系统后台等待"阶段的 PENDING。
     * - ReminderStatus.PENDING（已确认但还没到推送时间，SchedulerService 后台扫表阶段）
     */
    public static boolean isSystemWorkflowPending(Object statusValue) {
        return statusValue instanceof ReminderStatus rs && isPending(rs);
    }

    // ===== 中文语义标签（给日志、状态流、前端 debug 用） =====

    public static String getPendingLabel(Object statusValue) {
        if (!isPending(statusValue)) {
            return null;
        }
        if (statusValue instanceof TaskStatus) {
            return "多任务·子条目待用户决策";
        }
        if (statusValue instanceof SessionStatus) {
            return "会话级·整张卡片待处理";
        }
        if (statusValue instanceof ConfirmStatus) {
            return "确认阶段·信息待确认（推送前）";
        }
        if (statusValue instanceof ExecutionStatus) {
            return "执行阶段·已到点待用户执行确认";
        }
        if (statusValue instanceof ReminderStatus) {
            return "生命周期·待系统定时推送";
        }
        return "待处理";
    }
}
