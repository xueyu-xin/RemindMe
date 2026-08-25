package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

// 课表 AI 识别结果 DTO。封装 OCR + LLM 对课表图片/Excel/PDF 解析后的结构化结果，包含课程明细、节次映射、推断的学期与时间配置
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleTableDTO {

    // 是否识别成功：true 表示至少解析出一门有效课程，false 表示识别失败让用户手动录入
    private Boolean success;

    // 整体识别置信度，0~1，低于阈值时建议用户重点核对
    private Double confidence;

    // 解析出的课程列表，一门课一条
    private List<CourseItem> courses;

    // 节次行头映射：把课表图片中不规则的行标题（如"上午/下午""1-2 节"）映射为对应的节次索引列表
    private Map<String, List<Integer>> sectionMapping;

    // AI 根据识别结果推断出的默认节次时间表
    private List<TimeSection> timeSections;

    // AI 推断的学期起始日期（yyyy-MM-dd），用于推算每门课实际第几周
    private String semesterStartDate;

    // AI 建议的学期总周数
    private Integer suggestedWeeks;

    // 单门课程的解析结果
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseItem extends BaseReminderFields {

        // 任课教师
        private String teacher;

        // 上课星期几，枚举格式或中文「星期一…星期日」
        private String dayOfWeek;

        // 占的节次索引列表（如 [1, 2] 表示连堂上第 1、2 节）
        private List<Integer> sectionIndices;

        // 开课的周次列表（如 [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16]）
        private List<Integer> weeks;

        // 是否仅单周上课：true 则 weeks 里只在奇数周生效
        private Boolean oddWeek;

        // 是否仅双周上课：true 则 weeks 里只在偶数周生效
        private Boolean evenWeek;
    }

    // 单节次的时间区段
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSection {
        // 节次序号
        private Integer index;

        // 开始时间 HH:mm
        private String startTime;

        // 结束时间 HH:mm
        private String endTime;
    }
}
