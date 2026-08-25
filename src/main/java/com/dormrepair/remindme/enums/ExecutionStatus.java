package com.dormrepair.remindme.enums;

// 执行确认状态枚举。对应 reminders.execution_status 字段，存储为 VARCHAR(20)，默认值 PENDING；表示到点推送后用户是否确认「已执行」
public enum ExecutionStatus {

    // 【P2-3 语义：到点执行确认阶段·待用户点"已执行"】
    // 归属阶段：两阶段确认的"执行阶段"，即 reminders.execution_status 字段。
    // 前置条件：ReminderStatus 已从 PENDING → SENT（提醒已推送到用户端）。
    // 用户看到推送内容后需要明确点击"已执行/稍后/取消"；与 ConfirmStatus.PENDING（信息确认、推送前）区分前后阶段。
    PENDING("待执行确认"),
    // 已确认执行：用户点击「已执行」，提醒生命周期结束
    CONFIRMED("已确认执行"),
    // 已取消执行：用户在推送后选择「取消」，该次执行被标记为放弃
    CANCELLED("已取消执行"),
    // 默认确认执行：超时未收到用户操作，系统自动按「已执行」兜底
    DEFAULTED("默认确认执行");

    // 中文展示名，用于日志输出、响应体中返回给调用方的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    ExecutionStatus(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }
}
