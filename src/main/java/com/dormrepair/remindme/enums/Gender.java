package com.dormrepair.remindme.enums;

// 性别枚举。对应 users.gender 字段，存储为 VARCHAR(10)，默认值 PRIVATE
public enum Gender {

    // 男
    MALE("男"),
    // 女：命中后会启用经期计算、经期提醒等女性专属能力
    FEMALE("女"),
    // 不公开：用户未指定或选择不暴露性别，不触发女性专属分支
    PRIVATE("不公开");

    // 中文展示名，用于日志输出、响应体中返回给调用方的可读文案
    private final String displayName;

    // 构造函数：把中文展示名绑定到对应枚举常量
    Gender(String displayName) {
        this.displayName = displayName;
    }

    // 返回枚举常量绑定的中文展示名
    public String getDisplayName() {
        return displayName;
    }

    // 根据中文展示名反查枚举常量，找不到返回 null
    public static Gender fromDisplayName(String displayName) {
        for (Gender value : values()) {
            if (value.getDisplayName().equals(displayName)) {
                return value;
            }
        }
        return null;
    }
}
