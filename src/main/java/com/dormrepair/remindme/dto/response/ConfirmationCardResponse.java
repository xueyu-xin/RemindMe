package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

// 确认卡片响应 DTO。AI 解析/课表导入后要求用户确认时使用，通过 type 区分单条提醒、批量导入、课表配置等不同卡片场景
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationCardResponse {

    // 卡片类型：reminder（单条提醒确认）/schedule_import（批量导入）/schedule_time_config（课表时间配置）/schedule_result（导入结果反馈）等
    private String type;
    // 卡片顶部的提示文案，如"请确认以下提醒信息"
    private String message;

    // 卡片核心数据：不同 type 对应填充的字段集不同；通过 @Builder.Default 保证即使为空也返回空对象而非 null
    @Builder.Default
    private ConfirmationData data = new ConfirmationData();

    // 可操作按钮列表：典型场景如 [确认创建]、[跳过冲突]、[全部覆盖]
    private List<Action> actions;
    // 卡片超时秒数：超时后卡片自动关闭并按默认策略处理
    private Integer timeout;
    // 卡片过期的绝对时间戳
    private String expiresAt;

    // 卡片核心数据，通过 Include.NON_NULL 过滤空值字段，避免冗余
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfirmationData {
        // 确认会话 id：后续用户点「确认/取消」接口都通过此 id 定位会话
        private String sessionId;
        // 解析出的提醒标题
        private String title;
        // 解析出的提醒开始时间（已格式化为字符串）
        private String remindTime;
        // 解析出的提醒结束时间
        private String endTime;
        // 解析出的优先级
        private String priority;
        // 解析出的地点
        private String location;
        // 解析出的备注（用户对提醒的补充说明）
        private String note;
        // 解析出的提前提醒分钟数
        private Integer advanceMinutes;
        // LLM 对本次解析结果的置信度，0~1，低于阈值时需要用户特别注意核实
        private Double confidence;
        // 天气建议：若解析出提醒当天有雨雪/高温，会给出"请带伞"等文案
        private String weatherSuggestion;
        // 是否与已有提醒存在时间冲突
        private Boolean hasConflict;
        // 冲突描述文本
        private String conflictMessage;
        // 第一个冲突的提醒 id，用于定位冲突来源
        private Long conflictId;
        // 完整冲突列表
        private List<ConflictResponse.ConflictItem> conflictList;
        // 系统建议的空闲时间
        private String suggestedTime;
        // 是否为周期提醒：前端通过此字段判断渲染时间控件类型，无需从显示文本反推
        @JsonProperty("isRecurring")
        private Boolean isRecurring;
        // 周期提醒起始日期（yyyy-MM-dd）
        @JsonProperty("startDate")
        private String startDate;
        // 周期提醒结束日期（yyyy-MM-dd）
        @JsonProperty("endDate")
        private String endDate;
        // 周期提醒纯时间（HH:mm:ss），用于编辑表单回填 type=time
        @JsonProperty("recurringRemindTime")
        private String recurringRemindTime;
        // 周期提醒重复类型：DAILY/WEEKLY/MONTHLY
        @JsonProperty("repeatType")
        private String repeatType;

        // ===== 批量导入场景字段 =====

        // 识别到的多条日程条目列表（课表/考试安排等识别后供用户逐条核对）
        private List<ScheduleItem> items;
        // 是否为批量导入场景
        @Builder.Default
        private Boolean isBatch = false;
        // 原始上传文件名，便于核对
        private String sourceFile;
        // 识别到的日程总数量
        private Integer totalCount;
        // 导入任务 id：课表场景下确认/取消操作都通过此 id 调用导入接口
        private String importId;
        // 批量导入完成后成功创建的数量
        private Integer successCount;
        // 批量导入完成后失败/跳过的数量
        private Integer failedCount;

        // ===== 课表场景专用字段 =====

        // 节次时间表：课表识别完成后给用户自定义每节课的起止时间
        private List<Map<String, Object>> timeSections;
        // 学期开始日期（yyyy-MM-dd），用于推算第几周
        private String semesterStartDate;
        // 学期总周数，用户可调整
        private Integer totalWeeks;
        // AI 根据上传文件分析建议的学期周数
        private Integer suggestedWeeks;
    }

    // 批量导入场景中单条日程条目，对应识别结果中的每一行
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleItem {
        // 展示序号，从 1 开始
        private Integer id;
        // 日程标题（课程名/事件名）
        private String title;
        // 日期，格式 yyyy-MM-dd
        private String date;
        // 时间，格式 HH:mm
        private String time;
        // 地点（教室/场馆名）
        private String location;
        // 重复类型：ONCE/DAILY/WEEKLY 等
        private String repeatType;
        // 该条是否与已有提醒冲突
        private Boolean hasConflict;
        // 冲突描述文案
        private String conflictMessage;
    }

    // 卡片可点击按钮的定义
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        // 按钮 id：通过此 id 回调后端对应接口，如 "confirm"/"cancel"/"overwrite-all"
        private String id;
        // 按钮显示文案，如 "确认创建"
        private String label;
        // 按钮样式标识：primary/danger/ghost 等
        private String style;
    }
}
