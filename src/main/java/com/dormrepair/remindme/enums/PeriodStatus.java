package com.dormrepair.remindme.enums;

// 月经状态枚举。不持久化到数据库，由 PeriodService 依据 cycleDays / lastPeriodStart / periodDuration 实时计算得出，用于响应返回
public enum PeriodStatus {

    // 安全期：经期结束后到排卵日前的相对不易受孕期
    SAFE("安全期 ♀️"),
    // 排卵期：易孕期，排卵日前后约一周
    OVULATORY("排卵期 🌸"),
    // 经期前：距下次经期 1~7 天，尚未进入经期
    PREMENSTRUAL("经期前 📋"),
    // 经期中：当前日期落在 lastPeriodStart 起的 periodDuration 天内
    MENSTRUATING("经期中 🌙"),
    // 未设置：用户未填写周期或上次起始日，无法计算
    UNKNOWN("未设置");

    // 中文展示名（含表情后缀），直接用于响应体返回给调用方
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    PeriodStatus(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static PeriodStatus fromDisplayName(String displayName) {
        for (PeriodStatus value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
