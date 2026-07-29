package com.dormrepair.remindme.enums;

// 推送渠道枚举。对应 push_logs.push_channel 字段，存储为 VARCHAR(20)，默认值 WEBSOCKET；用于记录每条推送实际使用的通道
public enum PushChannel {

    // WebSocket 推送：浏览器在线时首选通道，实时性最高、零延迟
    WEBSOCKET("WebSocket推送"),
    // 邮件推送：兜底通道，WebSocket 未连上且配置了邮箱时使用
    EMAIL("邮件推送"),
    // 短信推送：高优先级、兜底通道，需要对接短信网关后启用
    SMS("短信推送");

    // 中文展示名，用于日志输出、推送记录查询的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    PushChannel(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static PushChannel fromDisplayName(String displayName) {
        for (PushChannel value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
