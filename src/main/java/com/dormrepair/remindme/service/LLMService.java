package com.dormrepair.remindme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * LLM服务
 * <p>
 * 调用DeepSeek API进行时间解析和意图识别
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ParsedResult parseReminder(String userInput) {
        log.info("开始解析用户输入: {}", userInput);
        String prompt = buildPrompt(userInput);

        try {
            log.info("构建Prompt完成，开始调用LLM...");
            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);

            String content = chatModel.call(promptObj).getResult().getOutput().getText();

            log.info("LLM调用成功，响应内容: {}", content);

            JsonNode root = objectMapper.readTree(content);

            return ParsedResult.builder()
                    .title(root.has("title") ? root.get("title").asText() : null)
                    .remindTime(root.has("remindTime") ? parseDateTime(root.get("remindTime").asText()) : null)
                    .intentType(root.has("intentType") ? root.get("intentType").asText() : "UNKNOWN")
                    .location(root.has("location") ? root.get("location").asText() : null)
                    .priority(root.has("priority") ? root.get("priority").asText() : "NORMAL")
                    .build();

        } catch (Exception e) {
            log.error("LLM解析失败，错误类型: {}, 错误信息: {}", e.getClass().getName(), e.getMessage());
            log.error("完整异常堆栈:", e);
            return ParsedResult.builder()
                    .title(userInput)
                    .intentType("UNKNOWN")
                    .build();
        }
    }

    private String buildPrompt(String userInput) {
        String currentDate = LocalDateTime.now().format(FORMATTER);
        return """
            你是一个智能提醒助手。请解析用户的输入，提取提醒信息。
            
            当前时间：%s
            
            要求：
            1. 返回严格的JSON格式
            2. JSON包含以下字段：
               - title: 提醒事项名称（字符串，必填）
               - remindTime: 提醒时间（格式：yyyy-MM-dd HH:mm:ss，必填）
               - intentType: 意图类型（REMINDER/QUERY/DELETE/UPDATE/CONFIRM/CANCEL/WEATHER/UNKNOWN）
               - location: 地点（字符串，可选）
               - priority: 优先级（LOW/NORMAL/HIGH，默认NORMAL）
            
            时间解析规则：
            - "明天下午3点" → 当前日期+1天，15:00:00
            - "后天早上9点" → 当前日期+2天，09:00:00
            - "本周六晚上8点" → 本周六，20:00:00
            - "周一上午10点" → 最近的周一，10:00:00
            - "每天早上7点" → 今天，07:00:00（重复提醒）
            - "5分钟后" → 当前时间+5分钟
            - "等会"、"待会"、"过一会儿"、"一会儿" → 当前时间+10分钟
            - "现在"、"马上" → 当前时间
            - 如果无法解析时间，返回当前时间+10分钟
            
            示例输入："明天下午3点还书"
            示例输出：{"title":"还书","remindTime":"%s","intentType":"REMINDER","location":null,"priority":"NORMAL"}
            
            用户输入: %s
            """.formatted(currentDate, currentDate, userInput);
    }

    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, FORMATTER);
        } catch (Exception e) {
            log.warn("时间解析失败: {}", dateTimeStr);
            return null;
        }
    }

    public record ParsedResult(
            String title,
            LocalDateTime remindTime,
            String intentType,
            String location,
            String priority
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String title;
            private LocalDateTime remindTime;
            private String intentType;
            private String location;
            private String priority;

            public Builder title(String title) {
                this.title = title;
                return this;
            }

            public Builder remindTime(LocalDateTime remindTime) {
                this.remindTime = remindTime;
                return this;
            }

            public Builder intentType(String intentType) {
                this.intentType = intentType;
                return this;
            }

            public Builder location(String location) {
                this.location = location;
                return this;
            }

            public Builder priority(String priority) {
                this.priority = priority;
                return this;
            }

            public ParsedResult build() {
                return new ParsedResult(title, remindTime, intentType, location, priority);
            }
        }
    }
}
