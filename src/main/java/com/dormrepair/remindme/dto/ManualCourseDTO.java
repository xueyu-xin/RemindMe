package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手动创建课程 DTO
 * <p>
 * 表示用户手动输入的课程信息
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualCourseDTO {

    /** 课程名 */
    private String courseName;

    /** 星期几（如：星期一） */
    private String dayOfWeek;

    /** 节次范围（如："1-2" 表示第1节到第2节） */
    private String sectionRange;

    /** 周次范围（如："1-17" 或 "1,3,5,7"） */
    private String weekRange;

    /** 单双周（可选值：单周 / 双周 / 全部） */
    private String oddEvenWeek;

    /** 地点（可选） */
    private String location;

    /** 教师（可选） */
    private String teacher;
}
