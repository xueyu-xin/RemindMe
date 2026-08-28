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
import com.alibaba.dashscope.exception.ApiException;
import com.dormrepair.remindme.config.QwenConfig;
import com.dormrepair.remindme.dto.ScheduleItemDTO;
import com.dormrepair.remindme.dto.ScheduleTableDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// 通义千问识别服务
// 调用阿里通义千问多模态模型，把图片/文本识别为结构化日程列表
@Slf4j
@Service
@RequiredArgsConstructor
public class QwenRecognitionService {

    private final QwenConfig qwenConfig;
    private final ObjectMapper objectMapper;

    // Bean 初始化时把 API Key 写入系统变量，供 DashScope SDK 读取
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

    // 文本日程识别 Prompt：要求模型只输出 JSON 数组，覆盖标题/日期/时间/地点/重复类型
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

    // 图片日程识别 Prompt：与文本版字段一致，强调仔细识别图中所有文字
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

    // 从文本内容识别日程
    // content 文本内容；fileType 文件类型（excel/pdf/image）；返回识别到的日程列表
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

            GenerationResult result = callWithRetry(() -> gen.call(param), "通义千问识别");
            String responseText = extractText(result);

            log.info("通义千问识别完成：fileType={}, responseLength={}", fileType, responseText.length());

            return parseScheduleItems(responseText);
        } catch (Exception e) {
            log.error("通义千问识别失败：fileType={}", fileType, e);
            throw new RuntimeException("通义千问识别失败：" + e.getMessage(), e);
        }
    }

    // 从图片 Base64 识别日程
    // base64Image 图片的 Base64 data URI；返回识别到的日程列表
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

            String tempFilePath = saveBase64ToTempFile(base64Image);
            log.info("通义千问图片识别使用临时文件：{}", tempFilePath);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-vl-plus")
                    .message(MultiModalConversationMessage.builder()
                            .role("user")
                            .content(java.util.Arrays.asList(
                                    new MultiModalMessageItemImage(tempFilePath),
                                    new MultiModalMessageItemText(IMAGE_PROMPT)
                            ))
                            .build())
                    .build();

            MultiModalConversationResult result = callWithRetry(() -> conv.call(param), "通义千问图片识别");
            String responseText = extractMultiModalText(result);

            log.info("通义千问图片识别完成：responseLength={}", responseText.length());

            return parseScheduleItems(responseText);
        } catch (Exception e) {
            log.error("通义千问图片识别失败", e);
            throw new RuntimeException("通义千问图片识别失败：" + e.getMessage(), e);
        }
    }

    // 解析 AI 返回的 JSON 为 ScheduleItemDTO 列表
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

    // 从 AI 返回中提取 JSON 数组（截取第一个 [ 到最后一个 ]）
    private String extractJsonArray(String text) {
        if (text == null) return null;

        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    // 从 GenerationResult 提取文本
    // 兼容 choices.message.content、output.text 以及 JSON 兜底三种来源
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

    // 从 MultiModalConversationResult 提取文本
    // 多模态返回的 content 是 List<?>，其中每个元素为 Map，键 "text" 对应 JSON 文本片段
    // 使用 instanceof 安全类型判断，避免 SDK 内部类型变化导致 ClassCastException
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
                    Object contentObj = message.getContent();
                    StringBuilder sb = new StringBuilder();
                    if (contentObj instanceof List) {
                        List<?> contentList = (List<?>) contentObj;
                        for (Object item : contentList) {
                            if (item instanceof Map) {
                                Map<?, ?> map = (Map<?, ?>) item;
                                Object textObj = map.get("text");
                                if (textObj != null) {
                                    sb.append(textObj.toString());
                                }
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

    // 课表识别 Prompt：解析用 | 分隔的表格结构，输出带节次/周次/单双周/时间表的完整课程 JSON
    private static final String SCHEDULE_TABLE_PROMPT = """
            注意：如果输入内容包含"周一"、"周二"、"第1节"、"08:00-08:45"等课表特征，直接识别为课表格式。
            如果内容本身就是课表结构，直接输出课程信息，不要推断日期。

            输入内容是用 "|" 分隔的表格结构，每行是一行课程，每列用 "|" 分隔。
            第一行通常是表头（如：时间/节次|周一|周二|周三|周四|周五），后续每行是具体节次和对应课程。

            你是一个专业的课程表识别助手。请从以下课表内容中精确提取所有课程信息，返回严格的 JSON 格式。

            识别规则：
            1. 识别星期几（星期一~星期日，周一~周日均需支持）
            2. 识别节次（第一二节 → 第1、2节，第三四节 → 第3、4节，依此类推）
            3. 提取课程名称、教师、上课地点
            4. 提取周次范围（如 1-17 周、1,3,5,7 周）
            5. 识别单双周标记（单周/双周/全部）
            6. 推断时间表（每节课的开始时间和结束时间），如果原文未提供，按常规大学作息时间（第1节 08:00-08:45，第2节 08:55-09:40，以此类推）
            7. 推断学期开始日期

            合并规则（重要）：
            - 同一门课程如果出现在连续的多个节次（如第1节和第2节都是"高等数学"），合并为一条课程，sectionIndices 为 [1, 2]
            - 同一门课程如果出现在同一列（同一天）的多个非连续节次，也合并为一条
            - 空单元格表示该时段无课，跳过
            - 每条课程应该唯一，不要为同一门课生成多条重复记录

            返回格式要求（严格按此 JSON 结构返回，不要添加任何额外文字）：
            {
              "success": true/false,
              "confidence": 0.0-1.0,
              "courses": [
                {
                  "title": "课程名",
                  "teacher": "教师名",
                  "dayOfWeek": "星期一",
                  "sectionIndices": [1, 2],
                  "weeks": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
                  "oddWeek": false,
                  "evenWeek": false,
                  "location": "教室名"
                }
              ],
              "sectionMapping": {
                "第一二节": [1, 2],
                "第三四节": [3, 4]
              },
              "timeSections": [
                {"index": 1, "startTime": "08:00", "endTime": "08:45"},
                {"index": 2, "startTime": "08:55", "endTime": "09:40"}
              ],
              "semesterStartDate": "2026-09-01",
              "suggestedWeeks": 16
            }

            如果无法识别课表，返回 {"success": false, "confidence": 0.0, "courses": [], "sectionMapping": {}, "timeSections": [], "semesterStartDate": null, "suggestedWeeks": 16}

            课表内容：
            %s""";

    // 识别课表结构化数据
    // content 课表文本内容；fileType 文件类型；返回识别结果（含课程列表、节次映射、时间表、学期起始日）
    public ScheduleTableDTO recognizeScheduleTable(String content, String fileType) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("课表识别内容为空");
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .sectionMapping(Collections.emptyMap())
                    .timeSections(Collections.emptyList())
                    .semesterStartDate(null)
                    .build();
        }

        String apiKey = qwenConfig.getKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("通义千问 API Key 未配置");
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .build();
        }

        try {
            Generation gen = new Generation();

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo")
                    .prompt(String.format(SCHEDULE_TABLE_PROMPT, content))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            GenerationResult result = callWithRetry(() -> gen.call(param), "课表识别");
            String responseText = extractText(result);

            log.info("课表识别完成：fileType={}, responseLength={}", fileType, responseText.length());

            return parseScheduleTableResult(responseText);
        } catch (Exception e) {
            log.error("课表识别失败：fileType={}", fileType, e);
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .build();
        }
    }

    // 解析课表识别结果
    // 提取 JSON 对象后反序列化为 DTO，并做置信度门槛与无效课程过滤
    private ScheduleTableDTO parseScheduleTableResult(String responseText) {
        log.info("AI 返回的原始 JSON：{}", responseText);
        try {
            String json = extractJsonObject(responseText);
            if (json == null || json.isEmpty()) {
                log.warn("课表识别结果中未找到 JSON 对象");
                return ScheduleTableDTO.builder()
                        .success(false)
                        .confidence(0.0)
                        .courses(Collections.emptyList())
                        .build();
            }

            ScheduleTableDTO dto = objectMapper.readValue(json, ScheduleTableDTO.class);

            // 检查置信度
            if (dto.getConfidence() == null || dto.getConfidence() < 0.5) {
                log.warn("课表识别置信度过低：{}", dto.getConfidence());
                dto.setSuccess(false);
            }

            // 过滤无效课程
            if (dto.getCourses() != null) {
                dto.setCourses(dto.getCourses().stream()
                        .filter(c -> c.getTitle() != null && !c.getTitle().isEmpty())
                        .collect(java.util.stream.Collectors.toList()));
            }

            log.info("课表识别成功：courses={}, confidence={}",
                    dto.getCourses() != null ? dto.getCourses().size() : 0,
                    dto.getConfidence());
            return dto;
        } catch (Exception e) {
            log.error("课表识别 JSON 解析失败：responseText={}", responseText, e);
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .build();
        }
    }

    // 从 AI 返回中提取 JSON 对象（截取第一个 { 到最后一个 }）
    private String extractJsonObject(String text) {
        if (text == null) return null;

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    // 从图片识别课表：用 qwen-vl-plus 多模态模型 + 课表专用 PROMPT
    // base64Image 图片的 Base64 data URI；返回课表结构化数据
    public ScheduleTableDTO recognizeScheduleImage(String base64Image) {
        if (base64Image == null || base64Image.isEmpty()) {
            log.warn("图片 Base64 为空");
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .build();
        }

        String apiKey = qwenConfig.getKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("通义千问 API Key 未配置");
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .build();
        }

        try {
            MultiModalConversation conv = new MultiModalConversation();

            String schedulePrompt = """
                    你是一个专业的课程表识别助手。请仔细识别图片中的课程表，返回严格的 JSON 格式。

                    识别规则：
                    1. 识别星期几（星期一~星期日，周一~周日均需支持）
                    2. 识别节次（第1节、第2节...第10节）
                    3. 提取课程名称、教师、上课地点
                    4. 推断时间表（每节课的开始时间和结束时间），如果图片中有时间则使用，没有则按常规大学作息时间（第1节 08:00-08:45，第2节 08:55-09:40，以此类推）
                    5. 推断学期开始日期

                    合并规则（重要）：
                    - 同一门课程如果出现在连续的多个节次，合并为一条课程，sectionIndices 为 [1, 2]
                    - 同一门课程如果出现在同一列（同一天）的多个非连续节次，也合并为一条
                    - 空单元格表示该时段无课，跳过
                    - 每条课程应该唯一，不要为同一门课生成多条重复记录

                    返回格式要求（严格按此 JSON 结构返回，不要添加任何额外文字）：
                    {
                      "success": true/false,
                      "confidence": 0.0-1.0,
                      "courses": [
                        {
                          "title": "课程名",
                          "teacher": "教师名",
                          "dayOfWeek": "星期一",
                          "sectionIndices": [1, 2],
                          "weeks": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
                          "oddWeek": false,
                          "evenWeek": false,
                          "location": "教室名"
                        }
                      ],
                      "timeSections": [
                        {"index": 1, "startTime": "08:00", "endTime": "08:45"},
                        {"index": 2, "startTime": "08:55", "endTime": "09:40"}
                      ],
                      "semesterStartDate": "2026-09-01",
                      "suggestedWeeks": 16
                    }

                    如果无法识别课表，返回 {"success": false, "confidence": 0.0, "courses": [], "timeSections": [], "semesterStartDate": null, "suggestedWeeks": 16}
                    """;

            String tempFilePath = saveBase64ToTempFile(base64Image);
            log.info("图片课表识别使用临时文件：{}", tempFilePath);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-vl-plus")
                    .message(MultiModalConversationMessage.builder()
                            .role("user")
                            .content(java.util.Arrays.asList(
                                    new MultiModalMessageItemImage(tempFilePath),
                                    new MultiModalMessageItemText(schedulePrompt)
                            ))
                            .build())
                    .build();

            MultiModalConversationResult result = callWithRetry(() -> conv.call(param), "图片课表识别");
            String responseText = extractMultiModalText(result);

            log.info("图片课表识别完成：responseLength={}", responseText.length());
            log.info("AI 返回的原始 JSON：{}", responseText);

            return parseScheduleTableResult(responseText);
        } catch (Exception e) {
            log.error("图片课表识别失败", e);
            return ScheduleTableDTO.builder()
                    .success(false)
                    .confidence(0.0)
                    .courses(Collections.emptyList())
                    .build();
        }
    }

    // ==================== API 调用重试 ====================

    // 可抛受检异常的 Supplier，用于包装 DashScope SDK 的 call()（其声明 throws 受检异常）
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    // 通义千问 API 调用重试包装器：仅对网络异常重试，4xx/JSON 解析错误不重试，
    // 指数退避 100ms→200ms→400ms，重试耗尽抛 RuntimeException（由调用方外层 catch 兜底，保持现有行为）。
    private <T> T callWithRetry(ThrowingSupplier<T> action, String operation) throws Exception {
        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryableNetworkException(e)) {
                    throw e;
                }
                lastException = e;
                log.warn("{} 调用失败，第 {} 次重试: {}", operation, attempt, e.getMessage());
                if (attempt >= maxRetries) {
                    break;
                }
                long waitMs = (long) (Math.pow(2, attempt - 1) * 100);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException(operation + " 调用失败，已重试 " + maxRetries + " 次", lastException);
    }

    // 判断异常是否可重试：DashScope ApiException（无 Status 为网络错误/有 Status 5xx 重试、4xx 不重试）、
    // SocketTimeoutException、非 JsonProcessingException 的 IOException 视为可重试网络异常
    private static boolean isRetryableNetworkException(Exception e) {
        if (e instanceof ApiException) {
            ApiException apiEx = (ApiException) e;
            if (apiEx.getStatus() != null) {
                int statusCode = apiEx.getStatus().getStatusCode();
                // 4xx 客户端错误不重试
                return statusCode < 400 || statusCode >= 500;
            }
            return true;  // 无 Status，纯网络错误，重试
        }
        if (e instanceof SocketTimeoutException) {
            return true;
        }
        // IO 异常（网络层），排除 JSON 解析错误
        return e instanceof IOException && !(e instanceof JsonProcessingException);
    }

    // 构造 DashScope MultiModalMessageItemImage 所需的 data URI：若入参已是 data:image/* 形式则原样返回，
    // 否则按 png 兜底拼接前缀。用于兼容纯 Base64 与 data URI 两种入参形态
    private static String buildDataUri(String base64Image) {
        if (base64Image == null) {
            return null;
        }
        if (base64Image.startsWith("data:image/")) {
            return base64Image;
        }
        return "data:image/png;base64," + base64Image;
    }

    // 将纯 Base64 图片数据保存为临时文件，并返回 file:/// 形式的 URL 字符串
    // 供 DashScope MultiModalMessageItemImage 通过文件 URL 方式读取图片（SDK 最稳定的调用方式）
    // 入参 pureBase64 必须为不含 data: 前缀的纯 Base64 字符串；若误传了 data URI 会自动剥离前缀
    private static String saveBase64ToTempFile(String pureBase64) {
        if (pureBase64 == null || pureBase64.isEmpty()) {
            throw new RuntimeException("Base64 图片数据为空，无法保存为临时文件");
        }

        // 兼容误传 data URI 的情况：剥离 data:image/...;base64, 前缀
        String rawBase64 = pureBase64;
        int commaIdx = pureBase64.indexOf(",");
        if (pureBase64.startsWith("data:") && commaIdx > 0) {
            rawBase64 = pureBase64.substring(commaIdx + 1);
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(rawBase64);
            Path tempFile = Files.createTempFile("qwen_img_", ".png");
            Files.write(tempFile, bytes);

            // 构造 file:/// 形式的 URL 字符串（Windows 绝对路径如 C:\... 需转成 /C:/... 形式）
            String path = tempFile.toAbsolutePath().toString().replace('\\', '/');
            String fileUrl = "file:///" + (path.startsWith("/") ? path.substring(1) : path);

            log.info("Base64 图片已保存为临时文件：{}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("保存 Base64 图片到临时文件失败", e);
            throw new RuntimeException("保存 Base64 图片到临时文件失败：" + e.getMessage(), e);
        }
    }
}
