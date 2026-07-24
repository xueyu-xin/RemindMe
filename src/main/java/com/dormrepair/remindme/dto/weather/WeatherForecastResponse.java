package com.dormrepair.remindme.dto.weather;

import lombok.Data;

import java.util.List;

/**
 * 天气预报响应DTO
 * <p>
 * 对应和风天气 API: /v7/weather/{days}
 * 支持3天、7天、10天、15天、30天预报
 * </p>
 */
@Data
public class WeatherForecastResponse {

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
     * 每日天气预报数据数组
     */
    private List<DailyForecast> daily;

    /**
     * 数据来源和版权信息
     */
    private Refer refer;

    /**
     * 每日天气预报数据
     */
    @Data
    public static class DailyForecast {
        /**
         * 预报日期
         */
        private String fxDate;

        /**
         * 日出时间
         */
        private String sunrise;

        /**
         * 日落时间
         */
        private String sunset;

        /**
         * 月升时间
         */
        private String moonrise;

        /**
         * 月落时间
         */
        private String moonset;

        /**
         * 月相名称
         */
        private String moonPhase;

        /**
         * 月相图标代码
         */
        private String moonPhaseIcon;

        /**
         * 最高温度（摄氏度）
         */
        private String tempMax;

        /**
         * 最低温度（摄氏度）
         */
        private String tempMin;

        /**
         * 白天天气状况图标代码
         */
        private String iconDay;

        /**
         * 白天天气状况文字描述
         */
        private String textDay;

        /**
         * 夜间天气状况图标代码
         */
        private String iconNight;

        /**
         * 夜间天气状况文字描述
         */
        private String textNight;

        /**
         * 白天风向360角度
         */
        private String wind360Day;

        /**
         * 白天风向
         */
        private String windDirDay;

        /**
         * 白天风力等级
         */
        private String windScaleDay;

        /**
         * 白天风速（公里/小时）
         */
        private String windSpeedDay;

        /**
         * 夜间风向360角度
         */
        private String wind360Night;

        /**
         * 夜间风向
         */
        private String windDirNight;

        /**
         * 夜间风力等级
         */
        private String windScaleNight;

        /**
         * 夜间风速（公里/小时）
         */
        private String windSpeedNight;

        /**
         * 相对湿度（百分比）
         */
        private String humidity;

        /**
         * 当天总降水量（毫米）
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
         * 紫外线强度指数
         */
        private String uvIndex;
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
