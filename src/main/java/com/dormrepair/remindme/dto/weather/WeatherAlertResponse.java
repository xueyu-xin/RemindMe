package com.dormrepair.remindme.dto.weather;

import lombok.Data;

import java.util.List;

/**
 * 天气预警响应DTO
 * <p>
 * 对应和风天气 API: /weatheralert/v1/current/{latitude}/{longitude}
 * </p>
 */
@Data
public class WeatherAlertResponse {

    /**
     * 元数据信息
     */
    private Metadata metadata;

    /**
     * 预警信息列表
     */
    private List<Alert> alerts;

    /**
     * 元数据信息
     */
    @Data
    public static class Metadata {
        /**
         * 数据标签
         */
        private String tag;

        /**
         * true表示请求成功但无数据返回（如查询地点无预警）
         */
        private Boolean zeroResult;

        /**
         * 数据来源或声明（开发者必须将此内容与当前数据一起展示）
         */
        private List<String> attributions;
    }

    /**
     * 预警信息
     */
    @Data
    public static class Alert {
        /**
         * 预警信息唯一标识
         */
        private String id;

        /**
         * 预警发布机构名称
         */
        private String senderName;

        /**
         * 原始预警信息生成时间
         */
        private String issuedTime;

        /**
         * 预警信息性质
         */
        private MessageType messageType;

        /**
         * 预警事件类型
         */
        private EventType eventType;

        /**
         * 预警紧迫程度
         */
        private String urgency;

        /**
         * 预警严重程度
         */
        private String severity;

        /**
         * 预警确定性或可信度
         */
        private String certainty;

        /**
         * 预警对应的图标代码
         */
        private String icon;

        /**
         * 预警信息颜色
         */
        private AlertColor color;

        /**
         * 预警生效时间
         */
        private String effectiveTime;

        /**
         * 预警事件预计开始时间
         */
        private String onsetTime;

        /**
         * 预警失效时间
         */
        private String expireTime;

        /**
         * 预警简要描述或标题
         */
        private String headline;

        /**
         * 预警详细描述
         */
        private String description;

        /**
         * 预警触发标准或条件
         */
        private String criteria;

        /**
         * 预警防御指南或行动指导
         */
        private String instruction;

        /**
         * 预警应对方式类型代码
         */
        private List<String> responseTypes;
    }

    /**
     * 预警信息性质
     */
    @Data
    public static class MessageType {
        /**
         * 性质代码（alert/update/cancel）
         */
        private String code;

        /**
         * 当前预警取代或取消的预警ID列表
         */
        private List<String> supersedes;
    }

    /**
     * 预警事件类型
     */
    @Data
    public static class EventType {
        /**
         * 事件名称（如大风、暴雨等）
         */
        private String name;

        /**
         * 事件代码（如1006）
         */
        private String code;
    }

    /**
     * 预警信息颜色
     */
    @Data
    public static class AlertColor {
        /**
         * 颜色代码（如 blue、yellow、orange、red）
         */
        private String code;

        /**
         * 红色分量（0-255）
         */
        private Integer red;

        /**
         * 绿色分量（0-255）
         */
        private Integer green;

        /**
         * 蓝色分量（0-255）
         */
        private Integer blue;

        /**
         * 透明度（0-1）
         */
        private Double alpha;
    }
}
