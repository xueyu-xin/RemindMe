package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.ScheduleItemDTO;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 图片日程识别服务。复用 QwenRecognitionService 的通义千问 VL 多模态调用，
// 将图片中识别出的事务转换为多项确认卡片所需的数据结构（title + 完整 remindTime）。
// 注：LLMService 仅支持文本对话、无视觉能力，故此处依赖 QwenRecognitionService 提供 qwen-vl-plus 调用。
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageRecognitionService {

    private final QwenRecognitionService qwenRecognitionService;

    // 识别图片中的事务信息，返回多项事务数据列表
    // base64Image 图片的 Base64 data URI；返回 List<Map>，每项含 title/remindTime/end/location 等
    public List<Map<String, Object>> recognizeSchedule(String base64Image) {
        if (base64Image == null || base64Image.isEmpty()) {
            log.warn("图片 Base64 为空");
            return new ArrayList<>();
        }

        List<ScheduleItemDTO> recognizedItems;
        try {
            recognizedItems = qwenRecognitionService.recognizeImage(base64Image);
        } catch (Exception e) {
            log.error("图片识别失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片识别失败：" + e.getMessage(), e);
        }

        if (recognizedItems == null || recognizedItems.isEmpty()) {
            log.info("图片未识别到任何事务");
            return new ArrayList<>();
        }

        // 缺失日期时默认补今天，保证 remindTime 字段完整
        String today = TimeUtils.now().toLocalDate().format(TimeUtils.DATE_FORMATTER);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScheduleItemDTO dto : recognizedItems) {
            String title = dto.getTitle();
            String date = dto.getDate() != null && !dto.getDate().isBlank() ? dto.getDate() : today;
            String time = dto.getTime();

            // 验证：标题与时间至少需存在，否则跳过该条
            if (title == null || title.isBlank() || time == null || time.isBlank()) {
                log.warn("跳过不完整的事务: title={}, time={}", title, time);
                continue;
            }

            // 组装完整 remindTime（yyyy-MM-dd HH:mm:ss）与 endTime
            String remindTime = date + " " + time + ":00";
            String endTime = null;
            if (dto.getEndTime() != null && !dto.getEndTime().isBlank()) {
                endTime = date + " " + dto.getEndTime() + ":00";
            }

            Map<String, Object> item = new HashMap<>();
            item.put("title", title);
            item.put("remindTime", remindTime);
            item.put("endTime", endTime);
            item.put("location", dto.getLocation());
            item.put("note", null);
            item.put("priority", Priority.NORMAL.name());
            result.add(item);
        }

        log.info("图片识别完成: 识别 {} 项，有效 {} 项", recognizedItems.size(), result.size());
        return result;
    }
}
