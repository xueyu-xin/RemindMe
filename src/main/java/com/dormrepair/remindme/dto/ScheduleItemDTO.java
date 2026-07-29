package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 日程识别项 DTO。AI 解析出的单条日程在 Service 层之间流转时使用，承载结构化的标题/日期/时间/地点/重复类型
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleItemDTO {

    // 日程标题（如「期末考试」「社团招新」）
    private String title;

    // 日程发生日期，格式 yyyy-MM-dd
    private String date;

    // 日程发生时间，格式 HH:mm
    private String time;

    // 地点（可选）
    private String location;

    // 重复类型：ONCE（单次）/ DAILY（每日）/ WEEKLY（每周）
    private String repeatType;
}
