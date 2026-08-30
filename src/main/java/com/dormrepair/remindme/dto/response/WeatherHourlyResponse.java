package com.dormrepair.remindme.dto.response;

import lombok.Data;

import java.util.List;

// 逐小时预报响应 DTO。对应和风天气 /v7/weather/24h 接口的返回结构，返回未来 24 小时逐小时预报
@Data
public class WeatherHourlyResponse {

    // 和风天气 API 状态码，200 表示成功
    private String code;

    // 接口数据更新时间
    private String updateTime;

    // 响应式页面链接，供跳转查看更完整的逐小时预报
    private String fxLink;

    // 逐小时预报数组，通常返回未来 24 小时的条目
    private List<HourlyForecast> hourly;

    // 版权与来源声明
    private Refer refer;

    // 单小时预报数据
    @Data
    public static class HourlyForecast {
        // 预报时间（yyyy-MM-ddTHH+mm，如 "2026-08-31T12:00+08:00"）
        private String fxTime;

        // 温度（摄氏度）
        private String temp;

        // 天气图标编码
        private String icon;

        // 天气文字描述（如「晴」「小雨」）
        private String text;

        // 风向 360 度角度
        private String wind360;

        // 风向中文文字（如「东北风」）
        private String windDir;

        // 风力等级（如 3 级）
        private String windScale;

        // 风速（公里/小时）
        private String windSpeed;

        // 相对湿度百分比
        private String humidity;

        // 过去 1 小时累计降水量（毫米）
        private String precip;

        // 大气压强（百帕 hPa）
        private String pressure;

        // 云量百分比
        private String cloud;

        // 露点温度（摄氏度）
        private String dew;
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
