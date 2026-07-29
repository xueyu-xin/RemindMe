package com.dormrepair.remindme.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

// 工具调用请求结构。封装工具名和参数，用于 LLM JSON 解析 → Agent 执行，以及会话历史落盘
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    // 被调用工具名
    private String toolName;

    // 参数键值表，key=参数名 value=参数值
    @Builder.Default
    private Map<String, Object> arguments = new HashMap<>();

    // 构造空参数调用
    public static ToolCall of(String toolName) {
        return ToolCall.builder()
                .toolName(toolName)
                .arguments(new HashMap<>())
                .build();
    }

    // 构造带参数调用，null 安全兜底为空 Map
    public static ToolCall of(String toolName, Map<String, Object> arguments) {
        return ToolCall.builder()
                .toolName(toolName)
                .arguments(arguments != null ? arguments : new HashMap<>())
                .build();
    }

    // 链式追加参数，返回自身便于流式构建
    public ToolCall addArgument(String key, Object value) {
        if (this.arguments == null) {
            this.arguments = new HashMap<>();
        }
        this.arguments.put(key, value);
        return this;
    }

    // 取原始参数值，无则返回 null
    public Object getArgument(String key) {
        return arguments != null ? arguments.get(key) : null;
    }

    // 转字符串；非字符串先 toString
    public String getStringArgument(String key) {
        Object value = getArgument(key);
        return value != null ? String.valueOf(value) : null;
    }

    // 转 int；支持 Number 和纯数字字符串，其它返回 null
    public Integer getIntArgument(String key) {
        Object value = getArgument(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return null;
    }

    // 转 long；支持 Number 和纯数字字符串
    public Long getLongArgument(String key) {
        Object value = getArgument(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        return null;
    }

    // 转 boolean；支持 Boolean 和 "true"/"1"/"yes" 等字符串
    public Boolean getBooleanArgument(String key) {
        Object value = getArgument(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }

    // 是否包含指定参数
    public boolean hasArgument(String key) {
        return arguments != null && arguments.containsKey(key);
    }

    // 参数数量；arguments 为 null 返回 0
    public int getArgumentCount() {
        return arguments != null ? arguments.size() : 0;
    }
}
