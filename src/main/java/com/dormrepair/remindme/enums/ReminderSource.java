package com.dormrepair.remindme.enums;

/**
 * 提醒来源
 * <p>
 * 对应数据库字段：reminders.source
 * 存储类型：VARCHAR(20)
 * 默认值：'USER_INPUT'
 * </p>
 */
public enum ReminderSource {

    USER_INPUT("用户手动输入"),
    WEATHER_LINK("天气联动"),
    EXCEL_IMPORT("Excel导入"),
    PERIOD_REMINDER("月经周期");

    private final String displayName;

    ReminderSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ReminderSource fromDisplayName(String displayName) {
        for (ReminderSource value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
