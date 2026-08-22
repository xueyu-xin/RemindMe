package com.dormrepair.remindme.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

// 工具名 → AgentStatus 映射工具类
// 将 Agent 内部工具名映射为中等粒度的 AgentStatus（含中文显示名和图标），
// 避免前端暴露原始工具名，保持状态流的可读性和友好性。
public class AgentStatusMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 工具名→ 对应 AgentStatus 的映射表
    private static final Map<String, AgentStatus> TOOL_STATUS_MAP = new HashMap<>();

    // 工具名 → 中文显示名映射（用于 CALLING_TOOL/TOOL_RESULT 事件中显示工具中文名）
    private static final Map<String, String> TOOL_CN_NAME_MAP = new HashMap<>();

    static {
        // ===== 天气工具 =====
        TOOL_STATUS_MAP.put("query_weather_now",       AgentStatus.CALLING_WEATHER);
        TOOL_STATUS_MAP.put("query_weather_forecast",  AgentStatus.CALLING_WEATHER);
        TOOL_STATUS_MAP.put("geocode_city",            AgentStatus.CALLING_LOCATION);
        TOOL_CN_NAME_MAP.put("query_weather_now",       "实时天气查询");
        TOOL_CN_NAME_MAP.put("query_weather_forecast",  "天气预报查询");
        TOOL_CN_NAME_MAP.put("geocode_city",            "城市坐标解析");

        // ===== 提醒工具 =====
        TOOL_STATUS_MAP.put("create_reminder",          AgentStatus.CALLING_REMINDER_CREATE);
        TOOL_STATUS_MAP.put("create_recurring_reminder", AgentStatus.CALLING_RECURRING_CREATE);
        TOOL_STATUS_MAP.put("update_reminder",          AgentStatus.CALLING_REMINDER_UPDATE);
        TOOL_STATUS_MAP.put("cancel_reminder",          AgentStatus.CALLING_REMINDER_CANCEL);
        TOOL_STATUS_MAP.put("cancel_reminders_by_date", AgentStatus.CALLING_REMINDER_CANCEL);
        TOOL_STATUS_MAP.put("get_reminder_detail",      AgentStatus.CALLING_REMINDER_QUERY);
        TOOL_STATUS_MAP.put("list_reminders",           AgentStatus.CALLING_REMINDER_QUERY);
        TOOL_CN_NAME_MAP.put("create_reminder",          "创建提醒");
        TOOL_CN_NAME_MAP.put("create_recurring_reminder", "创建周期提醒");
        TOOL_CN_NAME_MAP.put("update_reminder",          "修改提醒");
        TOOL_CN_NAME_MAP.put("cancel_reminder",          "取消提醒");
        TOOL_CN_NAME_MAP.put("cancel_reminders_by_date", "批量取消提醒");
        TOOL_CN_NAME_MAP.put("get_reminder_detail",      "提醒详情查询");
        TOOL_CN_NAME_MAP.put("list_reminders",           "提醒列表查询");

        // ===== 记忆工具 =====
        TOOL_STATUS_MAP.put("retrieve_private_knowledge", AgentStatus.CALLING_MEMORY_RETRIEVE);
        TOOL_STATUS_MAP.put("add_private_memory",         AgentStatus.CALLING_MEMORY_ADD);
        TOOL_STATUS_MAP.put("update_private_memory",      AgentStatus.CALLING_MEMORY_UPDATE);
        TOOL_STATUS_MAP.put("delete_private_memory",      AgentStatus.CALLING_MEMORY_DELETE);
        TOOL_STATUS_MAP.put("get_all_private_memories",   AgentStatus.CALLING_MEMORY_RETRIEVE);
        TOOL_STATUS_MAP.put("retrieve_public_knowledge",  AgentStatus.CALLING_MEMORY_RETRIEVE);
        TOOL_CN_NAME_MAP.put("retrieve_private_knowledge", "私人记忆检索");
        TOOL_CN_NAME_MAP.put("add_private_memory",         "添加私人记忆");
        TOOL_CN_NAME_MAP.put("update_private_memory",      "更新私人记忆");
        TOOL_CN_NAME_MAP.put("delete_private_memory",      "删除私人记忆");
        TOOL_CN_NAME_MAP.put("get_all_private_memories",   "获取全部记忆");
        TOOL_CN_NAME_MAP.put("retrieve_public_knowledge",  "公共知识库检索");

        // ===== 用户设置 =====
        TOOL_STATUS_MAP.put("get_user_preferences",     AgentStatus.CALLING_PREFERENCES_GET);
        TOOL_STATUS_MAP.put("set_user_preferences",     AgentStatus.CALLING_PREFERENCES_SET);
        TOOL_CN_NAME_MAP.put("get_user_preferences",     "读取用户偏好");
        TOOL_CN_NAME_MAP.put("set_user_preferences",     "保存用户偏好");

        // ===== 时间/位置工具 =====
        TOOL_STATUS_MAP.put("get_current_time",         AgentStatus.CALLING_TIME);
        TOOL_STATUS_MAP.put("calculate_date",           AgentStatus.CALLING_DATE_CALC);
        TOOL_STATUS_MAP.put("get_location",             AgentStatus.CALLING_LOCATION);
        TOOL_STATUS_MAP.put("set_location",             AgentStatus.CALLING_LOCATION);
        TOOL_CN_NAME_MAP.put("get_current_time",         "获取当前时间");
        TOOL_CN_NAME_MAP.put("calculate_date",           "日期差值计算");
        TOOL_CN_NAME_MAP.put("get_location",             "GPS坐标解析");
        TOOL_CN_NAME_MAP.put("set_location",             "设置位置信息");

        // ===== 课表导入 =====
        TOOL_STATUS_MAP.put("parse_schedule_excel",     AgentStatus.CALLING_SCHEDULE_IMPORT);
        TOOL_STATUS_MAP.put("import_schedule_items",    AgentStatus.CALLING_SCHEDULE_IMPORT);
        TOOL_CN_NAME_MAP.put("parse_schedule_excel",     "解析课表Excel");
        TOOL_CN_NAME_MAP.put("import_schedule_items",    "批量导入课表");

        // ===== 经期管理 =====
        TOOL_STATUS_MAP.put("get_period_status",          AgentStatus.CALLING_PERIOD_STATUS);
        TOOL_STATUS_MAP.put("get_next_period",            AgentStatus.CALLING_PERIOD_NEXT);
        TOOL_STATUS_MAP.put("update_period_start",        AgentStatus.CALLING_PERIOD_UPDATE_START);
        TOOL_STATUS_MAP.put("update_period_settings",    AgentStatus.CALLING_PERIOD_UPDATE_SETTINGS);
        TOOL_CN_NAME_MAP.put("get_period_status",          "查询经期状态");
        TOOL_CN_NAME_MAP.put("get_next_period",            "预测下次经期");
        TOOL_CN_NAME_MAP.put("update_period_start",        "记录经期开始");
        TOOL_CN_NAME_MAP.put("update_period_settings",    "更新经期设置");
    }

    // 根据工具名映射 AgentStatus；未匹配时返回通用 CALLING_TOOL
    // @param toolName 原始工具名（大小写不敏感，会自动 trim+小写）
    public static AgentStatus fromToolName(String toolName) {
        if (toolName == null) return AgentStatus.CALLING_TOOL;
        String normalized = toolName.trim().toLowerCase();
        return TOOL_STATUS_MAP.getOrDefault(normalized, AgentStatus.CALLING_TOOL);
    }

    // 工具名 → 中文显示名；未匹配时返回原始工具名
    public static String toChinese(String toolName) {
        if (toolName == null) return "未知工具";
        String normalized = toolName.trim().toLowerCase();
        return TOOL_CN_NAME_MAP.getOrDefault(normalized, toolName);
    }

    // 从工具参数中提取关键信息，生成前端可读的详情摘要（如城市名、提醒标题等）
    public static String extractToolDetail(String toolName, Map<String, Object> args) {
        if (toolName == null || args == null || args.isEmpty()) return "";
        try {
            String normalized = toolName.trim().toLowerCase();
            switch (normalized) {
                case "query_weather_now":
                case "query_weather_forecast":
                case "geocode_city":
                    return args.containsKey("location") ? "城市：" + args.get("location") : "";
                case "create_reminder":
                case "create_recurring_reminder":
                case "update_reminder": {
                    StringBuilder sb = new StringBuilder();
                    if (args.containsKey("title")) sb.append("标题：").append(args.get("title"));
                    if (args.containsKey("remindTime")) {
                        if (sb.length() > 0) sb.append("，");
                        sb.append("时间：").append(args.get("remindTime"));
                    }
                    return sb.toString();
                }
                case "cancel_reminder":
                case "get_reminder_detail":
                    return args.containsKey("reminderId") ? "ID：" + args.get("reminderId") : "";
                case "cancel_reminders_by_date": {
                    StringBuilder sb = new StringBuilder();
                    if (args.containsKey("date")) sb.append("日期：").append(args.get("date"));
                    if (args.containsKey("keyword")) {
                        if (sb.length() > 0) sb.append("，关键词：");
                        else sb.append("关键词：");
                        sb.append(args.get("keyword"));
                    }
                    return sb.toString();
                }
                case "list_reminders": {
                    StringBuilder sb = new StringBuilder();
                    if (args.containsKey("startDate")) sb.append("起：").append(args.get("startDate"));
                    if (args.containsKey("endDate")) {
                        if (sb.length() > 0) sb.append("，止：");
                        else sb.append("止：");
                        sb.append(args.get("endDate"));
                    }
                    return sb.toString();
                }
                case "calculate_date": {
                    StringBuilder sb = new StringBuilder();
                    if (args.containsKey("startDate")) sb.append("起：").append(args.get("startDate"));
                    if (args.containsKey("endDate")) {
                        if (sb.length() > 0) sb.append(" → ");
                        else sb.append("止：");
                        sb.append(args.get("endDate"));
                    }
                    return sb.toString();
                }
                case "update_period_start":
                    return args.containsKey("startDate") ? "经期开始日期：" + args.get("startDate") : "";
                case "update_period_settings": {
                    StringBuilder sb = new StringBuilder();
                    if (args.containsKey("cycleLength")) sb.append("周期：").append(args.get("cycleLength")).append("天");
                    if (args.containsKey("periodDuration")) {
                        if (sb.length() > 0) sb.append("，");
                        sb.append("持续：").append(args.get("periodDuration")).append("天");
                    }
                    return sb.toString();
                }
                case "get_period_status":
                case "get_next_period":
                    return "";
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    // 格式化工具结果 JSON，返回前端可读的简要结果（如天气温度、提醒创建成功等）
    @SuppressWarnings("unchecked")
    public static String formatToolResultBrief(String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) return "";
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(toolResultJson, Map.class);
            Boolean success = (Boolean) map.get("success");
            if (Boolean.FALSE.equals(success)) {
                if (map.containsKey("message")) return "失败：" + map.get("message");
                if (map.containsKey("error")) return "失败：" + map.get("error");
                return "执行失败";
            }
            // weather 场景
            if (map.containsKey("temperature") && map.containsKey("weather")) {
                return map.get("temperature") + "，" + map.get("weather");
            }
            // create_reminder 场景
            if (map.containsKey("sessionId")) {
                return "已生成确认卡片";
            }
            // message / data 通用场景
            if (map.containsKey("message")) {
                return String.valueOf(map.get("message"));
            }
            if (map.containsKey("data")) {
                return "返回数据";
            }
            return "执行成功";
        } catch (Exception e) {
            // 非 JSON 直接完整返回
            return toolResultJson;
        }
    }
}
