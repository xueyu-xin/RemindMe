package com.dormrepair.remindme.dto.response;

import lombok.Data;

// 城市搜索结果 DTO。对应和风天气 /geo/v2/city/lookup API 返回的单个城市条目，用于城市名联想与 LocationID 反查
@Data
public class CitySearchResult {

    // 城市/区县名称（如「海淀区」「浦东新区」）
    private String name;

    // 一级行政区（省/直辖市/自治区），如「北京」「广东」
    private String adm1;

    // 二级行政区（地级市/州），如「北京市」「深圳市」
    private String adm2;

    // 和风天气的 LocationID，后续查询天气/空气质量时都需要传此 id
    private String id;

    // 纬度字符串
    private String lat;

    // 经度字符串
    private String lon;

    // 拼装「省-市-区」显示名称，自动跳过与上级同名的层级（如 name==adm2 时不再重复显示"广州-广州"）
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();

        if (adm1 != null && !adm1.isBlank()) {
            sb.append(adm1);
        }

        if (adm2 != null && !adm2.isBlank()) {
            if (sb.length() > 0) sb.append("-");
            sb.append(adm2);
        }

        if (name != null && !name.isBlank() && !name.equals(adm2)) {
            if (sb.length() > 0) sb.append("-");
            sb.append(name);
        }

        return sb.length() > 0 ? sb.toString() : (name != null ? name : "");
    }
}
