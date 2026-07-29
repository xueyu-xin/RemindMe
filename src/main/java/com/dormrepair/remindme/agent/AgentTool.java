package com.dormrepair.remindme.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Agent 可调用工具声明注解。加在 Service 方法上即被 ToolRegistry 自动扫描注册，暴露给 LLM 进行 Function Calling
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    // 工具唯一名称，用于 Function Calling 的 name 字段
    String name();

    // 工具作用描述，传给 LLM 以判断何时调用
    String description();
}
