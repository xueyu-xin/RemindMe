package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationCardResponse {

    private String type;
    private String message;

    @Builder.Default
    private ConfirmationData data = new ConfirmationData();

    private List<Action> actions;
    private Integer timeout;
    private String expiresAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfirmationData {
        private String sessionId;
        private String title;
        private String remindTime;
        private String endTime;
        private String priority;
        private String location;
        private Integer advanceMinutes;
        private Double confidence;
        private String weatherSuggestion;
        /** 是否有冲突 */
        private Boolean hasConflict;
        /** 冲突描述文本 */
        private String conflictMessage;
        /** 冲突的提醒ID（第一个） */
        private Long conflictId;
        /** 冲突列表（JSON格式） */
        private String conflictList;
        /** 建议时间 */
        private String suggestedTime;

        // ===== 批量导入场景新增字段 =====

        /** 多日程列表（导入场景） */
        private List<ScheduleItem> items;
        /** 是否为批量导入场景 */
        @Builder.Default
        private Boolean isBatch = false;
        /** 来源文件名 */
        private String sourceFile;
        /** 总日程数 */
        private Integer totalCount;
        /** 导入ID（课表场景） */
        private String importId;
        /** 成功创建数（结果反馈） */
        private Integer successCount;
        /** 失败数（结果反馈） */
        private Integer failedCount;

        // ===== 课表场景专用字段 =====

        /** 时间节次列表（课表场景） */
        private List<Map<String, Object>> timeSections;
        /** 学期开始日期（课表场景） */
        private String semesterStartDate;
        /** 学期总周数（课表场景） */
        private Integer totalWeeks;
        /** AI 建议的学期周数（课表场景） */
        private Integer suggestedWeeks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleItem {
        /** 序号（用于前端展示） */
        private Integer id;
        /** 日程标题 */
        private String title;
        /** 日期（yyyy-MM-dd） */
        private String date;
        /** 时间（HH:mm） */
        private String time;
        /** 地点 */
        private String location;
        /** 重复类型：ONCE/DAILY/WEEKLY */
        private String repeatType;
        /** 是否冲突 */
        private Boolean hasConflict;
        /** 冲突描述 */
        private String conflictMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String id;
        private String label;
        private String style;
    }
}