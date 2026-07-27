package com.dormrepair.remindme.dto.response;

import lombok.Data;

/**
 * 城市搜索结果 DTO
 * <p>
 * 对应和风天气 /geo/v2/city/lookup API 返回的单个城市信息
 * </p>
 */
@Data
public class CitySearchResult {

    /**
     * 城市名（区/县名）
     */
    private String name;

    /**
     * 省（上级行政区划一）
     */
    private String adm1;

    /**
     * 市（上级行政区划二）
     */
    private String adm2;

    /**
     * 和风天气 LocationID
     */
    private String id;

    /**
     * 纬度
     */
    private String lat;

    /**
     * 经度
     */
    private String lon;

    /**
     * 返回 "省-市-区" 格式的显示名称
     * <p>
     * 自动处理直辖市（adm2 与 adm1 相同）和同名情况（name 与 adm2 相同），
     * 避免出现 "北京-北京-北京" 这样的冗余显示
     * </p>
     *
     * @return 格式化后的显示名称
     */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();

        if (adm1 != null && !adm1.isBlank()) {
            sb.append(adm1);
        }

        if (adm2 != null && !adm2.isBlank() && !adm2.equals(adm1)) {
            if (sb.length() > 0) sb.append("-");
            sb.append(adm2);
        }

        if (name != null && !name.isBlank() && !name.equals(adm2) && !name.equals(adm1)) {
            if (sb.length() > 0) sb.append("-");
            sb.append(name);
        }

        return sb.length() > 0 ? sb.toString() : (name != null ? name : "");
    }
}
