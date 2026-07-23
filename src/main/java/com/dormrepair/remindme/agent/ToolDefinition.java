package com.dormrepair.remindme.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工具定义类
 * <p>
 * 用于定义工具的JSON Schema格式，支持LLM Function Calling
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 参数定义
     */
    private Parameters parameters;

    /**
     * 参数定义类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Parameters {

        /**
         * 参数类型（固定为 object）
         */
        @Builder.Default
        private String type = "object";

        /**
         * 属性定义（参数名 -> 参数Schema）
         */
        private Map<String, Property> properties;

        /**
         * 必填参数列表
         */
        private List<String> required;
    }

    /**
     * 属性定义类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Property {

        /**
         * 参数类型（string, number, integer, boolean, array, object）
         */
        private String type;

        /**
         * 参数描述
         */
        private String description;

        /**
         * 数组类型的元素类型
         */
        private String items;
    }
}