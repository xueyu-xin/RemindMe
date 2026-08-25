package com.dormrepair.remindme.enums;

import lombok.Getter;

// 多任务确认场景中每个子任务的状态枚举。
// 用于 ReplyTemplateService 生成结构化回复时标记每条任务的处理结果。
@Getter
public enum TaskStatus {

    // 已创建成功（对应 ✅）
    CONFIRMED("已创建", "✅"),
    // 已覆盖原有提醒（对应 📝）
    OVERWRITTEN("已覆盖", "📝"),
    // 用户选择跳过（对应 ⏭️）
    SKIPPED("已跳过", "⏭️"),
    // 系统创建失败（对应 ❌）
    FAILED("创建失败", "❌"),
    // 等待用户确认（对应 ⏳）
    PENDING("待确认", "⏳");

    // 中文展示名
    private final String displayName;
    // 对应的表情符号
    private final String emoji;

    TaskStatus(String displayName, String emoji) {
        this.displayName = displayName;
        this.emoji = emoji;
    }

    // 从字符串安全解析，不匹配时返回 PENDING
    public static TaskStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        try {
            return TaskStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
