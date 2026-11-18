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

// 日程批量导入控制器。统一前缀 /api/schedule，覆盖课表/考试安排等场景的批量日程识别流程：上传文件→AI 识别→（课表场景）配置时间→确认导入/跳过冲突/覆盖全部，最终批量生成提醒
@Slf4j
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleImportController {

    // 导入服务：封装文件解析、AI 识别课表结构、冲突判定、批量落库等完整流程
    private final ScheduleImportService scheduleImportService;

    // 上传入口：接收 Excel/PDF/图片等课表或日程文件，转存后触发 OCR+AI 识别，返回识别出的原始日程条目
    // file   - 表单上传的二进制文件
    // userId - 当前登录用户
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

    // 确认导入（通用入口）：对已识别的条目直接批量创建提醒，对与现有提醒冲突的条目按 direct 策略（保留两者或报冲突）处理
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

    // 取消导入：删除本次导入的临时识别记录，不产生任何提醒数据
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

    // 查询导入结果详情：返回识别到的日程条目、已创建/冲突/跳过数量等
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

    // 课表场景 - AI 识别后立即生成日程：采用系统默认的节次时间表直接生成，不进入配置节次时间的流程
    @PostMapping("/confirm-ai")
    public Map<String, Object> confirmAi(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("AI识别确认：importId={}, userId={}", importId, userId);
        // config 传 null → 内部按默认节次时间表（8:00-8:45 等）构造
        Map<String, Object> result = scheduleImportService.generateScheduleFromImport(userId, importId, null);
        result.put("importId", String.valueOf(importId));
        return result;
    }

    // 课表场景 - 获取已配置的节次时间表：进入配置时调用，回填已有配置方便用户修改
    @GetMapping("/get-time-config")
    public Map<String, Object> getTimeConfig(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("获取时间配置：importId={}, userId={}", importId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "请配置课程时间");
        result.put("importId", String.valueOf(importId));
        // 配置数据与导入详情复用同一个服务方法返回
        Map<String, Object> configData = scheduleImportService.getImportResult(importId, userId);
        result.put("data", configData);
        return result;
    }

    // 课表场景 - 更新缓存中的单条课程：用户编辑课程信息后同步到 scheduleTableCache
    @PostMapping("/update-course")
    public Map<String, Object> updateCourse(
            @RequestParam("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        Long importId = Long.valueOf(String.valueOf(body.get("importId")));
        int courseIndex = Integer.parseInt(String.valueOf(body.get("courseIndex")));
        @SuppressWarnings("unchecked")
        Map<String, Object> courseData = (Map<String, Object>) body.get("course");
        log.info("更新课程：userId={}, importId={}, index={}", userId, importId, courseIndex);
        return scheduleImportService.updateCourse(userId, importId, courseIndex, courseData);
    }

    // 课表场景 - 删除缓存中的单条课程：用户删除某门课后同步到 scheduleTableCache
    @PostMapping("/delete-course")
    public Map<String, Object> deleteCourse(
            @RequestParam("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        Long importId = Long.valueOf(String.valueOf(body.get("importId")));
        int courseIndex = Integer.parseInt(String.valueOf(body.get("courseIndex")));
        log.info("删除课程：userId={}, importId={}, index={}", userId, importId, courseIndex);
        return scheduleImportService.deleteCourse(userId, importId, courseIndex);
    }

    // 课表场景 - 提交节次时间表配置：用户自定义每节课开始/结束时间后，按配置重新推算所有课程的真实提醒时间
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

    // 课表场景 - 纯手动输入课程：不依赖文件上传，用户手动录入课程名/星期/节次/周次/地点等批量生成
    @PostMapping("/manual")
    public Map<String, Object> manualCreate(
            @RequestBody Map<String, Object> body,
            @RequestParam("userId") Long userId) {
        log.info("手动创建课程：userId={}", userId);
        String importId = (String) body.get("importId");
        String semesterStartDate = (String) body.get("semesterStartDate");
        Object totalWeeksObj = body.get("totalWeeks");
        Integer totalWeeks = null;
        // 总周次兼容 Number / String / null 三类输入
        if (totalWeeksObj instanceof Number) {
            totalWeeks = ((Number) totalWeeksObj).intValue();
        } else if (totalWeeksObj instanceof String) {
            try {
                totalWeeks = Integer.parseInt((String) totalWeeksObj);
            } catch (NumberFormatException ignored) {
            }
        }

        // 组装时间表配置对象，linkMode=NONE 表示不关联任何 AI 识别结果
        CourseTimeConfigDTO config = new CourseTimeConfigDTO();
        config.setSemesterStartDate(semesterStartDate);
        config.setLinkMode("NONE");
        config.setImportId(importId);
        config.setTotalWeeks(totalWeeks);

        // 把 courses 数组逐行转为 ManualCourseDTO 对象
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

    // 课表场景 - 直接创建冲突条目：即使与已有提醒时间重叠也照常生成，交给用户自行判断
    @PostMapping("/confirm/direct")
    public Map<String, Object> confirmDirect(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("直接创建：importId={}, userId={}", importId, userId);
        return scheduleImportService.batchCreateReminders(importId, userId, "direct");
    }

    // 课表场景 - 跳过冲突条目：只生成不冲突的日程，与现有提醒重叠的条目被丢弃并计入跳过数
    @PostMapping("/confirm/skip")
    public Map<String, Object> confirmSkip(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("跳过冲突创建：importId={}, userId={}", importId, userId);
        return scheduleImportService.batchCreateReminders(importId, userId, "skip");
    }

    // 课表场景 - 全部覆盖：先删除时间冲突的既有提醒，再把本次导入的所有条目生成进来
    @PostMapping("/confirm/overwrite-all")
    public Map<String, Object> confirmOverwriteAll(
            @RequestParam("importId") Long importId,
            @RequestParam("userId") Long userId) {
        log.info("全部覆盖创建：importId={}, userId={}", importId, userId);
        return scheduleImportService.batchCreateReminders(importId, userId, "overwrite-all");
    }
}
