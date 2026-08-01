package com.dormrepair.remindme.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Agent 工具注册中心。容器启动后扫描所有 Service 上的 @AgentTool 方法注册，构建 Function Calling Schema，
// 并提供按名解析、参数类型转换、执行调用等能力
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    // 已注册工具：name → ToolEntry（定义 + bean + 方法）
    private final Map<String, ToolEntry> toolRegistry = new ConcurrentHashMap<>();

    // 注册表条目：包装一次工具执行所需的全部信息，运行时通过反射调用
    private static class ToolEntry {
        // Function Calling Schema 定义：包含工具名、描述、参数结构等
        ToolDefinition definition;
        // 方法所在的 Spring Bean 实例，反射调用时的目标对象
        Object bean;
        // 被 @AgentTool 标记的目标方法，反射调用时使用
        Method method;

        // 构造函数：传入工具定义、Bean 实例和方法引用，三者组合才能完成一次工具调用
        ToolEntry(ToolDefinition definition, Object bean, Method method) {
            this.definition = definition;
            this.bean = bean;
            this.method = method;
        }
    }

    // SmartInitializingSingleton 回调：在所有单例 Bean 均创建完成后再扫描，
    // 规避 @PostConstruct 过早触发 getBean(agentService) 形成的循环依赖，

    @Override
    public void afterSingletonsInstantiated() {
        init();
    }

    // 遍历 Spring 容器所有 bean，扫描其 public 方法上的 @AgentTool 并注册；
    // 使用 AopProxyUtils.ultimateTargetClass 穿透 CGLIB/JDK 代理获取原始类，避免漏扫描
    public void init() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> beanClass = AopProxyUtils.ultimateTargetClass(bean);

                Method[] methods = beanClass.getDeclaredMethods();
                for (Method method : methods) {
                    AgentTool annotation = method.getAnnotation(AgentTool.class);
                    if (annotation != null) {
                        registerTool(annotation, bean, method);
                    }
                }
            } catch (Exception e) {
                // 完整堆栈打出，方便排查"某个核心工具没注册"根因
                log.warn("扫描工具失败: beanName={}, error={}", beanName, e.getMessage(), e);
            }
        }
        log.info("工具注册完成，共注册 {} 个工具: {}", toolRegistry.size(), listToolNames());

        // 启动自检：核心提醒工具必须齐全，缺失时 ERROR 日志直接标明
        List<String> mustHave = List.of(
                "create_reminder",
                "create_recurring_reminder",
                "query_reminders",
                "update_reminder",
                "cancel_reminder",
                "cancel_reminders_by_date",
                "get_reminder_detail"
        );
        List<String> missing = new ArrayList<>();
        for (String n : mustHave) {
            if (resolveToolName(n) == null) missing.add(n);
        }
        if (!missing.isEmpty()) {
            log.error("❌ 核心提醒工具缺失: {}，请检查 ReminderService bean 扫描/代理/初始化顺序！", missing);
        } else {
            log.info("✅ 核心提醒工具全部注册成功: {}", mustHave);
        }
    }

    // 注册单个工具：构建参数 Schema，放入 toolRegistry
    public void registerTool(AgentTool annotation, Object bean, Method method) {
        String toolName = annotation.name();
        String description = annotation.description();

        ToolDefinition.Parameters parameters = buildParameters(method);

        ToolDefinition definition = ToolDefinition.builder()
                .name(toolName)
                .description(description)
                .parameters(parameters)
                .build();

        toolRegistry.put(toolName, new ToolEntry(definition, bean, method));
        log.debug("注册工具: {}", toolName);
    }

    // 根据方法签名构建参数 Schema：
    // 1. 跳过 userId（由 Agent 注入，不暴露给 LLM）
    // 2. 根据参数描述中含"（必填）"自动加入 required 列表
    private ToolDefinition.Parameters buildParameters(Method method) {
        Map<String, ToolDefinition.Property> properties = new ConcurrentHashMap<>();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter parameter : parameters) {
            String paramName = parameter.getName();

            if ("userId".equals(paramName)) {
                continue;
            }

            Class<?> paramType = parameter.getType();

            String type = convertJavaTypeToJsonType(paramType);

            String description = getParameterDescription(method.getName(), paramName);

            ToolDefinition.Property property = ToolDefinition.Property.builder()
                    .type(type)
                    .description(description)
                    .build();

            properties.put(paramName, property);

            if (description.contains("（必填）") || description.contains("(必填)")) {
                required.add(paramName);
            }
        }

        return ToolDefinition.Parameters.builder()
                .type("object")
                .properties(properties)
                .required(required)
                .build();
    }

    // 方法名 + 参数名 → 参数描述映射。
    // 新增 AgentTool 方法必须在此补齐 description，否则 LLM 看到的只能是"参数: xxx"，不知道传什么
    private String getParameterDescription(String methodName, String paramName) {
        Map<String, Map<String, String>> paramDescriptions = new HashMap<>();
        paramDescriptions.put("createReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "title", "提醒事项的标题（必填）",
                "remindTime", "提醒时间，格式为 yyyy-MM-dd HH:mm:ss（必填）",
                "endTime", "结束时间，格式为 yyyy-MM-dd HH:mm:ss（可选，用于时间段提醒，如'下午3点到5点开会'时传入结束时间）",
                "location", "提醒地点（可选）",
                "note", "备注（可选），用户对提醒的补充说明、注意事项或需要准备的物品，如'记得带借书证'、'带礼物'等",
                "priority", "优先级，可选值 LOW/NORMAL/HIGH（默认NORMAL）",
                "advanceMinutes", "提前提醒分钟数（可选）。当用户说'X分钟后'时传入X，说'等会/马上'时传入0，明确时间（如'明天下午3点'）时不传。"
        ));
        paramDescriptions.put("queryRemindersForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "date", "查询日期，格式为 yyyy-MM-dd（默认今天）"
        ));
        paramDescriptions.put("updateReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "reminderId", "提醒ID（必填），调用前必须先用 query_reminders 查到匹配的提醒，取其 reminderId 传进来，不能硬编码 ID=1、2、3",
                "title", "标题（可选）",
                "remindTime", "提醒时间，格式为 yyyy-MM-dd HH:mm:ss（可选）",
                "endTime", "结束时间，格式为 yyyy-MM-dd HH:mm:ss（可选，用于时间段提醒）",
                "location", "地点（可选）",
                "note", "备注（可选），用户对提醒的补充说明"
        ));
        paramDescriptions.put("cancelReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "reminderId", "提醒ID（必填），调用前必须先用 query_reminders 查到匹配的提醒，取其 reminderId 传进来，不能硬编码 ID=1、2、3"
        ));
        paramDescriptions.put("cancelRemindersByDate", Map.of(
                "userId", "用户ID（系统自动填充）",
                "date", "日期，格式 yyyy-MM-dd（必填）",
                "keyword", "关键词，用于按标题过滤要取消的提醒（可选）"
        ));
        paramDescriptions.put("queryWeatherNowForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "location", "具体城市名（可选，用户明确说了某个城市时传，例如 \"上海\"、\"北京\"）",
                "latitude", "纬度（必填），数字类型，例如 31.23。必须是通过 geocode_city 或 get_location 或 get_user_preferences→geocode_city 查询得到的真实坐标，禁止自己脑补。",
                "longitude", "经度（必填），数字类型，例如 121.47。禁止传 null。"
        ));
        paramDescriptions.put("queryWeatherForecastForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "location", "具体城市名（可选）",
                "days", "预报天数（3/7/10/15/30，默认7天）",
                "latitude", "纬度（必填），数字类型，必须真实查询得到，禁止脑补",
                "longitude", "经度（必填），数字类型，禁止脑补"
        ));
        paramDescriptions.put("queryAirQualityForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "location", "具体城市名（可选，用户明确说了某个城市时传，例如 \"上海\"、\"广州\"）。后端若未收到 lat/lon 会用此城市名自动转坐标，禁止空字符串。",
                "latitude", "纬度（强烈建议填，数字类型，真实查询得到。禁止传 null，禁止自己脑补。若不填则后端自动用 location 或默认城市兜底计算）",
                "longitude", "经度（强烈建议填，数字类型，真实查询得到。禁止传 null，禁止自己脑补。若不填则后端自动兜底计算）"
        ));
        paramDescriptions.put("queryWeatherAlertForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "location", "具体城市名（可选，用户明确说了某个城市时传）",
                "latitude", "纬度（强烈建议填，数字类型，真实查询得到。若不填后端自动用 location 或默认城市兜底）",
                "longitude", "经度（强烈建议填，数字类型，真实查询得到。若不填后端自动兜底）"
        ));
        paramDescriptions.put("calculateDate", Map.of(
                "expression", "日期表达式（必填），如：3天后、2天前、2周后、下周一、下周三"
        ));
        paramDescriptions.put("getUserPreferences", Map.of(
                "userId", "用户ID（系统自动填充）"
        ));
        paramDescriptions.put("setUserPreferences", Map.of(
                "userId", "用户ID（系统自动填充）",
                "preference", "偏好项名称（必填），可选值：city、defaultAdvanceMinutes、nickname、periodRemindTime",
                "value", "新值（必填），defaultAdvanceMinutes需为整数，periodRemindTime格式为HH:mm（如20:30）"
        ));
        paramDescriptions.put("getReminderDetail", Map.of(
                "userId", "用户ID（系统自动填充）",
                "reminderId", "提醒ID（必填）"
        ));
        paramDescriptions.put("getLocation", Map.of(
                "userId", "用户ID（系统自动填充）",
                "latitude", "纬度（可选，有GPS坐标时传入）",
                "longitude", "经度（可选，有GPS坐标时传入）"
        ));
        paramDescriptions.put("geocodeCity", Map.of(
                "city", "具体城市名（必填），用户明确提到了某一个城市名时传，例如 \"上海\"、\"广州\"、\"北京市\"、\"揭阳市\""
        ));
        paramDescriptions.put("createRecurringReminderForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "title", "提醒事项的标题（必填）",
                "remindTime", "每天提醒时间，格式为 HH:mm:ss（必填）",
                "startDate", "开始日期，格式为 yyyy-MM-dd（必填）",
                "endDate", "结束日期，格式为 yyyy-MM-dd（必填）",
                "repeatType", "周期类型，可选值 DAILY/WEEKLY/MONTHLY（默认DAILY）"
        ));
        // RAG 相关参数描述
        paramDescriptions.put("retrievePrivateKnowledgeForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "query", "查询关键词，如'我明天有什么课'、'我是什么专业'",
                "topK", "返回结果数量。传null时根据用户记忆总数动态计算（<20条→5，20-100条→10，>100条→15）；传具体值时使用该值"
        ));
        paramDescriptions.put("addPrivateMemoryForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "content", "要存储的记忆内容，如'用户是大三计算机专业学生'",
                "category", "记忆分类，可选值：PERSONAL_INFO / ACTIVITY / EVENT / PLAN / EXAM_SCHEDULE / DAILY_ROUTINE / HEALTH_CONDITION / DIET_PREFERENCE / HABIT / INTEREST / LANGUAGE_STYLE / OTHER。\n⚠️ category必须传字符串，比如 \"PERSONAL_INFO\"，禁止传 {PERSONAL_INFO:true、数组、对象等格式。\n✅ 正确示例：{\"category\":\"PERSONAL_INFO\"}\n❌ 错误示例：{\"category\":{\"PERSONAL_INFO\":true}}"
        ));
        paramDescriptions.put("updatePrivateMemoryForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "memoryId", "要更新的记忆ID",
                "newContent", "新的记忆内容"
        ));
        paramDescriptions.put("deletePrivateMemoryForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "memoryId", "要删除的记忆ID"
        ));
        paramDescriptions.put("getAllPrivateMemoriesForAgent", Map.of(
                "userId", "用户ID（系统自动填充）"
        ));
        paramDescriptions.put("retrievePublicKnowledgeForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "query", "查询关键词，用于检索系统使用手册，如'怎么创建提醒'、'能查天气吗'、'怎么记经期'",
                "topK", "返回结果数量，默认为5"
        ));
        // 经期相关：包含限制条件，供 LLM 在调用前自行判断合规性
        paramDescriptions.put("updatePeriodSettingsForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "cycleDays", "月经周期天数，必须为大于0的整数，且必须大于当前的经期持续天数（可选）",
                "periodDuration", "经期持续天数，必须为大于0的整数，且必须小于当前的月经周期（可选）"
        ));
        paramDescriptions.put("updatePeriodStartForAgent", Map.of(
                "userId", "用户ID（系统自动填充）",
                "startDate", "上次经期开始日期，格式 yyyy-MM-dd，不能晚于今天；不传则默认使用今天（可选）"
        ));

        Map<String, String> descriptions = paramDescriptions.get(methodName);
        if (descriptions != null && descriptions.containsKey(paramName)) {
            return descriptions.get(paramName);
        }

        return "参数: " + paramName;
    }

    // Java 基础类型 → JSON Schema 类型映射
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

    // 返回所有已注册工具的 Schema 列表
    public List<ToolDefinition> getToolDefinitions() {
        return toolRegistry.values().stream()
                .map(entry -> entry.definition)
                .toList();
    }

    // 返回 Function Calling 风格的 JSON Map（type/function/...），便于直接传给 LLM 构造请求
    public List<Map<String, Object>> getToolDefinitionsAsJson() {
        return toolRegistry.values().stream()
                .map(entry -> convertDefinitionToJson(entry.definition))
                .toList();
    }

    // ToolDefinition → JSON Map 转换
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

    // Parameters → JSON Map 转换，空参数兜底为 object/空属性
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

    // 按名执行工具：反射调用目标方法；参数自动按目标类型转换（Number/Enum/String/Jackson反序列化）
    public Object executeTool(String toolName, Map<String, Object> arguments) {
        ToolEntry entry = toolRegistry.get(toolName);
        if (entry == null) {
            throw new IllegalArgumentException("工具不存在: " + toolName);
        }

        try {
            Method method = entry.method;
            Object bean = entry.bean;

            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                String paramName = parameters[i].getName();
                Class<?> paramType = parameters[i].getType();

                Object value = arguments.get(paramName);
                args[i] = convertValue(value, paramType);
            }

            log.debug("执行工具: {}, 参数: {}", toolName, arguments);
            Object result = method.invoke(bean, args);
            log.debug("工具执行结果: {}", result);

            return result;

        } catch (Exception e) {
            log.error("执行工具失败: {}, error={}", toolName, e.getMessage(), e);
            throw new RuntimeException("执行工具失败: " + toolName, e);
        }
    }

    // 单个参数值按目标类型做转换：Number→基础数/包装类，Enum 用 valueOf，其它用 Jackson 兜底
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
            try {
                return objectMapper.convertValue(value, targetType);
            } catch (Exception e) {
                log.warn("参数转换失败: value={}, targetType={}, error={}", value, targetType, e.getMessage());
                return value;
            }
        }
    }

    // 判断工具是否存在（大小写不敏感）
    public boolean hasTool(String toolName) {
        return resolveToolName(toolName) != null;
    }

    // 大小写不敏感解析工具名：先精确匹配 → 全小写 → 遍历逐对 lower 比对
    public String resolveToolName(String toolName) {
        if (toolName == null) return null;
        if (toolRegistry.containsKey(toolName)) return toolName;
        String lower = toolName.trim().toLowerCase();
        if (toolRegistry.containsKey(lower)) return lower;
        for (String key : toolRegistry.keySet()) {
            if (key.toLowerCase().equals(lower)) return key;
        }
        return null;
    }

    // 列所有已注册工具的名称，用于启动日志输出和"工具不存在"错误时的提示列表
    public List<String> listToolNames() {
        return new ArrayList<>(toolRegistry.keySet());
    }

    // 返回当前已注册工具的总数量，用于健康检查和调试信息
    public int getToolCount() {
        return toolRegistry.size();
    }
}
