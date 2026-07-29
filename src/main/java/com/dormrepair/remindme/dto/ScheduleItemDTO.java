package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日程识别项 DTO
 * <p>
 * 用于 AI 识别后在 Service 层传递的日程项数据
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleItemDTO {

    /** 事项标题 */
    private String title;

    /** 日期，格式 yyyy-MM-dd */
    private String date;

    /** 时间，格式 HH:mm */
    private String time;

    /** 地点 */
    private String location;

    /** 重复类型：ONCE / DAILY / WEEKLY */
    private String repeatType;
}
