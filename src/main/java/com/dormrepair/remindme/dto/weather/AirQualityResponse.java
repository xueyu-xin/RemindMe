package com.dormrepair.remindme.dto.weather;

import lombok.Data;

import java.util.List;

/**
 * 空气质量响应DTO
 * <p>
 * 对应和风天气 API: /airquality/v1/current/{latitude}/{longitude}
 * </p>
 */
@Data
public class AirQualityResponse {

    /**
     * 元数据信息
     */
    private Metadata metadata;

    /**
     * 空气质量指数数组
     */
    private List<AirIndex> indexes;

    /**
     * 污染物浓度数据数组
     */
    private List<Pollutant> pollutants;

    /**
     * 相关联的监测站信息数组
     */
    private List<Station> stations;

    /**
     * 元数据信息
     */
    @Data
    public static class Metadata {
        /**
         * 数据标签
         */
        private String tag;
    }

    /**
     * 空气质量指数
     */
    @Data
    public static class AirIndex {
        /**
         * 指数Code（如 us-epa、qaqi）
         */
        private String code;

        /**
         * 指数名称
         */
        private String name;

        /**
         * 指数值
         */
        private Integer aqi;

        /**
         * 指数值的文本显示
         */
        private String aqiDisplay;

        /**
         * 指数等级
         */
        private String level;

        /**
         * 指数类别
         */
        private String category;

        /**
         * 指数颜色（RGBA）
         */
        private IndexColor color;

        /**
         * 首要污染物信息
         */
        private PrimaryPollutant primaryPollutant;

        /**
         * 健康影响和建议
         */
        private HealthInfo health;
    }

    /**
     * 指数颜色
     */
    @Data
    public static class IndexColor {
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

    /**
     * 首要污染物信息
     */
    @Data
    public static class PrimaryPollutant {
        /**
         * 污染物Code
         */
        private String code;

        /**
         * 污染物名称
         */
        private String name;

        /**
         * 污染物全称
         */
        private String fullName;
    }

    /**
     * 健康影响和建议
     */
    @Data
    public static class HealthInfo {
        /**
         * 健康影响描述
         */
        private String effect;

        /**
         * 健康指导意见
         */
        private Advice advice;
    }

    /**
     * 健康指导意见
     */
    @Data
    public static class Advice {
        /**
         * 一般人群建议
         */
        private String generalPopulation;

        /**
         * 敏感人群建议
         */
        private String sensitivePopulation;
    }

    /**
     * 污染物浓度数据
     */
    @Data
    public static class Pollutant {
        /**
         * 污染物Code（如 pm2p5、pm10、no2、o3、co）
         */
        private String code;

        /**
         * 污染物名称
         */
        private String name;

        /**
         * 污染物全称
         */
        private String fullName;

        /**
         * 污染物浓度值及单位
         */
        private Concentration concentration;

        /**
         * 该污染物在不同指数标准下的分指数数组
         */
        private List<SubIndex> subIndexes;
    }

    /**
     * 浓度值及单位
     */
    @Data
    public static class Concentration {
        /**
         * 浓度值
         */
        private Double value;

        /**
         * 浓度单位（如 μg/m3、ppb、ppm）
         */
        private String unit;
    }

    /**
     * 分指数
     */
    @Data
    public static class SubIndex {
        /**
         * 分指数对应的指数Code
         */
        private String code;

        /**
         * 分指数数值
         */
        private Integer aqi;

        /**
         * 分指数数值的显示文本
         */
        private String aqiDisplay;
    }

    /**
     * 监测站信息
     */
    @Data
    public static class Station {
        /**
         * 监测站Location ID
         */
        private String id;

        /**
         * 监测站名称
         */
        private String name;
    }
}
