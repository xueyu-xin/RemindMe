package com.dormrepair.remindme.dto.response;

import lombok.Data;

import java.util.List;

// 空气质量响应 DTO。对应和风天气 /airquality/v1/current/{lat}/{lon} 接口，承载 AQI 指数、首要污染物、健康建议等数据
@Data
public class AirQualityResponse {

    // 元数据信息
    private Metadata metadata;

    // 不同标准下的空气质量指数数组，如 us-epa（美标）、qaqi（国标）等
    private List<AirIndex> indexes;

    // 各类污染物的详细浓度数据（PM2.5/PM10/NO2/O3/CO/SO2 等）
    private List<Pollutant> pollutants;

    // 附近关联的空气监测站信息
    private List<Station> stations;

    // 响应元数据
    @Data
    public static class Metadata {
        // 数据标签，用于版本或调试
        private String tag;
    }

    // 单套标准下的空气质量指数
    @Data
    public static class AirIndex {
        // 指数标准代码：us-epa（美国 EPA 标准）、qaqi（国标等）
        private String code;

        // 指数标准中文名称
        private String name;

        // AQI 数值（0~500），整数
        private Integer aqi;

        // AQI 展示文本（如 "132"，可能带单位或后缀）
        private String aqiDisplay;

        // 指数等级（如 3 代表 3 级）
        private String level;

        // 指数类别（如「轻度污染」）
        private String category;

        // 指数对应颜色（RGBA），用于按级别区分显示
        private IndexColor color;

        // 首要污染物（可能多项，这里给最主要的一项）
        private PrimaryPollutant primaryPollutant;

        // 健康影响与指导建议
        private HealthInfo health;
    }

    // 颜色 RGBA
    @Data
    public static class IndexColor {
        private Integer red;
        private Integer green;
        private Integer blue;
        private Double alpha;
    }

    // 首要污染物
    @Data
    public static class PrimaryPollutant {
        // 污染物代码（如 pm2p5）
        private String code;

        // 污染物简称（如 PM2.5）
        private String name;

        // 污染物全称（如「细颗粒物」）
        private String fullName;
    }

    // 健康影响与建议
    @Data
    public static class HealthInfo {
        // 当前空气质量对健康的影响描述
        private String effect;

        // 不同人群的行动指导
        private Advice advice;
    }

    // 针对不同人群的健康建议
    @Data
    public static class Advice {
        // 一般人群建议（如「建议减少户外运动」）
        private String generalPopulation;

        // 敏感人群建议（老人、儿童、呼吸道疾病患者等）
        private String sensitivePopulation;
    }

    // 单项污染物浓度数据
    @Data
    public static class Pollutant {
        // 污染物代码（pm2p5/pm10/no2/o3/co/so2 等）
        private String code;

        // 污染物简称（PM2.5 等）
        private String name;

        // 污染物全称
        private String fullName;

        // 浓度数值及单位
        private Concentration concentration;

        // 同一污染物在不同 AQI 标准下的分指数（IAQI）
        private List<SubIndex> subIndexes;
    }

    // 浓度值及单位
    @Data
    public static class Concentration {
        // 数值
        private Double value;

        // 单位（μg/m3、ppb、ppm 等）
        private String unit;
    }

    // 单项污染物的分指数 IAQI
    @Data
    public static class SubIndex {
        // 对应 AQI 标准代码（us-epa 等）
        private String code;

        // 分指数数值
        private Integer aqi;

        // 分指数显示文本
        private String aqiDisplay;
    }

    // 监测站信息
    @Data
    public static class Station {
        // 监测站 Location ID
        private String id;

        // 监测站名称（如「海淀区万柳监测站」）
        private String name;
    }
}
