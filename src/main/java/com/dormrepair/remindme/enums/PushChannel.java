package com.dormrepair.remindme.enums;

/**
 * 推送渠道
 * <p>
 * 对应数据库字段：push_logs.push_channel
 * 存储类型：VARCHAR(20)
 * 默认值：'WEBSOCKET'
 * </p>
 */
public enum PushChannel {

    WEBSOCKET("WebSocket推送"),
    EMAIL("邮件推送"),
    SMS("短信推送");

    private final String displayName;

    PushChannel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PushChannel fromDisplayName(String displayName) {
        for (PushChannel value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
