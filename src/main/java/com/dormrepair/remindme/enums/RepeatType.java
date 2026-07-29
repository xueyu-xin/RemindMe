package com.dormrepair.remindme.enums;

// 周期提醒重复类型枚举。对应 recurring_reminders.repeat_type 字段，表示周期模板的重复粒度：日/周/月
public enum RepeatType {

    // 按天重复：每天（或每 N 天，步长单独存 repeat_interval）生成一条实例
    DAILY,
    // 按周重复：每周的指定星期几（repeat_days 存 1~7 集合）生成一条实例
    WEEKLY,
    // 按月重复：每月指定日期（repeat_day_of_month 存 1~31）生成一条实例
    MONTHLY
}
