package com.dormrepair.remindme.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    public static class ConfirmationData {
        private String sessionId;
        private String title;
        private String remindTime;
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