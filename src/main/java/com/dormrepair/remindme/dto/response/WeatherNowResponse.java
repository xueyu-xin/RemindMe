package com.dormrepair.remindme.dto.response;

import lombok.Data;

import java.util.List;

// 实时天气响应 DTO。对应和风天气 /v7/weather/now 接口的返回结构
@Data
public class WeatherNowResponse {

    // 和风天气 API 状态码，200 表示成功，其他为各类错误码
    private String code;

    // 接口最后更新时间，字符串格式
    private String updateTime;

    // 和风天气响应式页面链接，供跳转查看完整天气
    private String fxLink;

    // 实时天气数据主体：温度、体感、风向、湿度等
    private NowData now;

    // 数据来源与版权声明，使用和风天气数据时必须按协议展示
    private Refer refer;

    // 实时天气观测数据
    @Data
    public static class NowData {
        // 观测站记录本次数据的时间点
        private String obsTime;

        // 当前温度（摄氏度）
        private String temp;

        // 体感温度（摄氏度），综合考虑风速/湿度的实际感受
        private String feelsLike;

        // 天气图标编码
        private String icon;

        // 天气文字描述（如「晴」「多云转小雨」）
        private String text;

        // 风向 360 度角度（0 为正北，90 为正东）
        private String wind360;

        // 风向的中文文字（如「东北风」）
        private String windDir;

        // 风力等级（如 3 级）
        private String windScale;

        // 风速（公里/小时）
        private String windSpeed;

        // 相对湿度百分比（如 65）
        private String humidity;

        // 过去 1 小时累计降水量（毫米）
        private String precip;

        // 大气压强（百帕 hPa）
        private String pressure;

        // 能见度（公里）
        private String vis;

        // 云量百分比（0 晴空万里，100 满天阴云）
        private String cloud;

        // 露点温度，空气冷却到结露的临界温度
        private String dew;
    }

    // 数据来源与版权声明
    @Data
    public static class Refer {
        // 原始数据来源列表，如气象站、卫星等
        private List<String> sources;

        // 许可协议/版权声明文字，使用天气数据时必须遵守和风的协议展示
        private List<String> license;
    }
}
