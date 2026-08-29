package com.dormrepair.remindme.agent.event;

// Agent 执行状态枚举
// 用于描述 Agent 在 ReAct 循环中的每个阶段状态，供前端状态流展示
public enum AgentStatus {

    // ========== 生命周期阶段 ==========
    START_THINKING("开始处理", "🧠", false),
    THINKING("正在理解意图", "💭", false),
    GENERATING_REPLY("正在生成回复", "✍️", false),
    DONE("处理完成", "✨", true),

    // ========== 工具执行阶段 ==========
    CALLING_WEATHER("查询天气", "🌤️", false),
    CALLING_REMINDER_CREATE("创建提醒", "📝", false),
    CALLING_REMINDER_QUERY("查询提醒", "📋", false),
    CALLING_REMINDER_CANCEL("取消提醒", "🗑️", false),
    CALLING_REMINDER_UPDATE("修改提醒", "✏️", false),
    CALLING_RECURRING_CREATE("创建周期提醒", "📅", false),
    CALLING_MEMORY_RETRIEVE("检索记忆", "🔍", false),
    CALLING_MEMORY_ADD("添加记忆", "🧺", false),
    CALLING_MEMORY_UPDATE("更新记忆", "🔧", false),
    CALLING_MEMORY_DELETE("删除记忆", "🧹", false),
    CALLING_PREFERENCES_GET("查看偏好设置", "⚙️", false),
    CALLING_PREFERENCES_SET("保存偏好设置", "💾", false),
    CALLING_LOCATION("解析位置", "📍", false),
    CALLING_TIME("获取时间", "🕒", false),
    CALLING_DATE_CALC("日期计算", "📆", false),
    CALLING_SCHEDULE_IMPORT("导入课表", "📚", false),

    // ========== 经期管理 ==========
    CALLING_PERIOD_STATUS("查询经期状态", "🌸", false),
    CALLING_PERIOD_NEXT("预测下次经期", "🌷", false),
    CALLING_PERIOD_UPDATE_START("记录经期开始", "🌹", false),
    CALLING_PERIOD_UPDATE_SETTINGS("更新经期设置", "🌺", false),

    // ========== 通用工具 ==========
    CALLING_TOOL("执行工具", "🔧", false),
    TOOL_RESULT("工具执行完成", "✅", true),

    // ========== 异常/降级 ==========
    ERROR("遇到问题", "⚠️", true);

    // 前端显示的中文描述
    private final String displayName;

    // 配套的 emoji 图标
    private final String icon;

    // 是否为终态（true 表示该步骤已结束，前端显示打勾效果）
    private final boolean terminal;

    AgentStatus(String displayName, String icon, boolean terminal) {
        this.displayName = displayName;
        this.icon = icon;
        this.terminal = terminal;
    }

    public String getDisplayName() { return displayName; }
    public String getIcon() { return icon; }
    public boolean isTerminal() { return terminal; }
}
