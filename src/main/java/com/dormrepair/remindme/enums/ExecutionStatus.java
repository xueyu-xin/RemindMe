package com.dormrepair.remindme.enums;

// 执行确认状态枚举。对应 reminders.execution_status 字段，存储为 VARCHAR(20)，默认值 PENDING；表示到点推送后用户是否确认「已执行」
public enum ExecutionStatus {

    // 待执行确认：提醒已到点推送，等待用户点击「已执行」或「稍后」或「取消」
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
