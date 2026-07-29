package com.dormrepair.remindme.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

// 工具 Function Calling Schema 定义。对应 OpenAI 风格 JSON：type/function/{name,description,parameters}
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    // 工具名，与 @AgentTool.name 一致
    private String name;

    // 工具作用描述，传给 LLM
    private String description;

    // 参数定义：类型 + 属性表 + 必填列表
    private Parameters parameters;

    // 参数结构：固定 type=object，承载 properties 和 required
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Parameters {

        // JSON 类型，固定填 object
        @Builder.Default
        private String type = "object";

        // 参数属性：参数名 → Property（类型+描述）
        private Map<String, Property> properties;

        // 必填参数名集合；缺省为空
        private List<String> required;
    }

    // 单个参数属性
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Property {

        // JSON Schema 类型：string / integer / number / boolean / array / object
        private String type;

        // 参数含义说明，供 LLM 判断是否传值；包含"(必填)"字样将被自动划入 required
        private String description;

        // 当 type=array 时：元素的 JSON 类型（string 等）
        private String items;
    }
}
