package com.dormrepair.remindme.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表
 * <p>
 * 管理所有可被Agent调用的工具，支持注册、查询和执行
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    /**
     * 工具注册表：工具名称 -> 工具条目
     */
    private final Map<String, ToolEntry> toolRegistry = new ConcurrentHashMap<>();

    /**
     * 内部类：工具条目，包含完整的工具信息
     */
    private static class ToolEntry {
        ToolDefinition definition;
        Object bean;
        Method method;

        ToolEntry(ToolDefinition definition, Object bean, Method method) {
            this.definition = definition;
            this.bean = bean;
            this.method = method;
        }
    }

    /**
     * 初始化时扫描并注册所有带有 @AgentTool 注解的方法
     */
    @PostConstruct
    public void init() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                // 使用 AopUtils 获取真实目标类，处理 CGLIB 和 JDK 动态代理
                Class<?> beanClass = AopProxyUtils.ultimateTargetClass(bean);

                Method[] methods = beanClass.getDeclaredMethods();
                for (Method method : methods) {
                    AgentTool annotation = method.getAnnotation(AgentTool.class);
                    if (annotation != null) {
                        registerTool(annotation, bean, method);
                    }
                }
            } catch (Exception e) {
                log.warn("扫描工具失败: beanName={}, error={}", beanName, e.getMessage());
            }
        }
        log.info("工具注册完成，共注册 {} 个工具", toolRegistry.size());
    }

    /**
     * 注册工具
     */
    public void registerTool(AgentTool annotation, Object bean, Method method) {
        String toolName = annotation.name();
        String description = annotation.description();

        // 构建参数定义
        ToolDefinition.Parameters parameters = buildParameters(method);

        // 创建工具定义
        ToolDefinition definition = ToolDefinition.builder()
                .name(toolName)
                .description(description)
                .parameters(parameters)
                .build();

        // 注册到注册表
        toolRegistry.put(toolName, new ToolEntry(definition, bean, method));
        log.debug("注册工具: {}", toolName);
    }

    /**
     * 根据方法签名构建参数定义
     */
    private ToolDefinition.Parameters buildParameters(Method method) {
        Map<String, ToolDefinition.Property> properties = new ConcurrentHashMap<>();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter parameter : parameters) {
            String paramName = parameter.getName();
            
            // 跳过 userId 参数（由Agent自动填充，不暴露给LLM）
            if ("userId".equals(paramName)) {
                continue;
            }
            
            Class<?> paramType = parameter.getType();
            
            String type = convertJavaTypeToJsonType(paramType);
            
            // 获取参数描述
            String description = getParameterDescription(method.getName(), paramName);
            
            ToolDefinition.Property property = ToolDefinition.Property.builder()
                    .type(type)
                    .description(description)
                    .build();

            properties.put(paramName, property);
            
            // 所有非userId参数都设为必填（由工具定义决定是否必填）
            required.add(paramName);
        }

        return ToolDefinition.Parameters.builder()
                .type("object")
                .properties(properties)
                .required(required)
                .build();
    }

    /**
     * 获取参数的详细描述
     */
    private String getParameterDescription(String methodName, String paramName) {
        // 工具参数描述映射
        Map<String, Map<String, String>> paramDescriptions = Map.of(
            "createReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "title", "提醒事项的标题（必填）",
                "remindTime", "提醒时间，格式为 yyyy-MM-dd HH:mm:ss（必填）",
                "location", "提醒地点（可选）",
                "priority", "优先级，可选值 LOW/NORMAL/HIGH（默认NORMAL）"
            ),
            "queryRemindersForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "date", "查询日期，格式为 yyyy-MM-dd（默认今天）"
            ),
            "updateReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "reminderId", "提醒ID（必填）",
                "title", "标题（可选）",
                "remindTime", "提醒时间，格式为 yyyy-MM-dd HH:mm:ss（可选）",
                "location", "地点（可选）"
            ),
            "cancelReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "reminderId", "提醒ID（必填）"
            ),
            "queryWeatherNowForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "location", "LocationID（如101010100）或经纬度（如116.41,39.92）"
            ),
            "queryWeatherForecastForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "location", "LocationID或经纬度",
                "days", "预报天数（3/7/10/15/30，默认7天）"
            ),
            "queryAirQualityForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "latitude", "纬度",
                "longitude", "经度"
            ),
            "queryWeatherAlertForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "latitude", "纬度",
                "longitude", "经度"
            )
        );

        Map<String, String> descriptions = paramDescriptions.get(methodName);
        if (descriptions != null && descriptions.containsKey(paramName)) {
            return descriptions.get(paramName);
        }
        
        return "参数: " + paramName;
    }

    /**
     * 将Java类型转换为JSON Schema类型
     */
    private String convertJavaTypeToJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        } else if (type == Integer.class || type == int.class || 
                   type == Long.class || type == long.class) {
            return "integer";
        } else if (type == Double.class || type == double.class ||
                   type == Float.class || type == float.class) {
            return "number";
        } else if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        } else if (type.isArray() || List.class.isAssignableFrom(type)) {
            return "array";
        } else {
            return "object";
        }
    }

    /**
     * 获取所有工具定义（用于LLM Function Calling）
     */
    public List<ToolDefinition> getToolDefinitions() {
        return toolRegistry.values().stream()
                .map(entry -> entry.definition)
                .toList();
    }

    /**
     * 获取所有工具定义的JSON格式（直接传递给LLM）
     */
    public List<Map<String, Object>> getToolDefinitionsAsJson() {
        return toolRegistry.values().stream()
                .map(entry -> convertDefinitionToJson(entry.definition))
                .toList();
    }

    /**
     * 将工具定义转换为Map格式（JSON）
     */
    private Map<String, Object> convertDefinitionToJson(ToolDefinition definition) {
        Map<String, Object> toolMap = new ConcurrentHashMap<>();
        toolMap.put("type", "function");
        toolMap.put("function", Map.of(
                "name", definition.getName(),
                "description", definition.getDescription(),
                "parameters", convertParametersToJson(definition.getParameters())
        ));
        return toolMap;
    }

    /**
     * 将参数定义转换为Map格式
     */
    private Map<String, Object> convertParametersToJson(ToolDefinition.Parameters parameters) {
        if (parameters == null) {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
            );
        }

        Map<String, Object> propertiesMap = new ConcurrentHashMap<>();
        if (parameters.getProperties() != null) {
            parameters.getProperties().forEach((name, property) -> {
                Map<String, Object> propMap = new ConcurrentHashMap<>();
                propMap.put("type", property.getType());
                propMap.put("description", property.getDescription());
                if (property.getItems() != null) {
                    propMap.put("items", Map.of("type", property.getItems()));
                }
                propertiesMap.put(name, propMap);
            });
        }

        return Map.of(
                "type", parameters.getType(),
                "properties", propertiesMap,
                "required", parameters.getRequired() != null ? parameters.getRequired() : List.of()
        );
    }

    /**
     * 根据工具名称执行对应工具
     *
     * @param toolName 工具名称
     * @param arguments 参数（Map格式）
     * @return 执行结果
     */
    public Object executeTool(String toolName, Map<String, Object> arguments) {
        ToolEntry entry = toolRegistry.get(toolName);
        if (entry == null) {
            throw new IllegalArgumentException("工具不存在: " + toolName);
        }

        try {
            Method method = entry.method;
            Object bean = entry.bean;

            // 解析参数
            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                String paramName = parameters[i].getName();
                Class<?> paramType = parameters[i].getType();
                
                Object value = arguments.get(paramName);
                args[i] = convertValue(value, paramType);
            }

            // 执行方法
            log.debug("执行工具: {}, 参数: {}", toolName, arguments);
            Object result = method.invoke(bean, args);
            log.debug("工具执行结果: {}", result);
            
            return result;

        } catch (Exception e) {
            log.error("执行工具失败: {}, error={}", toolName, e.getMessage(), e);
            throw new RuntimeException("执行工具失败: " + toolName, e);
        }
    }

    /**
     * 将参数值转换为目标类型
     */
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType == String.class) {
            return String.valueOf(value);
        } else if (targetType == Integer.class || targetType == int.class) {
            return ((Number) value).intValue();
        } else if (targetType == Long.class || targetType == long.class) {
            return ((Number) value).longValue();
        } else if (targetType == Double.class || targetType == double.class) {
            return ((Number) value).doubleValue();
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(String.valueOf(value));
        } else if (targetType.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) targetType, String.valueOf(value));
        } else {
            // 尝试JSON反序列化
            try {
                return objectMapper.convertValue(value, targetType);
            } catch (Exception e) {
                log.warn("参数转换失败: value={}, targetType={}, error={}", value, targetType, e.getMessage());
                return value;
            }
        }
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String toolName) {
        return toolRegistry.containsKey(toolName);
    }

    /**
     * 获取工具数量
     */
    public int getToolCount() {
        return toolRegistry.size();
    }
}