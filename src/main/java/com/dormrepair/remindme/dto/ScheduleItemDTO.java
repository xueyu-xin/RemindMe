package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 日程识别项 DTO。AI 解析出的单条日程在 Service 层之间流转时使用，承载结构化的标题/日期/时间/地点/重复类型
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ScheduleItemDTO extends BaseReminderFields {

    // 日程发生日期，格式 yyyy-MM-dd
    private String date;

    // 日程发生时间，格式 HH:mm
    private String time;

    // 日程结束时间，格式 HH:mm（课表导入场景下来自节次时间表，可选）
    private String endTime;

    // 重复类型：ONCE（单次）/ DAILY（每日）/ WEEKLY（每周）
    private String repeatType;
}
