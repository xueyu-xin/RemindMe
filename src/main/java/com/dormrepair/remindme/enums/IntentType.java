package com.dormrepair.remindme.enums;

// 对话意图类型枚举。对应 user_dialog_stats.intent_type 字段，存储为 VARCHAR(20)，默认 NULL；由 LLM 解析后写入，用于统计用户意图分布
public enum IntentType {

    // 设置提醒：用户输入了「明天8点开会」类需要创建新提醒的指令
    REMINDER("设置提醒"),
    // 查询日程：用户询问「今天有什么安排」「下周行程」等检索类指令
    QUERY("查询日程"),
    // 查询天气：用户问天气、温度、预警等
    WEATHER("查询天气"),
    // 删除提醒：用户明确要求删除某条已有提醒
    DELETE("删除提醒"),
    // 修改提醒：用户要求改时间、改标题、改地点等编辑类指令
    UPDATE("修改提醒"),
    // 确认操作：用户回复「确认」「好的」这类确认语义
    CONFIRM("确认操作"),
    // 取消操作：用户回复「取消」「算了」这类取消语义
    CANCEL("取消操作"),
    // 无法识别：LLM 无法归类到以上任何意图，走兜底闲聊或提示
    UNKNOWN("无法识别");

    // 中文展示名，用于日志输出、统计报表、响应体中的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    IntentType(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static IntentType fromDisplayName(String displayName) {
        for (IntentType value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }

    // 按枚举名（常量名，如 REMINDER）解析，非法值统一降级为 UNKNOWN，避免抛异常中断主流程
    public static IntentType fromString(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
