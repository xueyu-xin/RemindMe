package com.dormrepair.remindme.enums;

/**
 * 提醒状态
 * <p>
 * 对应数据库字段：reminders.status
 * 存储类型：VARCHAR(20)
 * 默认值：'PENDING'
 * </p>
 */
public enum ReminderStatus {

    PENDING("待推送"),
    SENT("已推送"),
    CANCELLED("已取消"),
    EXPIRED("已过期");

    private final String displayName;

    ReminderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ReminderStatus fromDisplayName(String displayName) {
        for (ReminderStatus value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
