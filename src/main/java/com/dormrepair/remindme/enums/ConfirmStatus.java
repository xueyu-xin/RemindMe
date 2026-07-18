package com.dormrepair.remindme.enums;

/**
 * 确认状态
 * <p>
 * 对应数据库字段：reminders.confirm_status / users.period_confirm_status
 * 存储类型：VARCHAR(20)
 * 默认值：'PENDING'
 * </p>
 */
public enum ConfirmStatus {

    PENDING("待确认"),
    CONFIRMED("已确认"),
    CANCELLED("已取消"),
    DEFAULTED("默认确认");

    private final String displayName;

    ConfirmStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ConfirmStatus fromDisplayName(String displayName) {
        for (ConfirmStatus value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
