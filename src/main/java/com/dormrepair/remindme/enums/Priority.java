package com.dormrepair.remindme.enums;

/**
 * 优先级
 * <p>
 * 对应数据库字段：reminders.priority
 * 存储类型：VARCHAR(20)
 * 默认值：'NORMAL'
 * </p>
 */
public enum Priority {

    LOW("低"),
    NORMAL("普通"),
    HIGH("高");

    private final String displayName;

    Priority(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Priority fromDisplayName(String displayName) {
        for (Priority value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
