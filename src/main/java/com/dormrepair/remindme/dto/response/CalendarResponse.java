package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

// 日历视图响应 DTO。对应日历月/周/日视图接口的返回体，按月视图一次性返回整个月份每天的提醒、经期、节日、农历等聚合信息
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalendarResponse {

    // 日历年份（如 2026）
    private Integer year;
    // 日历月份（1~12）
    private Integer month;
    // 当前查看的具体日期（日视图模式下使用，月视图可为空）
    private Integer day;
    // 整个月份的每一天数据，按日期升序排列
    private List<DayItem> days;

    // 单日聚合数据：包含当天的提醒数量、经期状态、节日、农历等信息，以及该天所有提醒的明细列表
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayItem {
        // 日期（yyyy-MM-dd 格式）
        @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
        private LocalDate date;
        // 是否有提醒（一次性或周期性皆算）
        private Boolean hasReminder;
        // 是否有周期提醒（课程表、打卡类），用于标识周期性质
        private Boolean hasRecurring;
        // 当日提醒总数
        private Integer reminderCount;
        // 经期状态：IN_PERIOD（经期中）/PRE_PERIOD（经期前）/OVULATION（排卵期）/SAFE（安全期）等
        private String periodStatus;
        // 节日名称（如「春节」「国庆」），非节日为空
        private String festival;
        // 农历日期字符串（如「六月初二」）
        private String lunarDate;
        // 当日所有提醒的明细列表
        private List<ReminderItem> reminders;
    }

    // 单条提醒的日历条目：包含标题、时间、优先级、来源（一次性/周期）等展示要素
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReminderItem {
        // 提醒 id
        private Long id;
        // 提醒标题
        private String title;
        // 提醒开始时间（字符串）
        private String remindTime;
        // 提醒结束时间（字符串）
        private String endTime;
        // 优先级：LOW/NORMAL/HIGH
        private String priority;
        // 提醒状态：PENDING/DONE/CANCELLED
        private String status;
        // 二次确认状态
        private String confirmStatus;
        // 地点
        private String location;
        // 是否为周期提醒实例
        private Boolean isRecurring;
        // 周期范围描述（如「第 1-16 周」），课程表场景下使用
        private String recurringRange;
        // 重复类型：ONCE/DAILY/WEEKLY 等
        private String repeatType;
        // 提醒来源：USER_INPUT/COURSE/EXCEL_IMPORT/WEATHER_LINK/PERIOD_REMINDER，前端据此渲染紫色课程标签
        private String source;
    }
}
