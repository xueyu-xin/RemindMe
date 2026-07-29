package com.dormrepair.remindme.enums;

// 提醒优先级枚举。对应 reminders.priority 字段，存储为 VARCHAR(20)，默认值 NORMAL
public enum Priority {

    // 低优先级：普通备忘、一般事项，推送排序靠后
    LOW("低"),
    // 普通优先级：默认级别，占绝大多数日常提醒
    NORMAL("普通"),
    // 高优先级：重要会议、考试、截止日期等，推送排序靠前、失败重试更积极
    HIGH("高");

    // 中文展示名，用于日志输出、响应体中返回给调用方的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    Priority(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static Priority fromDisplayName(String displayName) {
        for (Priority value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
