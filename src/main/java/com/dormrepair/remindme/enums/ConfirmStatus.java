package com.dormrepair.remindme.enums;

// 确认状态枚举。对应 reminders.confirm_status / users.period_confirm_status 字段，存储为 VARCHAR(20)，默认值 PENDING
public enum ConfirmStatus {

    // 待确认：提醒/行程卡片已下发，等待用户点击「确认」或「取消」
    PENDING("待确认"),
    // 已确认：用户主动点击确认，提醒后续进入待推送/待执行链路
    CONFIRMED("已确认"),
    // 已取消：用户主动取消该条待确认项，后续不再处理
    CANCELLED("已取消"),
    // 默认确认：超过倒计时超时后仍未收到用户操作，系统自动按确认处理
    DEFAULTED("默认确认");

    // 中文展示名，用于日志输出、响应体中返回给调用方的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    ConfirmStatus(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，用于把用户输入的「待确认」等文字转成内部枚举，找不到返回 null
    public static ConfirmStatus fromDisplayName(String displayName) {
        for (ConfirmStatus value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
