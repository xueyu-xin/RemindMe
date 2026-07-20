package com.dormrepair.remindme.enums;

/**
 * 意图类型
 * <p>
 * 对应数据库字段：user_dialog_stats.intent_type
 * 存储类型：VARCHAR(20)
 * 默认值：NULL
 * </p>
 */
public enum IntentType {

    REMINDER("设置提醒"),
    QUERY("查询日程"),
    WEATHER("查询天气"),
    DELETE("删除提醒"),
    UPDATE("修改提醒"),
    CONFIRM("确认操作"),
    CANCEL("取消操作"),
    UNKNOWN("无法识别");

    private final String displayName;

    IntentType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static IntentType fromDisplayName(String displayName) {
        for (IntentType value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }

    public static IntentType fromString(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
