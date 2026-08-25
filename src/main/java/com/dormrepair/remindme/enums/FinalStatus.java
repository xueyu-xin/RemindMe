package com.dormrepair.remindme.enums;

// 多任务汇总后的最终状态枚举。
// 由 TaskSummaryUtils.computeSummary 根据各子任务状态统计计算得出。
public enum FinalStatus {

    // 全部任务成功创建
    ALL_SUCCESS("全部任务成功创建"),
    // 部分任务覆盖了原有提醒
    PARTIAL_OVERWRITE("部分任务覆盖了原有提醒"),
    // 全部任务都覆盖了原有提醒
    ALL_OVERWRITTEN("全部任务都覆盖了原有提醒"),
    // 部分任务被跳过
    PARTIAL_SKIPPED("部分任务被跳过"),
    // 部分任务创建失败
    PARTIAL_FAILED("部分任务创建失败");

    // 中文描述
    private final String description;

    FinalStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
