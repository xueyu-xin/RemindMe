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
    private ConfirmationData data;
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