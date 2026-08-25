package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * P2-2 公共基类：聚合提醒/日程/课程/事务条目中的 4 个通用业务字段。
 * 适用 DTO（共 5 份）：TaskDetail、ScheduleItemDTO、ScheduleTableDTO.CourseItem、
 * MultiItemModifyRequest（request 包）、ConfirmationCardResponse.ConfirmationItem（response 包）。
 *
 * JSON 反序列化：父类字段沿用同名 key（title/location/note/priority），
 * 未引入 @JsonTypeInfo，不改变序列化格式，与当前前后端契约完全兼容。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseReminderFields {

    // 标题（提醒事项 / 课程名 / 日程名）
    private String title;

    // 地点（可选。教室/场馆/食堂/快递站等）
    private String location;

    // 备注（可选。补充说明、携带物品等）
    private String note;

    // 优先级（可选。LOW / NORMAL / HIGH）
    private String priority;
}
