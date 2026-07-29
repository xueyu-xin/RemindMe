package com.dormrepair.remindme.enums;

// 提醒来源枚举。对应 reminders.source 字段，存储为 VARCHAR(20)，默认值 USER_INPUT；用于统计各来源创建的提醒占比
public enum ReminderSource {

    // 用户手动输入：自然语言解析、手动新建卡片等用户主动行为创建
    USER_INPUT("用户手动输入"),
    // 天气联动：检测到雨雪/高温等恶劣天气时自动创建的出行建议提醒
    WEATHER_LINK("天气联动"),
    // Excel 导入：通过课程表 Excel 解析批量创建的上课提醒
    EXCEL_IMPORT("Excel导入"),
    // 月经周期：按女性周期设置自动生成的经期、安全期、排卵日提醒
    PERIOD_REMINDER("月经周期");

    // 中文展示名，用于日志输出、统计报表的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    ReminderSource(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static ReminderSource fromDisplayName(String displayName) {
        for (ReminderSource value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
