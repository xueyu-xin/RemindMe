package com.dormrepair.remindme.dto;

import lombok.Data;

import java.util.List;

/**
 * 课程时间配置 DTO
 * <p>
 * 用于接收用户配置的课程时间信息
 * </p>
 */
@Data
public class CourseTimeConfigDTO {

    /** 学期开始日期（格式 yyyy-MM-dd） */
    private String semesterStartDate;

    /** 联动模式：TOP_DOWN（自顶向下）/ BOTTOM_UP（自底向上）/ BOTH / NONE */
    private String linkMode;

    /** 节次时间列表 */
    private List<SectionTime> sectionTimes;

    /** 导入ID（课表场景） */
    private String importId;

    /** 学期总周数 */
    private Integer totalWeeks;

    /**
     * 节次时间
     */
    @Data
    public static class SectionTime {
        /** 节次索引 */
        private Integer index;

        /** 开始时间（格式 HH:mm） */
        private String startTime;

        /** 结束时间（格式 HH:mm） */
        private String endTime;
    }
}
