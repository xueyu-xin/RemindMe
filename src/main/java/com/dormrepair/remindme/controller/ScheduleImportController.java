package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.service.ScheduleImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
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

        return scheduleImportService.batchCreateReminders(importId, userId);
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
}
