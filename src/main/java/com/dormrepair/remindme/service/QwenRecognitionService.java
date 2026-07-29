package com.dormrepair.remindme.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.alibaba.dashscope.common.Message;
import com.dormrepair.remindme.config.QwenConfig;
import com.dormrepair.remindme.dto.ScheduleItemDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通义千问识别服务
 * <p>
 * 调用通义千问 API，将文本或图片内容识别为结构化日程列表
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QwenRecognitionService {

    private final QwenConfig qwenConfig;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        String key = qwenConfig.getKey();
        if (key != null && !key.isEmpty()) {
            System.setProperty("dashscope.api.key", key);
            log.info("✅ DashScope API Key 已加载：{}...{}", key.substring(0, Math.min(8, key.length())), 
                    key.length() > 8 ? key.substring(key.length() - 4) : "");
        } else {
            log.warn("⚠️ DashScope API Key 未配置，请设置环境变量 DASHSCOPE_API_KEY");
        }
    }

    private static final String RECOGNIZE_PROMPT = """
            你是一个课程表/日程识别助手。请从以下内容中提取所有日程信息，返回 JSON 数组。

            每个日程包含：
            - title: 事项名称
            - date: 日期，格式 yyyy-MM-dd（如果只有时间没有日期，使用当前日期或推断的日期）
            - time: 时间，格式 HH:mm
            - location: 地点（如果没有则为 null）
            - repeatType: 重复类型，ONCE（单次）/ DAILY（每天）/ WEEKLY（每周）

            识别规则：
            1. 如果内容是表格，按表格结构解析
            2. 如果是课表，识别课程名称、上课时间、教室
            3. 如果识别到"每天"、"每周"等关键词，设置对应的 repeatType
            4. 如果日期不完整（如"周一"），根据上下文推断
            5. 只返回 JSON 数组，不要其他内容
            6. 如果无法识别任何日程，返回空数组 []

            内容：
            %s""";

    private static final String IMAGE_PROMPT = """
            你是一个课程表/日程识别助手。请识别图片中的所有日程信息，返回 JSON 数组。

            每个日程包含：
            - title: 事项名称
            - date: 日期，格式 yyyy-MM-dd
            - time: 时间，格式 HH:mm
            - location: 地点（如果没有则为 null）
            - repeatType: 重复类型，ONCE（单次）/ DAILY（每天）/ WEEKLY（每周）

            识别规则：
            1. 仔细识别图片中所有文字内容
            2. 如果是课表，识别课程名称、上课时间、教室
            3. 如果识别到"每天"、"每周"等关键词，设置对应的 repeatType
            4. 只返回 JSON 数组，不要其他内容
            5. 如果无法识别任何日程，返回空数组 []
            """;

    /**
     * 从文本内容识别日程
     *
     * @param content  文本内容
     * @param fileType 文件类型（excel/pdf/image）
     * @return 识别到的日程列表
     */
    public List<ScheduleItemDTO> recognize(String content, String fileType) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("识别内容为空");
            return Collections.emptyList();
        }

        String apiKey = qwenConfig.getKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("通义千问 API Key 未配置");
            throw new RuntimeException("通义千问 API Key 未配置，请在 application.properties 中设置 dashscope.api.key");
        }

        try {
            Generation gen = new Generation();

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo")
                    .prompt(String.format(RECOGNIZE_PROMPT, content))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            GenerationResult result = gen.call(param);
            String responseText = extractText(result);

            log.info("通义千问识别完成：fileType={}, responseLength={}", fileType, responseText.length());

            return parseScheduleItems(responseText);
        } catch (Exception e) {
            log.error("通义千问识别失败：fileType={}", fileType, e);
            throw new RuntimeException("通义千问识别失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从图片 Base64 识别日程
     *
     * @param base64Image 图片的 Base64 data URI
     * @return 识别到的日程列表
     */
    public List<ScheduleItemDTO> recognizeImage(String base64Image) {
        if (base64Image == null || base64Image.isEmpty()) {
            log.warn("图片 Base64 为空");
            return Collections.emptyList();
        }

        String apiKey = qwenConfig.getKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("通义千问 API Key 未配置");
            throw new RuntimeException("通义千问 API Key 未配置");
        }

        try {
            MultiModalConversation conv = new MultiModalConversation();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-vl-plus")
                    .message(MultiModalConversationMessage.builder()
                            .role("user")
                            .content(java.util.Arrays.asList(
                                    new MultiModalMessageItemImage(base64Image),
                                    new MultiModalMessageItemText(IMAGE_PROMPT)
                            ))
                            .build())
                    .build();

            MultiModalConversationResult result = conv.call(param);
            String responseText = extractMultiModalText(result);

            log.info("通义千问图片识别完成：responseLength={}", responseText.length());

            return parseScheduleItems(responseText);
        } catch (Exception e) {
            log.error("通义千问图片识别失败", e);
            throw new RuntimeException("通义千问图片识别失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 AI 返回的 JSON 为 ScheduleItemDTO 列表
     */
    private List<ScheduleItemDTO> parseScheduleItems(String responseText) {
        try {
            String json = extractJsonArray(responseText);
            if (json == null || json.isEmpty()) {
                log.warn("AI 返回内容中未找到 JSON 数组");
                return Collections.emptyList();
            }

            List<ScheduleItemDTO> items = objectMapper.readValue(json,
                    new TypeReference<List<ScheduleItemDTO>>() {});

            // 过滤无效项
            List<ScheduleItemDTO> validItems = new ArrayList<>();
            for (ScheduleItemDTO item : items) {
                if (item.getTitle() != null && !item.getTitle().isEmpty()) {
                    if (item.getRepeatType() == null) {
                        item.setRepeatType("ONCE");
                    }
                    validItems.add(item);
                }
            }

            log.info("日程识别成功：共 {} 条，有效 {} 条", items.size(), validItems.size());
            return validItems;
        } catch (Exception e) {
            log.error("日程 JSON 解析失败：responseText={}", responseText, e);
            return Collections.emptyList();
        }
    }

    /**
     * 从 AI 返回中提取 JSON 数组
     */
    private String extractJsonArray(String text) {
        if (text == null) return null;

        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    /**
     * 从 GenerationResult 提取文本
     */
    private String extractText(GenerationResult result) {
        if (result == null || result.getOutput() == null) {
            log.warn("GenerationResult output 为空");
            return "";
        }

        GenerationOutput output = result.getOutput();

        // 优先通过 choices 获取 message content
        if (output.getChoices() != null && !output.getChoices().isEmpty()) {
            try {
                Message message = output.getChoices().get(0).getMessage();
                if (message != null && message.getContent() != null) {
                    log.debug("通过 choices.message.content 提取文本成功");
                    return message.getContent();
                }
            } catch (Exception e) {
                log.warn("通过 choices 提取文本失败", e);
            }
        }

        // 备选：直接获取 text
        if (output.getText() != null && !output.getText().isEmpty()) {
            log.debug("通过 output.text 提取文本成功");
            return output.getText();
        }

        // 最后备选：尝试 JSON 解析
        try {
            JsonNode node = objectMapper.readTree(output.toString());
            if (node.has("choices") && node.get("choices").isArray()) {
                JsonNode message = node.get("choices").get(0).get("message");
                if (message != null && message.has("content")) {
                    log.debug("通过 JSON 解析提取文本成功");
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.warn("JSON 解析 GenerationOutput 失败", e);
        }

        log.warn("未能从 GenerationResult 中提取到文本");
        return "";
    }

    /**
     * 从 MultiModalConversationResult 提取文本
     */
    private String extractMultiModalText(MultiModalConversationResult result) {
        if (result == null || result.getOutput() == null) {
            log.warn("MultiModalConversationResult output 为空");
            return "";
        }

        MultiModalConversationOutput output = result.getOutput();

        // 通过 choices 获取 message content
        if (output.getChoices() != null && !output.getChoices().isEmpty()) {
            try {
                var message = output.getChoices().get(0).getMessage();
                if (message != null && message.getContent() != null) {
                    // MultiModalMessage.getContent() 返回 List<Map<String, Object>>
                    // 需要找到类型为 text 的内容
                    List<Map<String, Object>> contentList = message.getContent();
                    StringBuilder sb = new StringBuilder();
                    for (Map<String, Object> item : contentList) {
                        if (item != null && "text".equals(item.get("type"))) {
                            Object textVal = item.get("text");
                            if (textVal != null) {
                                sb.append(textVal.toString());
                            }
                        }
                    }
                    if (sb.length() > 0) {
                        log.debug("多模态：通过 choices.message.content 提取文本成功");
                        return sb.toString();
                    }
                }
            } catch (Exception e) {
                log.warn("多模态：通过 choices 提取文本失败", e);
            }
        }

        log.warn("未能从 MultiModalConversationResult 中提取到文本");
        return "";
    }
}
