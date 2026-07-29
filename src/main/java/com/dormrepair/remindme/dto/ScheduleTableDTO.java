package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 课表识别结果 DTO
 * <p>
 * 用于存储 AI 识别后的课表数据
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleTableDTO {

    /** 是否识别成功 */
    private Boolean success;

    /** 置信度（0-1 之间） */
    private Double confidence;

    /** 课程列表 */
    private List<CourseItem> courses;

    /** 节次映射（行头名称 → 小节索引列表） */
    private Map<String, List<Integer>> sectionMapping;

    /** 推断的时间表 */
    private List<TimeSection> timeSections;

    /** 推断的学期开始日期（格式 yyyy-MM-dd） */
    private String semesterStartDate;

    /** AI 建议的学期周数，默认 16 */
    private Integer suggestedWeeks;

    /**
     * 课程项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseItem {
        /** 课程标题 */
        private String title;

        /** 教师 */
        private String teacher;

        /** 星期几（如：星期一） */
        private String dayOfWeek;

        /** 节次索引列表（如 [1, 2]） */
        private List<Integer> sectionIndices;

        /** 周次列表（如 [1, 2, 3, ..., 17]） */
        private List<Integer> weeks;

        /** 是否单周 */
        private Boolean oddWeek;

        /** 是否双周 */
        private Boolean evenWeek;

        /** 上课地点 */
        private String location;
    }

    /**
     * 时间段
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSection {
        /** 索引（第几节） */
        private Integer index;

        /** 开始时间（格式 HH:mm） */
        private String startTime;

        /** 结束时间（格式 HH:mm） */
        private String endTime;
    }
}
