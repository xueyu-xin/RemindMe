package com.dormrepair.remindme.dto.response;

import lombok.Data;

import java.util.List;

// 气象灾害预警响应 DTO。对应和风天气 /weatheralert/v1/current/{lat}/{lon} 接口
@Data
public class WeatherAlertResponse {

    // 元数据：包含标签、是否空结果、来源声明等辅助信息
    private Metadata metadata;

    // 生效中的预警列表，可能多条（如同时出现暴雨+雷电预警）
    private List<Alert> alerts;

    // 响应元数据
    @Data
    public static class Metadata {
        // 数据标签，用于版本标识或调试用途
        private String tag;

        // 请求成功但无预警时为 true
        private Boolean zeroResult;

        // 归属声明：和风协议要求开发者必须把此内容随预警数据一同展示
        private List<String> attributions;
    }

    // 单条预警信息：包含发布机构、事件类型、级别、起止时间、详细描述、防御指南等完整字段
    @Data
    public static class Alert {
        // 预警唯一 id
        private String id;

        // 发布预警的官方机构名称（如「国家气象中心」）
        private String senderName;

        // 预警生成的原始发布时间
        private String issuedTime;

        // 预警信息的性质（新发布/更新/取消）
        private MessageType messageType;

        // 事件类型（大风、暴雨、高温等）
        private EventType eventType;

        // 紧迫程度：Immediate（立即）/Expected（预期内）/Future（未来）/Unknown
        private String urgency;

        // 严重程度：Minor/Moderate/Severe/Extreme
        private String severity;

        // 确定性：Observed（已观测）/Likely（很可能）/Possible（可能）/Unlikely（不太可能）/Unknown
        private String certainty;

        // 预警图标编码
        private String icon;

        // 预警颜色（蓝/黄/橙/红等）及 RGBA 分量
        private AlertColor color;

        // 预警生效时间
        private String effectiveTime;

        // 灾害事件预计实际开始时间
        private String onsetTime;

        // 预警失效时间
        private String expireTime;

        // 标题式简要描述（如「暴雨橙色预警」）
        private String headline;

        // 详细描述，解释发生范围、强度、影响等
        private String description;

        // 触发本预警的标准或条件
        private String criteria;

        // 防御指南：公众应采取的行动（如「人员勿随意外出」「切断户外危险电源」）
        private String instruction;

        // 应对方式代码列表：Evacuate/Shelter/Prepare/Execute/Avoid/Monitor/Assess/AllClear 等
        private List<String> responseTypes;
    }

    // 预警信息性质
    @Data
    public static class MessageType {
        // 性质代码：alert（新发布）/update（更新旧预警）/cancel（取消预警）
        private String code;

        // 当前预警所取代或取消的旧预警 id 列表
        private List<String> supersedes;
    }

    // 预警事件类型
    @Data
    public static class EventType {
        // 事件中文名称（如「大风」「暴雨」）
        private String name;

        // 事件编码（如 1006 代表大风）
        private String code;
    }

    // 预警级别颜色
    @Data
    public static class AlertColor {
        // 颜色代码：blue（蓝）/yellow（黄）/orange（橙）/red（红）
        private String code;

        private Integer red;
        private Integer green;
        private Integer blue;
        private Double alpha;
    }
}
