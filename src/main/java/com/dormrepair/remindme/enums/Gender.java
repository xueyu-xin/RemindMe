package com.dormrepair.remindme.enums;

/**
 * 性别
 * <p>
 * 对应数据库字段：users.gender
 * 存储类型：VARCHAR(10)
 * 默认值：'PRIVATE'
 * </p>
 */
public enum Gender {

    MALE("男"),
    FEMALE("女"),
    PRIVATE("不公开");

    private final String displayName;

    Gender(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Gender fromDisplayName(String displayName) {
        for (Gender value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
