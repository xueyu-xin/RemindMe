package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.CourseTimeConfigDTO;
import com.dormrepair.remindme.dto.ManualCourseDTO;
import com.dormrepair.remindme.dto.ScheduleItemDTO;
import com.dormrepair.remindme.service.ScheduleImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日程导入控制器
 * <p>
 * 处理文件上传、确认导入、取消导入、查询结果
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleImportController {

    private final ScheduleImportService scheduleImportService;

    /**
     * 上传文件，触发识别
     *
     * @param file   上传的文件（Excel/PDF/图片）
     * @param userId 用户ID
     * @return 识别结果
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") Long userId) {

        if (file == null || file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件不能为空");
            return error;
        }
        if (userId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "用户ID不能为空");
            return error;
        }

        log.info("接收到文件上传请求：userId={}, fileName={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());

        return scheduleImportService.processUpload(file, userId);
    }

    /**
     * 确认导入，批量创建提醒
     *
     * @param importId 导入ID
     * @param userId   用户ID
     * @return 创建结果
     */
    @PostMapping("/confirm/{importId}")
    public Map<String, Object> confirmImport(
            @PathVariable Long importId,
            @RequestParam("userId") Long userId) {

        if (userId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "用户ID不能为空");
            return error;
        }

        log.info("接收到确认导入请求：importId={}, userId={}", importId, userId);

        return scheduleImportService.batchCreateReminders(importId, userId, "direct");
    }

    /**
     * 取消导入
     *
     * @param importId 导入ID
     * @param userId   用户ID
     * @return 取消结果
     */
    @PostMapping("/cancel/{importId}")
    public Map<String, Object> cancelImport(
            @PathVariable Long importId,
            @RequestParam("userId") Long userId) {

        if (userId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "用户ID不能为空");
            return error;
        }

        log.info("接收到取消导入请求：importId={}, userId={}", importId, userId);

        return scheduleImportService.cancelImport(importId, userId);
    }

    /**
     * 查询导入结果
     *
     * @param importId 导入ID
     * @param userId   用户ID
     * @return 导入结果详情
     */
    @GetMapping("/result/{importId}")
    public Map<String, Object> getImportResult(
            @PathVariable Long importId,
            @RequestParam("userId") Long userId) {

        if (userId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "用户ID不能为空");
            return error;
        }

        return scheduleImportService.getImportResult(importId, userId);
    }

    /**
     * AI 识别确认（课表场景）
     */
    @PostMapping("/confirm-ai")
    public Map<String, Object> confirmAi(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("AI识别确认：importId={}, userId={}", importId, userId);
        // 使用 AI 识别结果 + 默认时间表生成具体日程
        Map<String, Object> result = scheduleImportService.generateScheduleFromImport(userId, importId, null);
        result.put("importId", String.valueOf(importId));
        return result;
    }

    /**
     * 获取时间配置（课表场景）
     */
    @GetMapping("/get-time-config")
    public Map<String, Object> getTimeConfig(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("获取时间配置：importId={}, userId={}", importId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "请配置课程时间");
        result.put("importId", String.valueOf(importId));
        // 从导入记录中获取时间配置
        Map<String, Object> configData = scheduleImportService.getImportResult(importId, userId);
        result.put("data", configData);
        return result;
    }

    /**
     * 提交时间配置（课表场景）
     */
    @PostMapping("/config-time")
    public Map<String, Object> configTime(
            @RequestBody CourseTimeConfigDTO config,
            @RequestParam("userId") Long userId) {
        log.info("提交时间配置：userId={}, importId={}", userId, config.getImportId());
        Long importId = Long.parseLong(config.getImportId());
        Map<String, Object> result = scheduleImportService.generateScheduleFromImport(userId, importId, config);
        result.put("importId", config.getImportId());
        return result;
    }

    /**
     * 手动创建课程
     */
    @PostMapping("/manual")
    public Map<String, Object> manualCreate(
            @RequestBody Map<String, Object> body,
            @RequestParam("userId") Long userId) {
        log.info("手动创建课程：userId={}", userId);
        String importId = (String) body.get("importId");
        String semesterStartDate = (String) body.get("semesterStartDate");
        Object totalWeeksObj = body.get("totalWeeks");
        Integer totalWeeks = null;
        if (totalWeeksObj instanceof Number) {
            totalWeeks = ((Number) totalWeeksObj).intValue();
        } else if (totalWeeksObj instanceof String) {
            try {
                totalWeeks = Integer.parseInt((String) totalWeeksObj);
            } catch (NumberFormatException ignored) {
            }
        }

        CourseTimeConfigDTO config = new CourseTimeConfigDTO();
        config.setSemesterStartDate(semesterStartDate);
        config.setLinkMode("NONE");
        config.setImportId(importId);
        config.setTotalWeeks(totalWeeks);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courseMaps = (List<Map<String, Object>>) body.get("courses");
        List<ManualCourseDTO> courses = courseMaps.stream().map(m -> {
            ManualCourseDTO dto = new ManualCourseDTO();
            dto.setCourseName((String) m.get("courseName"));
            dto.setDayOfWeek((String) m.get("dayOfWeek"));
            dto.setSectionRange((String) m.get("sectionRange"));
            dto.setWeekRange((String) m.get("weekRange"));
            dto.setOddEvenWeek((String) m.get("oddEvenWeek"));
            dto.setLocation((String) m.get("location"));
            dto.setTeacher((String) m.get("teacher"));
            return dto;
        }).toList();

        Map<String, Object> result = scheduleImportService.generateScheduleFromManual(userId, courses, config);
        result.put("importId", importId);
        return result;
    }

    /**
     * 直接创建（课表场景）
     */
    @PostMapping("/confirm/direct")
    public Map<String, Object> confirmDirect(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("直接创建：importId={}, userId={}", importId, userId);
        return scheduleImportService.batchCreateReminders(importId, userId, "direct");
    }

    /**
     * 跳过冲突创建（课表场景）
     */
    @PostMapping("/confirm/skip")
    public Map<String, Object> confirmSkip(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("跳过冲突创建：importId={}, userId={}", importId, userId);
        return scheduleImportService.batchCreateReminders(importId, userId, "skip");
    }

    /**
     * 全部覆盖创建（课表场景）
     */
    @PostMapping("/confirm/overwrite-all")
    public Map<String, Object> confirmOverwriteAll(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("全部覆盖创建：importId={}, userId={}", importId, userId);
        return scheduleImportService.batchCreateReminders(importId, userId, "overwrite-all");
    }
}
