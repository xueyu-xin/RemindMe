package com.dormrepair.remindme.enums;

/**
 * 月经状态
 * <p>
 * 注意：此枚举不持久化到数据库，仅用于计算和展示
 * 由 PeriodService 实时计算得出
 * </p>
 */
public enum PeriodStatus {

    SAFE("安全期 ♀️"),
    OVULATORY("排卵期 🌸"),
    PREMENSTRUAL("经期前 📋"),
    MENSTRUATING("经期中 🌙"),
    UNKNOWN("未设置");

    private final String displayName;

    PeriodStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PeriodStatus fromDisplayName(String displayName) {
        for (PeriodStatus value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
