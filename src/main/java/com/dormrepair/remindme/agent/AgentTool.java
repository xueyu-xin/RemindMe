package com.dormrepair.remindme.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent工具注解
 * <p>
 * 用于标记Service方法为可被Agent调用的工具
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /**
     * 工具名称
     */
    String name();

    /**
     * 工具描述
     */
    String description();
}