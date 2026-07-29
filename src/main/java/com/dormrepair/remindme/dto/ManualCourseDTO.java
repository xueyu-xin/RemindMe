package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 手动创建课程 DTO。课表批量导入页「手动新增课程」表单使用，用户不依赖文件直接录入课程信息
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualCourseDTO {

    // 课程名称（如「大学物理」）
    private String courseName;

    // 星期几，支持中文「星期一」或英文枚举
    private String dayOfWeek;

    // 节次范围字符串，支持「1-2」表示第 1 到第 2 节两节课连堂
    private String sectionRange;

    // 周次范围字符串，支持「1-17」连续或「1,3,5,7」离散两种写法
    private String weekRange;

    // 单双周标记：可选值「单周」「双周」「全部」，缺省时按全部处理
    private String oddEvenWeek;

    // 上课教室或地点，可选
    private String location;

    // 任课教师，可选
    private String teacher;
}
