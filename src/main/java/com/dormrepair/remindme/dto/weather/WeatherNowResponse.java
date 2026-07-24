package com.dormrepair.remindme.dto.weather;

import lombok.Data;

import java.util.List;

/**
 * 实时天气响应DTO
 * <p>
 * 对应和风天气 API: /v7/weather/now
 * </p>
 */
@Data
public class WeatherNowResponse {

    /**
     * 状态码
     */
    private String code;

    /**
     * API更新时间
     */
    private String updateTime;

    /**
     * 响应式页面URL
     */
    private String fxLink;

    /**
     * 实时天气数据
     */
    private NowData now;

    /**
     * 数据来源和版权信息
     */
    private Refer refer;

    /**
     * 实时天气数据对象
     */
    @Data
    public static class NowData {
        /**
         * 数据观测时间
         */
        private String obsTime;

        /**
         * 温度（摄氏度）
         */
        private String temp;

        /**
         * 体感温度（摄氏度）
         */
        private String feelsLike;

        /**
         * 天气状况图标代码
         */
        private String icon;

        /**
         * 天气状况文字描述
         */
        private String text;

        /**
         * 风向360角度
         */
        private String wind360;

        /**
         * 风向
         */
        private String windDir;

        /**
         * 风力等级
         */
        private String windScale;

        /**
         * 风速（公里/小时）
         */
        private String windSpeed;

        /**
         * 相对湿度（百分比）
         */
        private String humidity;

        /**
         * 过去1小时降水量（毫米）
         */
        private String precip;

        /**
         * 大气压强（百帕）
         */
        private String pressure;

        /**
         * 能见度（公里）
         */
        private String vis;

        /**
         * 云量（百分比）
         */
        private String cloud;

        /**
         * 露点温度
         */
        private String dew;
    }

    /**
     * 数据来源和版权信息
     */
    @Data
    public static class Refer {
        /**
         * 原始数据来源
         */
        private List<String> sources;

        /**
         * 数据许可或版权声明
         */
        private List<String> license;
    }
}
