package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

// 冲突检测响应 DTO。对应 /api/reminders/check-conflict 接口的返回体，标识本次创建/编辑是否与已有提醒时间重叠
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictResponse {

    // 是否存在冲突：true 表示时间与至少 1 条已有提醒重叠
    private boolean hasConflict;
    // 冲突的具体提醒列表：每条都包含 id、标题和起止时间，用户可以选择调整时间或忽略冲突
    private List<ConflictItem> conflicts;
    // 系统建议的空闲时间：就近取不会冲突的时间点
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime suggestedTime;

    // 单条冲突的提醒信息
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictItem {
        // 已有冲突提醒的 id
        private Long id;
        // 已有冲突提醒的标题
        private String title;
        // 冲突提醒的开始时间
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private LocalDateTime remindTime;
        // 冲突提醒的结束时间
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private LocalDateTime endTime;
        // 冲突提醒的地点（可选）
        private String location;
    }
}
