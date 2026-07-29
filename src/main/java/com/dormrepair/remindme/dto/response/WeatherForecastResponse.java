package com.dormrepair.remindme.dto.response;

import lombok.Data;

import java.util.List;

// 天气预报响应 DTO。对应和风天气 /v7/weather/{days} 接口的返回结构，支持 3/7/10/15/30 天预报
@Data
public class WeatherForecastResponse {

    // 和风天气 API 状态码，200 成功
    private String code;

    // 接口数据更新时间
    private String updateTime;

    // 响应式页面链接，供跳转查看更完整的预报
    private String fxLink;

    // 每日预报数组，长度与请求的 days 一致
    private List<DailyForecast> daily;

    // 版权与来源声明
    private Refer refer;

    // 单日预报数据，包含白天/夜间两套天气指标、日出日落、月相、紫外线等
    @Data
    public static class DailyForecast {
        // 预报日期（yyyy-MM-dd）
        private String fxDate;

        // 日出时间（HH:mm）
        private String sunrise;

        // 日落时间（HH:mm）
        private String sunset;

        // 月升时间
        private String moonrise;

        // 月落时间
        private String moonset;

        // 月相名称（如「满月」「上弦月」）
        private String moonPhase;

        // 月相图标编码
        private String moonPhaseIcon;

        // 当日最高温度（摄氏度）
        private String tempMax;

        // 当日最低温度（摄氏度）
        private String tempMin;

        // 白天天气图标编码
        private String iconDay;

        // 白天天气文字描述
        private String textDay;

        // 夜间天气图标编码
        private String iconNight;

        // 夜间天气文字描述
        private String textNight;

        // 白天风向 360 度角度
        private String wind360Day;

        // 白天风向文字
        private String windDirDay;

        // 白天风力等级
        private String windScaleDay;

        // 白天风速（公里/小时）
        private String windSpeedDay;

        // 夜间风向 360 度角度
        private String wind360Night;

        // 夜间风向文字
        private String windDirNight;

        // 夜间风力等级
        private String windScaleNight;

        // 夜间风速（公里/小时）
        private String windSpeedNight;

        // 相对湿度百分比
        private String humidity;

        // 当日累计预计降水量（毫米）
        private String precip;

        // 大气压强（百帕）
        private String pressure;

        // 能见度（公里）
        private String vis;

        // 云量百分比
        private String cloud;

        // 紫外线强度指数（0~11+，数值越高晒越伤）
        private String uvIndex;
    }

    // 版权与来源声明
    @Data
    public static class Refer {
        // 原始数据来源列表
        private List<String> sources;

        // 版权/许可协议文字
        private List<String> license;
    }
}
