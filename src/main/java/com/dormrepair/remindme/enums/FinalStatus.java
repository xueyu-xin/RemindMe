package com.dormrepair.remindme.enums;

// 多任务汇总后的最终状态枚举。
// 由 TaskSummaryUtils.computeSummary 根据各子任务状态统计计算得出。
public enum FinalStatus {

    // ===== 原有枚举（保留以兼容旧统计维度：CONFIRMED/OVERWRITTEN 合并计算 + 跳过/失败优先） =====
    // 全部任务成功创建（含覆盖）
    ALL_SUCCESS("全部任务成功创建"),
    // 部分任务覆盖了原有提醒
    PARTIAL_OVERWRITE("部分任务覆盖了原有提醒"),
    // 全部任务都覆盖了原有提醒
    ALL_OVERWRITTEN("全部任务都覆盖了原有提醒"),
    // 部分任务被跳过（含确认+跳过混合场景）
    PARTIAL_SKIPPED("部分任务被跳过"),
    // 部分任务创建失败
    PARTIAL_FAILED("部分任务创建失败"),

    // ===== 新增枚举（按用户交互维度：确认 / 取消 / 跳过 的组合） =====
    // 全部任务均为 CONFIRMED / OVERWRITTEN（即"全部确认/覆盖创建成功"，ReplyTemplate 选择"已创建 N 项"模板）
    ALL_CONFIRMED("全部确认创建完成"),
    // 全部任务均为 CANCELLED（用户取消了所有 item，未保存任何内容）
    ALL_CANCELLED("全部取消，未保存任何内容"),
    // 全部任务均为 SKIPPED（用户跳过了所有 item，未创建任何内容）
    ALL_SKIPPED("全部跳过，未创建任何内容"),
    // 混合：部分 CONFIRMED/OVERWRITTEN + 部分 SKIPPED
    PARTIAL_CONFIRM_SKIP("部分确认创建，部分跳过"),
    // 混合：部分 CONFIRMED/OVERWRITTEN + 部分 CANCELLED
    PARTIAL_CONFIRM_CANCEL("部分确认创建，部分取消"),
    // 混合：存在 CANCELLED，同时有 SKIPPED（三态混合兜底，通常走 PARTIAL_FAILED 或新三态）
    PARTIAL_CANCEL_SKIP("部分取消，部分跳过");

    // 中文描述
    private final String description;

    FinalStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
