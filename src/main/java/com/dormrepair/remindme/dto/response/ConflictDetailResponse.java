package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 多项事务冲突详情弹窗响应 DTO。供前端点击冲突标记后渲染冲突详情弹窗使用
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConflictDetailResponse {
    // 当前条目的索引（或临时 ID）
    private Long currentId;
    // 当前事务标题
    private String currentTitle;
    // 当前事务提醒时间
    private String currentRemindTime;
    // 当前事务地点
    private String currentLocation;
    // 冲突提醒的 ID
    private Long conflictReminderId;
    // 冲突提醒的标题
    private String conflictTitle;
    // 冲突提醒的时间
    private String conflictRemindTime;
    // 冲突提醒的地点
    private String conflictLocation;
    // 冲突描述文案
    private String conflictMessage;
}
