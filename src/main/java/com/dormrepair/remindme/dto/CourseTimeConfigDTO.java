package com.dormrepair.remindme.dto;

import lombok.Data;

import java.util.List;

// 课表节次时间配置 DTO。课表场景中用户在「配置时间」页提交给后端的数据，用于把课程节次映射成具体的提醒时间
@Data
public class CourseTimeConfigDTO {

    // 学期开学日期，格式 yyyy-MM-dd，用于计算每门课对应周次的实际日期
    private String semesterStartDate;

    // 时间配置联动模式：TOP_DOWN 自顶向下推时间 / BOTTOM_UP 自底向上 / NONE 不联动
    private String linkMode;

    // 用户自定义的每节课起止时间列表，长度通常是 10~14 节
    private List<SectionTime> sectionTimes;

    // 导入任务 id：课表识别产生的 importId，关联到该配置
    private String importId;

    // 学期总周数，用于生成每门课的真实提醒日期范围
    private Integer totalWeeks;

    // 单节次的起止时间
    @Data
    public static class SectionTime {
        // 节次索引，从 1 开始
        private Integer index;

        // 节次开始时间 HH:mm
        private String startTime;

        // 节次结束时间 HH:mm
        private String endTime;
    }
}
