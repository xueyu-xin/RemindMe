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
    // 用户取消选中（对应 ❌ 取消 → 非创建失败，仅表示用户选择不保存）
    CANCELLED("已取消", "❌"),
    // 用户选择跳过（对应 ⏭️）
    SKIPPED("已跳过", "⏭️"),
    // 系统创建失败（对应 ❌）
    FAILED("创建失败", "⚠️"),
    // 【P2-3 语义：多任务子任务级待确认】
    // 归属阶段：多任务卡片中的单个 item。用户尚未点击"确认/取消/跳过/修改"此条目，仍处于待用户决策或勾选状态。
    // 与 SessionStatus.PENDING（整会话）/ ConfirmStatus.PENDING（单提醒卡片）区分粒度：TaskStatus 是 items 维度。
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
