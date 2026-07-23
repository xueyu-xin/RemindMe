package com.dormrepair.remindme.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用类
 * <p>
 * 封装工具调用信息，用于LLM工具调用和Agent执行
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具参数
     */
    @Builder.Default
    private Map<String, Object> arguments = new HashMap<>();

    /**
     * 创建工具调用
     */
    public static ToolCall of(String toolName) {
        return ToolCall.builder()
                .toolName(toolName)
                .arguments(new HashMap<>())
                .build();
    }

    /**
     * 创建工具调用（带参数）
     */
    public static ToolCall of(String toolName, Map<String, Object> arguments) {
        return ToolCall.builder()
                .toolName(toolName)
                .arguments(arguments != null ? arguments : new HashMap<>())
                .build();
    }

    /**
     * 添加参数
     */
    public ToolCall addArgument(String key, Object value) {
        if (this.arguments == null) {
            this.arguments = new HashMap<>();
        }
        this.arguments.put(key, value);
        return this;
    }

    /**
     * 获取参数值
     */
    public Object getArgument(String key) {
        return arguments != null ? arguments.get(key) : null;
    }

    /**
     * 获取字符串参数
     */
    public String getStringArgument(String key) {
        Object value = getArgument(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 获取整数参数
     */
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

    /**
     * 获取长整数参数
     */
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

    /**
     * 获取布尔参数
     */
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

    /**
     * 检查是否包含参数
     */
    public boolean hasArgument(String key) {
        return arguments != null && arguments.containsKey(key);
    }

    /**
     * 获取参数数量
     */
    public int getArgumentCount() {
        return arguments != null ? arguments.size() : 0;
    }
}