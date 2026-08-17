package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.CourseTimeConfigDTO;
import com.dormrepair.remindme.dto.ManualCourseDTO;
import com.dormrepair.remindme.dto.ScheduleItemDTO;
import com.dormrepair.remindme.dto.ScheduleTableDTO;
import com.dormrepair.remindme.dto.TimeSlotDTO;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.ScheduleImport;
import com.dormrepair.remindme.entity.ScheduleImportItem;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.ScheduleImportItemRepository;
import com.dormrepair.remindme.repository.ScheduleImportRepository;
import com.dormrepair.remindme.repository.UserRepository;
import com.dormrepair.remindme.utils.FileParserUtil;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 课表导入服务：覆盖文件上传、解析、通义千问识别、导入记录创建、确认卡片推送及批量提醒生成的全流程
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleImportService {

    private final ScheduleImportRepository importRepository;
    private final ScheduleImportItemRepository importItemRepository;
    private final QwenRecognitionService qwenRecognitionService;
    private final WebSocketPushService webSocketPushService;
    private final ReminderService reminderService;
    private final ConflictDetectionService conflictDetectionService;
    private final UserRepository userRepository;
    private final ReminderRepository reminderRepository;

    // 课表识别结果缓存，importId 映射到识别出的 ScheduleTableDTO
    private final Map<Long, ScheduleTableDTO> scheduleTableCache = new ConcurrentHashMap<>();

    // 处理文件上传 → 解析 → 识别 → 创建导入记录 → 推送确认卡片
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> processUpload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        String fileName = file.getOriginalFilename();
        String fileType = getFileExtension(fileName);

        log.info("开始处理文件上传：userId={}, fileName={}, fileType={}", userId, fileName, fileType);

        // 1. 解析文件
        String content = null;
        boolean isImage = isImage(fileName);

        if (isImage) {
            String base64 = FileParserUtil.parseImage(file);
            // 图片直接走课表识别路径
            log.info("图片上传，使用课表识别路径：fileName={}", fileName);
            ScheduleTableDTO tableResult = qwenRecognitionService.recognizeScheduleImage(base64);
            if (tableResult.getSuccess() != null && tableResult.getSuccess() && tableResult.getCourses() != null && !tableResult.getCourses().isEmpty()) {
                return processScheduleTableFromDTO(tableResult, fileType, userId, fileName);
            }
            // 课表识别失败，回退到通用日程识别
            log.warn("图片课表识别失败，回退到通用识别：fileName={}", fileName);
            List<ScheduleItemDTO> recognizedItems = qwenRecognitionService.recognizeImage(base64);
            if (recognizedItems.isEmpty()) {
                log.warn("图片未识别到任何日程：fileName={}", fileName);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "未识别到任何日程信息，请检查文件内容");
                return result;
            }
            return buildImportResult(userId, fileName, fileType, recognizedItems);
        } else {
            content = FileParserUtil.parseFile(file);
        }

        // 2. Excel / PDF：检测是否为课表格式
        if (content != null && isScheduleTable(content)) {
            log.info("检测到课表格式：fileName={}", fileName);
            return processScheduleTable(content, fileType, userId, fileName);
        }

        // 3. 非课表走原有流程
        if (content != null) {
            List<ScheduleItemDTO> recognizedItems = qwenRecognitionService.recognize(content, fileType);
            if (recognizedItems.isEmpty()) {
                log.warn("未识别到任何日程：fileName={}", fileName);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "未识别到任何日程信息，请检查文件内容");
                result.put("items", Collections.emptyList());
                result.put("totalCount", 0);
                return result;
            }
            return buildImportResult(userId, fileName, fileType, recognizedItems);
        }

        // 兜底
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "文件解析失败");
        return result;
    }

    // 构建导入结果（通用日程识别结果）
    private Map<String, Object> buildImportResult(Long userId, String fileName, String fileType, List<ScheduleItemDTO> recognizedItems) {
        // 1. 创建导入记录
        ScheduleImport importRecord = createImportRecord(userId, fileName, fileType, recognizedItems);

        // 2. 创建明细记录
        List<ScheduleImportItem> itemEntities = new ArrayList<>();
        for (int i = 0; i < recognizedItems.size(); i++) {
            ScheduleItemDTO dto = recognizedItems.get(i);
            ScheduleImportItem item = new ScheduleImportItem();
            item.setImportId(importRecord.getId());
            item.setTitle(dto.getTitle());
            item.setRemindDate(parseDateSafely(dto.getDate()));
            item.setRemindTime(parseTimeSafely(dto.getTime()));
            item.setLocation(dto.getLocation());
            item.setRepeatType(dto.getRepeatType() != null ? dto.getRepeatType() : "ONCE");
            item.setStatus("PENDING");
            itemEntities.add(item);
        }
        importItemRepository.saveAll(itemEntities);

        // 3. 构建识别结果（供前端展示）
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (ScheduleItemDTO dto : recognizedItems) {
            Map<String, Object> map = new HashMap<>();
            map.put("title", dto.getTitle());
            map.put("date", dto.getDate());
            map.put("time", dto.getTime());
            map.put("location", dto.getLocation());
            map.put("repeatType", dto.getRepeatType());
            itemsList.add(map);
        }

        // 4. 推送确认卡片到前端
        pushConfirmCardToUser(userId, importRecord.getId(), importRecord.getFileName(), itemsList);

        // 5. 返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("importId", importRecord.getId());
        result.put("message", "识别完成，请确认");
        result.put("items", itemsList);
        result.put("totalCount", recognizedItems.size());
        result.put("hasConflict", false);

        log.info("文件上传处理完成：importId={}, totalCount={}", importRecord.getId(), recognizedItems.size());
        return result;
    }

    // 从图片/PDF 识别到的 ScheduleTableDTO 直接走课表后处理（复用 Excel 路径的全部后处理逻辑）
    private Map<String, Object> processScheduleTableFromDTO(ScheduleTableDTO tableResult, String fileType, Long userId, String fileName) {
        return handleScheduleTablePostProcess(tableResult, fileType, userId, fileName);
    }

    // 课表识别后的统一后处理：保存学期日期、创建导入记录、缓存结果、推送确认卡片
    private Map<String, Object> handleScheduleTablePostProcess(ScheduleTableDTO tableResult, String fileType, Long userId, String fileName) {
        if (tableResult.getSuccess() == null || !tableResult.getSuccess()) {
            log.warn("课表识别失败：confidence={}", tableResult.getConfidence());
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "课表识别失败，请手动创建课程或上传更清晰的文件");
            result.put("needManualCreate", true);
            return result;
        }

        log.info("课表识别成功：courses={}, confidence={}, semesterStartDate={}",
                tableResult.getCourses() != null ? tableResult.getCourses().size() : 0,
                tableResult.getConfidence(),
                tableResult.getSemesterStartDate());

        // 保存学期开始日期到用户
        if (tableResult.getSemesterStartDate() != null) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                user.setSemesterStartDate(LocalDate.parse(tableResult.getSemesterStartDate(), TimeUtils.DATE_FORMATTER));
                userRepository.save(user);
                log.info("保存学期开始日期：userId={}, semesterStartDate={}", userId, tableResult.getSemesterStartDate());
            }
        }

        // 创建导入记录获取 importId
        int courseCount = tableResult.getCourses() != null ? tableResult.getCourses().size() : 0;
        ScheduleImport importRecord = new ScheduleImport();
        importRecord.setUserId(userId);
        importRecord.setFileName(fileName);
        importRecord.setFileType(fileType);
        importRecord.setTotalCount(courseCount);
        importRecord.setSuccessCount(0);
        importRecord.setFailedCount(0);
        importRecord.setStatus("PENDING");
        importRecord.setTotalWeeks(tableResult.getSuggestedWeeks() != null ? tableResult.getSuggestedWeeks() : 16);
        if (tableResult.getSemesterStartDate() != null) {
            importRecord.setSemesterStartDate(LocalDate.parse(tableResult.getSemesterStartDate(), TimeUtils.DATE_FORMATTER));
        }
        importRecord = importRepository.save(importRecord);
        Long importId = importRecord.getId();
        log.info("课表导入记录已创建：importId={}, courseCount={}", importId, courseCount);

        // 缓存识别结果，供后续时间配置使用
        scheduleTableCache.put(importId, tableResult);

        // 推送课表确认卡片
        pushScheduleTableCard(userId, importId, tableResult, fileName);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "课表识别完成，请确认时间表配置");
        result.put("importId", String.valueOf(importId));
        result.put("totalCount", courseCount);
        result.put("courses", tableResult.getCourses());
        result.put("timeSections", tableResult.getTimeSections());
        result.put("semesterStartDate", tableResult.getSemesterStartDate());
        result.put("needTimeConfig", true);
        result.put("isScheduleTable", true);

        log.info("课表上传处理完成：importId={}, courseCount={}", importId, courseCount);
        return result;
    }

    // 处理课表文件：调用 AI 识别后统一走课表后处理
    private Map<String, Object> processScheduleTable(String content, String fileType, Long userId, String fileName) {
        // 调用 AI 课表识别
        ScheduleTableDTO tableResult = qwenRecognitionService.recognizeScheduleTable(content, fileType);
        return handleScheduleTablePostProcess(tableResult, fileType, userId, fileName);
    }

    // 检测文本是否为课表格式：通过关键词命中数判断
    private boolean isScheduleTable(String content) {
        if (content == null || content.trim().isEmpty()) return false;
        String lower = content.toLowerCase();
        // 扩展关键词，支持简写和繁体
        String[] keywords = {
                "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
                "周一", "周二", "周三", "周四", "周五", "周六", "周日",
                "第一二节", "第三四节", "第五六节", "第七八节", "第九十节",
                "第1节", "第2节", "第3节", "第4节", "第5节", "第6节", "第7节", "第8节", "第9节", "第10节",
                "第1-2节", "第3-4节", "第5-6节", "第7-8节", "第9-10节",
                "周次", "星期", "节次", "课程表", "课表",
                "教室", "教学楼", "上课", "课程", "时间/节次",
                "08:00", "08:50", "09:50", "10:40", "14:00", "14:50"
        };
        int matchCount = 0;
        for (String kw : keywords) {
            if (content.contains(kw)) {
                matchCount++;
                log.debug("课表关键词匹配：{}", kw);
            }
        }
        log.debug("课表关键词匹配数：{} / {}", matchCount, keywords.length);
        // 降低阈值，匹配 2 个以上即判定为课表
        return matchCount >= 2;
    }

    // 创建一条 PENDING 状态的导入记录，记录文件名、类型和总数
    @Transactional(rollbackFor = Exception.class)
    public ScheduleImport createImportRecord(Long userId, String fileName, String fileType,
                                             List<ScheduleItemDTO> items) {
        ScheduleImport importRecord = new ScheduleImport();
        importRecord.setUserId(userId);
        importRecord.setFileName(fileName);
        importRecord.setFileType(fileType);
        importRecord.setTotalCount(items.size());
        importRecord.setSuccessCount(0);
        importRecord.setFailedCount(0);
        importRecord.setStatus("PENDING");
        return importRepository.save(importRecord);
    }

    // 批量创建提醒，由用户在确认卡片上确认后触发，action 控制冲突处理策略
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchCreateReminders(Long importId, Long userId, String action) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        ScheduleImport importRecord = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalArgumentException("导入记录不存在：" + importId));

        if (!importRecord.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作此导入记录");
        }

        if ("CONFIRMED".equals(importRecord.getStatus()) || "CANCELLED".equals(importRecord.getStatus())) {
            throw new IllegalArgumentException("该导入记录已处理，无法再次确认");
        }

        // 优先使用缓存中的课表数据生成日程
        ScheduleTableDTO cached = scheduleTableCache.get(importId);
        if (cached != null && cached.getCourses() != null && !cached.getCourses().isEmpty()) {
            return createRemindersFromCache(importId, userId, action, importRecord, cached);
        }

        // 查询所有 PENDING 明细
        List<ScheduleImportItem> items = importItemRepository.findByImportIdAndStatus(importId, "PENDING");
        if (items.isEmpty()) {
            importRecord.setStatus("CONFIRMED");
            importRecord.setSuccessCount(0);
            importRecord.setFailedCount(0);
            importRepository.save(importRecord);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "没有待创建的日程");
            result.put("successCount", 0);
            result.put("failedCount", 0);
            return result;
        }

        return createRemindersFromItems(importId, userId, items, importRecord);
    }

    // 课表场景：从缓存的识别结果生成提醒，结合学期开始日期、时间表和周次逐课展开
    private Map<String, Object> createRemindersFromCache(Long importId, Long userId, String action,
                                                          ScheduleImport importRecord, ScheduleTableDTO tableResult) {
        User user = userRepository.findById(userId).orElseThrow();

        // 确定学期开始日期：importRecord 优先（用户在时间配置卡片中修改过）
        LocalDate semesterStart;
        if (importRecord.getSemesterStartDate() != null) {
            semesterStart = importRecord.getSemesterStartDate();
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        } else if (user.getSemesterStartDate() != null) {
            semesterStart = user.getSemesterStartDate();
        } else if (tableResult.getSemesterStartDate() != null) {
            semesterStart = LocalDate.parse(tableResult.getSemesterStartDate(), TimeUtils.DATE_FORMATTER);
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        } else {
            semesterStart = LocalDate.now();
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        }

        // 获取时间配置
        List<ScheduleTableDTO.TimeSection> timeSections;
        if (tableResult.getTimeSections() != null && !tableResult.getTimeSections().isEmpty()) {
            timeSections = tableResult.getTimeSections();
        } else {
            timeSections = getDefaultTimeSections();
        }

        // 获取学期总周数
        int totalWeeks = 16;
        if (importRecord.getTotalWeeks() != null && importRecord.getTotalWeeks() > 0) {
            totalWeeks = importRecord.getTotalWeeks();
        } else if (tableResult.getSuggestedWeeks() != null && tableResult.getSuggestedWeeks() > 0) {
            totalWeeks = tableResult.getSuggestedWeeks();
        }

        int successCount = 0;
        int failedCount = 0;

        for (ScheduleTableDTO.CourseItem course : tableResult.getCourses()) {
            try {
                List<CourseTimeSlot> slots = calculateCourseTimesFromCourseItem(
                        semesterStart, course, timeSections, totalWeeks);

                for (CourseTimeSlot slot : slots) {
                    LocalDateTime courseTime = slot.start();
                    LocalDateTime endTime = slot.end();

                    // 处理冲突
                    if ("overwrite-all".equals(action)) {
                        ConflictResponse conflict = conflictDetectionService.checkConflict(
                                userId, courseTime, endTime, null);
                        if (conflict.isHasConflict() && conflict.getConflicts() != null) {
                            for (ConflictResponse.ConflictItem ci : conflict.getConflicts()) {
                                Reminder conflictReminder = reminderRepository.findById(ci.getId()).orElse(null);
                                if (conflictReminder != null) {
                                    conflictReminder.setStatus(ReminderStatus.CANCELLED);
                                    reminderRepository.save(conflictReminder);
                                }
                            }
                        }
                    } else if ("skip".equals(action)) {
                        ConflictResponse conflict = conflictDetectionService.checkConflict(
                                userId, courseTime, endTime, null);
                        if (conflict.isHasConflict()) {
                            failedCount++;
                            continue;
                        }
                    }

                    // 创建提醒
                    Reminder reminder = new Reminder();
                    reminder.setUserId(userId);
                    reminder.setTitle(course.getTitle());
                    reminder.setRemindTime(courseTime);
                    reminder.setEndTime(endTime);
                    reminder.setPriority(Priority.NORMAL);
                    reminder.setLocation(course.getLocation());
                    reminder.setStatus(ReminderStatus.PENDING);
                    reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
                    reminder.setExecutionStatus(ExecutionStatus.PENDING);
                    reminder.setSource(ReminderSource.COURSE);
                    reminder.setTimezone("Asia/Shanghai");
                    reminderRepository.save(reminder);

                    successCount++;
                }
            } catch (Exception e) {
                failedCount++;
                log.warn("课表提醒创建失败：course={}, error={}", course.getTitle(), e.getMessage());
            }
        }

        // 更新导入记录
        importRecord.setStatus("CONFIRMED");
        importRecord.setSuccessCount(successCount);
        importRecord.setFailedCount(failedCount);
        importRecord.setTotalCount(successCount + failedCount);
        importRepository.save(importRecord);

        // 清理缓存
        scheduleTableCache.remove(importId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已创建 " + successCount + " 条提醒" +
                (failedCount > 0 ? "（跳过 " + failedCount + " 条冲突）" : ""));
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);

        log.info("课表导入完成：importId={}, success={}, failed={}, action={}", importId, successCount, failedCount, action);
        return result;
    }

    // 通用场景：从数据库明细记录批量静默创建提醒，并更新明细和导入记录状态
    private Map<String, Object> createRemindersFromItems(Long importId, Long userId,
                                                          List<ScheduleImportItem> items,
                                                          ScheduleImport importRecord) {
        // 转换为批量静默创建所需的格式
        List<Map<String, Object>> silentItems = new ArrayList<>();
        for (ScheduleImportItem item : items) {
            Map<String, Object> silentItem = new HashMap<>();
            silentItem.put("title", item.getTitle());
            silentItem.put("remindTime", item.getRemindDate().format(TimeUtils.DATE_FORMATTER) + " "
                    + item.getRemindTime().format(TimeUtils.TIME_FORMATTER) + ":00");
            silentItem.put("location", item.getLocation());
            silentItems.add(silentItem);
        }

        // 调用静默批量创建，不触发逐条确认和推送
        Map<String, Object> createResult = batchCreateSilently(userId, silentItems);

        int successCount = (int) createResult.getOrDefault("successCount", 0);
        int failedCount = (int) createResult.getOrDefault("failedCount", 0);

        // 更新明细状态
        for (ScheduleImportItem item : items) {
            item.setStatus("CONFIRMED");
        }
        importItemRepository.saveAll(items);

        importRecord.setStatus("CONFIRMED");
        importRecord.setSuccessCount(successCount);
        importRecord.setFailedCount(failedCount);
        importRepository.save(importRecord);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", createResult.getOrDefault("message", "已创建 " + successCount + " 条提醒"));
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);

        log.info("批量导入完成：importId={}, success={}, failed={}", importId, successCount, failedCount);
        return result;
    }

    // 批量静默创建提醒：不触发确认会话、不逐条推送，一次性创建全部并仅推送一条汇总消息
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchCreateSilently(Long userId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "没有待创建的日程");
            return result;
        }

        int successCount = 0;
        int failedCount = 0;
        List<String> createdTitles = new ArrayList<>();
        List<String> failedTitles = new ArrayList<>();

        for (Map<String, Object> item : items) {
            try {
                String title = (String) item.get("title");
                String remindTimeStr = (String) item.get("remindTime");
                String location = (String) item.get("location");

                if (title == null || remindTimeStr == null) {
                    failedCount++;
                    failedTitles.add(title != null ? title : "未知");
                    continue;
                }

                LocalDateTime remindTime = LocalDateTime.parse(remindTimeStr, TimeUtils.FORMATTER);

                Reminder reminder = new Reminder();
                reminder.setUserId(userId);
                reminder.setTitle(title);
                reminder.setRemindTime(remindTime);
                reminder.setPriority(Priority.NORMAL);
                reminder.setLocation(location);
                reminder.setStatus(ReminderStatus.PENDING);
                reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
                reminder.setExecutionStatus(ExecutionStatus.PENDING);
                reminder.setSource(ReminderSource.COURSE);
                reminder.setTimezone("Asia/Shanghai");

                reminderRepository.save(reminder);
                successCount++;
                createdTitles.add(title);

            } catch (Exception e) {
                failedCount++;
                String title = (String) item.get("title");
                failedTitles.add(title != null ? title : "未知");
                log.error("批量静默创建提醒失败：title={}", title, e);
            }
        }

        String summaryMessage = String.format("📌 成功创建 %d 条提醒", successCount);
        if (failedCount > 0) {
            summaryMessage += String.format("，%d 条失败", failedCount);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", summaryMessage);
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("createdTitles", createdTitles);
        result.put("failedTitles", failedTitles);

        pushSummaryMessage(userId, summaryMessage);

        return result;
    }

    // 推送一条汇总消息到用户的消息队列
    private void pushSummaryMessage(Long userId, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "import_result");
        data.put("message", message);
        webSocketPushService.pushToUser(userId, "/queue/messages", data);
    }

    // 取消导入：校验归属与状态后置为 CANCELLED，并把待处理明细一并取消
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelImport(Long importId, Long userId) {
        ScheduleImport importRecord = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalArgumentException("导入记录不存在：" + importId));

        if (!importRecord.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作此导入记录");
        }

        if ("CONFIRMED".equals(importRecord.getStatus())) {
            throw new IllegalArgumentException("已确认的导入无法取消");
        }

        importRecord.setStatus("CANCELLED");
        importRepository.save(importRecord);

        // 更新未完成的明细
        List<ScheduleImportItem> pendingItems = importItemRepository.findByImportIdAndStatus(importId, "PENDING");
        for (ScheduleImportItem item : pendingItems) {
            item.setStatus("CANCELLED");
        }
        importItemRepository.saveAll(pendingItems);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已取消导入");
        return result;
    }

    // 查询导入结果：返回导入记录状态、明细列表，以及缓存中的课表识别结果（时间段、课程、学期开始日期）
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Map<String, Object> getImportResult(Long importId, Long userId) {
        ScheduleImport importRecord = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalArgumentException("导入记录不存在：" + importId));

        if (!importRecord.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权查看此导入记录");
        }

        List<ScheduleImportItem> items = importItemRepository.findByImportId(importId);

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (ScheduleImportItem item : items) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", item.getId());
            map.put("title", item.getTitle());
            map.put("date", item.getRemindDate().format(TimeUtils.DATE_FORMATTER));
            map.put("time", item.getRemindTime().format(TimeUtils.TIME_FORMATTER));
            map.put("location", item.getLocation());
            map.put("status", item.getStatus());
            map.put("errorMessage", item.getErrorMessage());
            itemList.add(map);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("importId", importRecord.getId());
        result.put("fileName", importRecord.getFileName());
        result.put("status", importRecord.getStatus());
        result.put("totalCount", importRecord.getTotalCount());
        result.put("successCount", importRecord.getSuccessCount());
        result.put("failedCount", importRecord.getFailedCount());
        result.put("items", itemList);

        // 从缓存中获取课表识别结果（含 timeSections 和 semesterStartDate）
        ScheduleTableDTO cached = scheduleTableCache.get(importId);
        if (cached != null) {
            List<Map<String, Object>> timeSectionList = new ArrayList<>();
            if (cached.getTimeSections() != null) {
                for (ScheduleTableDTO.TimeSection ts : cached.getTimeSections()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("index", ts.getIndex());
                    map.put("startTime", ts.getStartTime());
                    map.put("endTime", ts.getEndTime());
                    timeSectionList.add(map);
                }
            }
            // timeSections 为空时用默认值兜底，避免前端只显示表头没有行
            if (timeSectionList.isEmpty()) {
                for (ScheduleTableDTO.TimeSection ts : getDefaultTimeSections()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("index", ts.getIndex());
                    map.put("startTime", ts.getStartTime());
                    map.put("endTime", ts.getEndTime());
                    timeSectionList.add(map);
                }
            }
            result.put("timeSections", timeSectionList);
            result.put("semesterStartDate", cached.getSemesterStartDate());
            result.put("suggestedWeeks", cached.getSuggestedWeeks() != null ? cached.getSuggestedWeeks() : 16);
            result.put("totalWeeks", cached.getSuggestedWeeks() != null ? cached.getSuggestedWeeks() : 16);

            // 课程列表
            List<Map<String, Object>> courseList = new ArrayList<>();
            if (cached.getCourses() != null) {
                for (ScheduleTableDTO.CourseItem course : cached.getCourses()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("title", course.getTitle());
                    map.put("teacher", course.getTeacher());
                    map.put("dayOfWeek", course.getDayOfWeek());
                    map.put("sectionIndices", course.getSectionIndices());
                    map.put("weeks", course.getWeeks());
                    map.put("oddWeek", course.getOddWeek());
                    map.put("evenWeek", course.getEvenWeek());
                    map.put("location", course.getLocation());
                    courseList.add(map);
                }
            }
            result.put("courses", courseList);
        }

        return result;
    }

    // 推送课表确认卡片：组装课程、时间段和操作按钮，经 WebSocket 下发给用户
    private void pushScheduleTableCard(Long userId, Long importId, ScheduleTableDTO tableResult, String fileName) {
        try {
            // 构建课程列表信息
            List<Map<String, Object>> courseList = new ArrayList<>();
            if (tableResult.getCourses() != null) {
                for (ScheduleTableDTO.CourseItem course : tableResult.getCourses()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("title", course.getTitle());
                    map.put("teacher", course.getTeacher());
                    map.put("dayOfWeek", course.getDayOfWeek());
                    map.put("sectionIndices", course.getSectionIndices());
                    map.put("weeks", course.getWeeks());
                    map.put("oddWeek", course.getOddWeek());
                    map.put("evenWeek", course.getEvenWeek());
                    map.put("location", course.getLocation());
                    courseList.add(map);
                }
            }

            // 构建时间信息
            List<Map<String, Object>> timeSectionList = new ArrayList<>();
            if (tableResult.getTimeSections() != null) {
                for (ScheduleTableDTO.TimeSection ts : tableResult.getTimeSections()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("index", ts.getIndex());
                    map.put("startTime", ts.getStartTime());
                    map.put("endTime", ts.getEndTime());
                    timeSectionList.add(map);
                }
            }

            int totalCount = courseList.size();

            // 构建卡片 items
            List<ConfirmationCardResponse.ScheduleItem> items = new ArrayList<>();
            for (Map<String, Object> course : courseList) {
                items.add(ConfirmationCardResponse.ScheduleItem.builder()
                        .title((String) course.get("title"))
                        .date("")
                        .time((String) course.get("dayOfWeek"))
                        .location((String) course.get("location"))
                        .repeatType(RepeatType.WEEKLY.name())
                        .build());
            }

            ConfirmationCardResponse card = ConfirmationCardResponse.builder()
                    .type("schedule_table_confirm")
                    .message("📚 课表识别完成，请配置时间后确认")
                    .data(ConfirmationCardResponse.ConfirmationData.builder()
                            .sessionId("schedule-table-" + System.currentTimeMillis())
                            .importId(String.valueOf(importId))
                            .sourceFile(fileName)
                            .isBatch(true)
                            .totalCount(totalCount)
                            .items(items)
                            .timeSections(timeSectionList)
                            .semesterStartDate(tableResult.getSemesterStartDate())
                            .suggestedWeeks(tableResult.getSuggestedWeeks() != null ? tableResult.getSuggestedWeeks() : 16)
                            .totalWeeks(tableResult.getSuggestedWeeks() != null ? tableResult.getSuggestedWeeks() : 16)
                            .build())
                    .actions(List.of(
                            ConfirmationCardResponse.Action.builder()
                                    .id("confirm")
                                    .label("✅ 确认生成")
                                    .style("primary")
                                    .build(),
                            ConfirmationCardResponse.Action.builder()
                                    .id("adjust")
                                    .label("✏️ 手动调整")
                                    .style("secondary")
                                    .build(),
                            ConfirmationCardResponse.Action.builder()
                                    .id("cancel")
                                    .label("❌ 取消")
                                    .style("danger")
                                    .build()
                    ))
                    .timeout(600)
                    .build();

            webSocketPushService.pushToUser(userId, "/queue/confirmations", card);
            log.info("课表确认卡片已推送：userId={}, importId={}, courseCount={}", userId, importId, totalCount);
        } catch (Exception e) {
            log.error("推送课表确认卡片失败：userId={}, importId={}", userId, importId, e);
        }
    }

    // 根据用户配置的时间表生成具体日程：保存学期开始日期和时间段配置，返回配置结果
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateScheduleFromConfig(Long userId, CourseTimeConfigDTO config) {
        log.info("根据配置生成日程：userId={}, semesterStartDate={}, linkMode={}",
                userId, config.getSemesterStartDate(), config.getLinkMode());

        if (config.getSemesterStartDate() == null || config.getSemesterStartDate().isEmpty()) {
            throw new IllegalArgumentException("学期开始日期不能为空");
        }

        LocalDate semesterStart = LocalDate.parse(config.getSemesterStartDate(), TimeUtils.DATE_FORMATTER);

        // 保存学期开始日期
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setSemesterStartDate(semesterStart);
        userRepository.save(user);

        // 使用默认时间配置（从 AI 识别结果或用户自定义）
        List<ScheduleTableDTO.TimeSection> timeSections = new ArrayList<>();
        if (config.getSectionTimes() != null) {
            for (CourseTimeConfigDTO.SectionTime st : config.getSectionTimes()) {
                ScheduleTableDTO.TimeSection ts = ScheduleTableDTO.TimeSection.builder()
                        .index(st.getIndex())
                        .startTime(st.getStartTime())
                        .endTime(st.getEndTime())
                        .build();
                timeSections.add(ts);
            }
        }
        // 如果没有用户配置，使用默认时间
        if (timeSections.isEmpty()) {
            timeSections = getDefaultTimeSections();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "时间表配置已保存");
        result.put("semesterStartDate", config.getSemesterStartDate());
        result.put("timeSections", timeSections);
        result.put("totalWeeks", config.getTotalWeeks() != null ? config.getTotalWeeks() : 16);
        return result;
    }

    // 根据手动输入的课程列表生成具体日程：逐课展开上课时间并做冲突检测
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateScheduleFromManual(Long userId, List<ManualCourseDTO> courses,
                                                           CourseTimeConfigDTO config) {
        log.info("根据手动输入生成日程：userId={}, courseCount={}", userId, courses != null ? courses.size() : 0);

        if (courses == null || courses.isEmpty()) {
            throw new IllegalArgumentException("课程列表不能为空");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 获取学期开始日期
        LocalDate semesterStart;
        if (config != null && config.getSemesterStartDate() != null) {
            semesterStart = LocalDate.parse(config.getSemesterStartDate(), TimeUtils.DATE_FORMATTER);
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        } else if (user.getSemesterStartDate() != null) {
            semesterStart = user.getSemesterStartDate();
        } else {
            semesterStart = LocalDate.now();
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        }

        // 获取时间配置
        List<ScheduleTableDTO.TimeSection> timeSections = getTimeSections(config);

        // 获取学期总周数
        int totalWeeks = 16;
        if (config != null && config.getTotalWeeks() != null && config.getTotalWeeks() > 0) {
            totalWeeks = config.getTotalWeeks();
        }

        List<Map<String, Object>> generatedItems = new ArrayList<>();
        List<Map<String, Object>> conflictItems = new ArrayList<>();

        for (ManualCourseDTO course : courses) {
            try {
                List<CourseTimeSlot> slots = calculateCourseTimes(
                        semesterStart, course, timeSections, totalWeeks);

                for (CourseTimeSlot slot : slots) {
                    LocalDateTime courseTime = slot.start();
                    LocalDateTime endTime = slot.end();
                    // 冲突检测
                    ConflictResponse conflict = conflictDetectionService.checkConflict(userId, courseTime, endTime, null);

                    Map<String, Object> item = new HashMap<>();
                    item.put("courseName", course.getCourseName());
                    item.put("time", courseTime.format(TimeUtils.FORMATTER));
                    item.put("endTime", endTime.format(TimeUtils.FORMATTER));
                    item.put("location", course.getLocation());
                    item.put("teacher", course.getTeacher());
                    item.put("dayOfWeek", course.getDayOfWeek());
                    item.put("sectionRange", course.getSectionRange());
                    item.put("weekRange", course.getWeekRange());
                    item.put("oddEvenWeek", course.getOddEvenWeek());

                    if (conflict.isHasConflict()) {
                        item.put("hasConflict", true);
                        int conflictCount = conflict.getConflicts() != null ? conflict.getConflicts().size() : 0;
                        item.put("conflictMessage", "与 " + conflictCount + " 个提醒时间冲突");
                        item.put("conflictCount", conflictCount);
                        conflictItems.add(item);
                    } else {
                        item.put("hasConflict", false);
                    }
                    generatedItems.add(item);
                }
            } catch (Exception e) {
                log.warn("生成课程时间失败：course={}, error={}", course.getCourseName(), e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "生成 " + generatedItems.size() + " 条日程，其中 " + conflictItems.size() + " 条有冲突");
        result.put("items", generatedItems);
        result.put("conflictItems", conflictItems);
        result.put("totalCount", generatedItems.size());
        result.put("conflictCount", conflictItems.size());
        result.put("totalWeeks", totalWeeks);
        return result;
    }

    // 根据缓存的课表识别结果生成具体日程：写回用户的时间配置，逐课展开时间并做冲突检测
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateScheduleFromImport(Long userId, Long importId,
                                                          CourseTimeConfigDTO config) {
        log.info("根据导入记录生成日程：userId={}, importId={}", userId, importId);

        ScheduleTableDTO tableResult = scheduleTableCache.get(importId);
        if (tableResult == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "课表识别结果已过期，请重新上传");
            return result;
        }

        // 加载导入记录，用于保存用户配置
        ScheduleImport importRecord = importRepository.findById(importId).orElse(null);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 获取学期总周数
        int totalWeeks = 16;
        if (config != null && config.getTotalWeeks() != null && config.getTotalWeeks() > 0) {
            totalWeeks = config.getTotalWeeks();
        } else if (tableResult.getSuggestedWeeks() != null && tableResult.getSuggestedWeeks() > 0) {
            totalWeeks = tableResult.getSuggestedWeeks();
        }

        // 确定学期开始日期
        LocalDate semesterStart;
        if (config != null && config.getSemesterStartDate() != null && !config.getSemesterStartDate().isEmpty()) {
            semesterStart = LocalDate.parse(config.getSemesterStartDate(), TimeUtils.DATE_FORMATTER);
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        } else if (tableResult.getSemesterStartDate() != null) {
            semesterStart = LocalDate.parse(tableResult.getSemesterStartDate(), TimeUtils.DATE_FORMATTER);
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        } else if (user.getSemesterStartDate() != null) {
            semesterStart = user.getSemesterStartDate();
        } else {
            semesterStart = LocalDate.now();
            user.setSemesterStartDate(semesterStart);
            userRepository.save(user);
        }

        // 保存用户配置到 importRecord（周数 + 学期开始日期），供后续确认创建时使用
        if (importRecord != null) {
            importRecord.setTotalWeeks(totalWeeks);
            importRecord.setSemesterStartDate(semesterStart);
            importRepository.save(importRecord);
            log.debug("保存导入记录配置：importId={}, totalWeeks={}, semesterStartDate={}",
                    importId, totalWeeks, semesterStart);
        }

        // 获取时间段
        List<ScheduleTableDTO.TimeSection> timeSections;
        if (config != null) {
            timeSections = getTimeSections(config);
            // 用户修改了时间配置，写回缓存供后续"直接创建"使用
            tableResult.setTimeSections(timeSections);
            scheduleTableCache.put(importId, tableResult);
        } else if (tableResult.getTimeSections() != null && !tableResult.getTimeSections().isEmpty()) {
            timeSections = tableResult.getTimeSections();
        } else {
            timeSections = getDefaultTimeSections();
        }

        List<Map<String, Object>> generatedItems = new ArrayList<>();
        List<Map<String, Object>> conflictItems = new ArrayList<>();

        if (tableResult.getCourses() != null) {
            for (ScheduleTableDTO.CourseItem course : tableResult.getCourses()) {
                try {
                    List<CourseTimeSlot> slots = calculateCourseTimesFromCourseItem(
                            semesterStart, course, timeSections, totalWeeks);

                    for (CourseTimeSlot slot : slots) {
                        LocalDateTime courseTime = slot.start();
                        LocalDateTime endTime = slot.end();
                        ConflictResponse conflict = conflictDetectionService.checkConflict(
                                userId, courseTime, endTime, null);

                        Map<String, Object> item = new HashMap<>();
                        item.put("title", course.getTitle());
                        item.put("time", courseTime.format(TimeUtils.FORMATTER));
                        item.put("endTime", endTime.format(TimeUtils.FORMATTER));
                        item.put("location", course.getLocation());
                        item.put("teacher", course.getTeacher());
                        item.put("dayOfWeek", course.getDayOfWeek());

                        if (conflict.isHasConflict()) {
                            item.put("hasConflict", true);
                            int conflictCount = conflict.getConflicts() != null ? conflict.getConflicts().size() : 0;
                            item.put("conflictMessage", "与 " + conflictCount + " 个提醒时间冲突");
                            item.put("conflictCount", conflictCount);
                            conflictItems.add(item);
                        } else {
                            item.put("hasConflict", false);
                        }
                        generatedItems.add(item);
                    }
                } catch (Exception e) {
                    log.warn("生成课程时间失败：course={}, error={}", course.getTitle(), e.getMessage());
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "生成 " + generatedItems.size() + " 条日程，其中 " + conflictItems.size() + " 条有冲突");
        result.put("items", generatedItems);
        result.put("conflictItems", conflictItems);
        result.put("totalCount", generatedItems.size());
        result.put("conflictCount", conflictItems.size());
        result.put("totalWeeks", totalWeeks);
        return result;
    }

    // 一节课的起止时间对：start=首节开始时间，end=末节结束时间（同一日期）
    private record CourseTimeSlot(LocalDateTime start, LocalDateTime end) {}

    // 从 CourseItem 计算具体上课时间列表：按周次、单双周规则展开到学期开始日期上。
    // 每周生成一条 CourseTimeSlot（一课一条提醒），起止时间取自该课所占首节 startTime 与末节 endTime，
    // 不再使用"开始+90分钟"的粗略估算，确保与节次时间表一致。
    private List<CourseTimeSlot> calculateCourseTimesFromCourseItem(
            LocalDate semesterStart, ScheduleTableDTO.CourseItem course,
            List<ScheduleTableDTO.TimeSection> timeSections, int totalWeeks) {
        List<CourseTimeSlot> slots = new ArrayList<>();

        DayOfWeek dayOfWeek = parseDayOfWeek(course.getDayOfWeek());
        if (dayOfWeek == null) return slots;

        List<Integer> sectionIndices = course.getSectionIndices();
        if (sectionIndices == null || sectionIndices.isEmpty()) return slots;

        // 取首末节次，查对应时间段
        int firstSection = sectionIndices.stream().min(Integer::compareTo).orElse(-1);
        int lastSection = sectionIndices.stream().max(Integer::compareTo).orElse(-1);
        ScheduleTableDTO.TimeSection firstTs = timeSections.stream()
                .filter(t -> t.getIndex() == firstSection).findFirst().orElse(null);
        ScheduleTableDTO.TimeSection lastTs = timeSections.stream()
                .filter(t -> t.getIndex() == lastSection).findFirst().orElse(null);
        if (firstTs == null || lastTs == null) return slots;

        LocalTime startTime;
        LocalTime endTime;
        try {
            startTime = LocalTime.parse(firstTs.getStartTime(), TimeUtils.TIME_FORMATTER);
            endTime = LocalTime.parse(lastTs.getEndTime(), TimeUtils.TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("解析课程起止时间失败：course={}, firstSection={}, lastSection={}",
                    course.getTitle(), firstSection, lastSection);
            return slots;
        }

        // 用户配置的 totalWeeks 优先：用它作为完整周次范围，覆盖课程自带的 weeks
        List<Integer> weeks;
        if (totalWeeks > 0) {
            weeks = new ArrayList<>();
            for (int w = 1; w <= totalWeeks; w++) {
                weeks.add(w);
            }
        } else {
            weeks = course.getWeeks();
            if (weeks == null || weeks.isEmpty()) {
                weeks = new ArrayList<>();
                for (int w = 1; w <= 16; w++) {
                    weeks.add(w);
                }
            }
        }

        boolean oddWeek = Boolean.TRUE.equals(course.getOddWeek());
        boolean evenWeek = Boolean.TRUE.equals(course.getEvenWeek());

        for (int week : weeks) {
            LocalDate courseDate = semesterStart.plusWeeks(week - 1).with(dayOfWeek);

            boolean skipWeek = false;
            if (oddWeek && !evenWeek && week % 2 == 0) skipWeek = true;
            if (!oddWeek && evenWeek && week % 2 == 1) skipWeek = true;
            if (skipWeek) continue;

            slots.add(new CourseTimeSlot(
                    LocalDateTime.of(courseDate, startTime),
                    LocalDateTime.of(courseDate, endTime)));
        }
        return slots;
    }

    // 确认并创建课表日程：按 action 处理冲突，逐条创建提醒并统计成败
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmSchedule(Long userId, String action, List<ScheduleItemDTO> items) {
        log.info("确认课表日程：userId={}, action={}, itemCount={}", userId, action, items != null ? items.size() : 0);

        if (items == null || items.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "没有待创建的日程");
            return result;
        }

        int successCount = 0;
        int failedCount = 0;
        List<String> createdReminders = new ArrayList<>();

        for (ScheduleItemDTO item : items) {
            try {
                User user = userRepository.findById(userId)
                        .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

                String remindTimeStr = item.getDate() + " " + item.getTime() + ":00";
                LocalDateTime remindTime = LocalDateTime.parse(remindTimeStr, TimeUtils.FORMATTER);

                // 结束时间：优先用 item 携带的 endTime（来自节次时间表），缺失时回退到开始时间+90分钟
                LocalDateTime endTime;
                if (item.getEndTime() != null && !item.getEndTime().isBlank()) {
                    try {
                        endTime = LocalDateTime.parse(item.getDate() + " " + item.getEndTime() + ":00", TimeUtils.FORMATTER);
                    } catch (Exception e) {
                        endTime = remindTime.plusMinutes(90);
                    }
                } else {
                    endTime = remindTime.plusMinutes(90);
                }

                // 根据 action 处理冲突
                if ("overwrite-all".equals(action)) {
                    // 覆盖所有冲突
                    ConflictResponse conflict = conflictDetectionService.checkConflict(userId, remindTime, endTime, null);
                    if (conflict.isHasConflict() && conflict.getConflicts() != null) {
                        for (ConflictResponse.ConflictItem ci : conflict.getConflicts()) {
                            Reminder conflictReminder = reminderRepository.findById(ci.getId()).orElse(null);
                            if (conflictReminder != null) {
                                conflictReminder.setStatus(ReminderStatus.CANCELLED);
                                reminderRepository.save(conflictReminder);
                            }
                        }
                        log.info("覆盖冲突提醒：userId={}, remindTime={}, conflictCount={}",
                                userId, remindTime, conflict.getConflicts().size());
                    }
                }

                // 创建新提醒
                Reminder reminder = new Reminder();
                reminder.setUserId(userId);
                reminder.setTitle(item.getTitle());
                reminder.setRemindTime(remindTime);
                reminder.setEndTime(endTime);
                reminder.setPriority(Priority.NORMAL);
                reminder.setLocation(item.getLocation());
                reminder.setStatus(ReminderStatus.PENDING);
                reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
                reminder.setExecutionStatus(ExecutionStatus.PENDING);
                reminder.setSource(ReminderSource.COURSE);
                reminder.setTimezone("Asia/Shanghai");
                reminderRepository.save(reminder);

                successCount++;
                createdReminders.add(item.getTitle());
                log.info("课表提醒创建成功：userId={}, title={}, remindTime={}",
                        userId, item.getTitle(), remindTime);
            } catch (Exception e) {
                failedCount++;
                log.error("课表提醒创建失败：userId={}, title={}", userId, item.getTitle(), e);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "课表日程创建完成：成功 " + successCount + " 条，失败 " + failedCount + " 条");
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("createdReminders", createdReminders);
        return result;
    }

    // 计算手动输入课程的所有上课时间：解析星期、节次、周次和单双周后逐周展开。
    // 每周生成一条 CourseTimeSlot（一课一条提醒），起止时间取自首节 startTime 与末节 endTime。
    private List<CourseTimeSlot> calculateCourseTimes(LocalDate semesterStart, ManualCourseDTO course,
                                                      List<ScheduleTableDTO.TimeSection> timeSections, int totalWeeks) {
        List<CourseTimeSlot> slots = new ArrayList<>();

        // 解析星期几
        DayOfWeek dayOfWeek = parseDayOfWeek(course.getDayOfWeek());
        if (dayOfWeek == null) return slots;

        // 解析节次范围
        int[] sectionRange = parseSectionRange(course.getSectionRange());
        if (sectionRange == null) return slots;

        // 取首末节次对应时间段
        int firstSection = sectionRange[0];
        int lastSection = sectionRange[1];
        ScheduleTableDTO.TimeSection firstTs = timeSections.stream()
                .filter(t -> t.getIndex() == firstSection).findFirst().orElse(null);
        ScheduleTableDTO.TimeSection lastTs = timeSections.stream()
                .filter(t -> t.getIndex() == lastSection).findFirst().orElse(null);
        if (firstTs == null || lastTs == null) return slots;

        LocalTime startTime;
        LocalTime endTime;
        try {
            startTime = LocalTime.parse(firstTs.getStartTime(), TimeUtils.TIME_FORMATTER);
            endTime = LocalTime.parse(lastTs.getEndTime(), TimeUtils.TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("解析课程起止时间失败：course={}, firstSection={}, lastSection={}",
                    course.getCourseName(), firstSection, lastSection);
            return slots;
        }

        // 解析周次范围，如果为空则使用总周数作为默认
        List<Integer> weeks = parseWeekRange(course.getWeekRange());
        if (weeks.isEmpty()) {
            for (int w = 1; w <= totalWeeks; w++) {
                weeks.add(w);
            }
        }

        // 解析单双周
        String oddEven = course.getOddEvenWeek();

        for (int week : weeks) {
            // 计算该周的星期几日期
            LocalDate courseDate = semesterStart.plusWeeks(week - 1)
                    .with(dayOfWeek);

            // 检查单双周
            if ("单周".equals(oddEven) && week % 2 == 0) continue;
            if ("双周".equals(oddEven) && week % 2 == 1) continue;

            slots.add(new CourseTimeSlot(
                    LocalDateTime.of(courseDate, startTime),
                    LocalDateTime.of(courseDate, endTime)));
        }
        return slots;
    }

    // 解析星期几，支持"星期一/周一/1"等多种写法
    private DayOfWeek parseDayOfWeek(String dayStr) {
        if (dayStr == null) return null;
        return switch (dayStr.trim()) {
            case "星期一", "周一", "1" -> DayOfWeek.MONDAY;
            case "星期二", "周二", "2" -> DayOfWeek.TUESDAY;
            case "星期三", "周三", "3" -> DayOfWeek.WEDNESDAY;
            case "星期四", "周四", "4" -> DayOfWeek.THURSDAY;
            case "星期五", "周五", "5" -> DayOfWeek.FRIDAY;
            case "星期六", "周六", "6" -> DayOfWeek.SATURDAY;
            case "星期日", "周日", "7" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    // 解析节次范围，支持"3-4"或单节"3"两种写法，返回 [start, end]
    private int[] parseSectionRange(String range) {
        if (range == null || range.isEmpty()) return null;
        try {
            if (range.contains("-")) {
                String[] parts = range.split("-");
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());
                return new int[]{start, end};
            } else {
                int single = Integer.parseInt(range.trim());
                return new int[]{single, single};
            }
        } catch (NumberFormatException e) {
            log.warn("节次范围解析失败：{}", range);
            return null;
        }
    }

    // 解析周次范围，支持"1-16"、"1,3,5"或单周"3"等写法
    private List<Integer> parseWeekRange(String range) {
        List<Integer> weeks = new ArrayList<>();
        if (range == null || range.isEmpty()) return weeks;
        try {
            if (range.contains(",")) {
                for (String part : range.split(",")) {
                    weeks.add(Integer.parseInt(part.trim()));
                }
            } else if (range.contains("-")) {
                String[] parts = range.split("-");
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());
                for (int i = start; i <= end; i++) {
                    weeks.add(i);
                }
            } else {
                weeks.add(Integer.parseInt(range.trim()));
            }
        } catch (NumberFormatException e) {
            log.warn("周次范围解析失败：{}", range);
        }
        return weeks;
    }

    // 获取时间段配置：优先用用户配置，缺失时回退到默认时间表
    private List<ScheduleTableDTO.TimeSection> getTimeSections(CourseTimeConfigDTO config) {
        if (config != null && config.getSectionTimes() != null && !config.getSectionTimes().isEmpty()) {
            List<ScheduleTableDTO.TimeSection> sections = new ArrayList<>();
            for (CourseTimeConfigDTO.SectionTime st : config.getSectionTimes()) {
                sections.add(ScheduleTableDTO.TimeSection.builder()
                        .index(st.getIndex())
                        .startTime(st.getStartTime())
                        .endTime(st.getEndTime())
                        .build());
            }
            return sections;
        }
        return getDefaultTimeSections();
    }

    // 默认时间表，共 10 节，覆盖早八到晚课的常规作息
    private List<ScheduleTableDTO.TimeSection> getDefaultTimeSections() {
        List<ScheduleTableDTO.TimeSection> sections = new ArrayList<>();
        sections.add(ScheduleTableDTO.TimeSection.builder().index(1).startTime("08:00").endTime("08:45").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(2).startTime("08:55").endTime("09:40").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(3).startTime("10:00").endTime("10:45").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(4).startTime("10:55").endTime("11:40").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(5).startTime("14:00").endTime("14:45").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(6).startTime("14:55").endTime("15:40").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(7).startTime("16:00").endTime("16:45").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(8).startTime("16:55").endTime("17:40").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(9).startTime("19:00").endTime("19:45").build());
        sections.add(ScheduleTableDTO.TimeSection.builder().index(10).startTime("19:55").endTime("20:40").build());
        return sections;
    }

    // 推送批量导入确认卡片到用户，携带识别到的日程项和确认/取消按钮
    private void pushConfirmCardToUser(Long userId, Long importId, String fileName,
                                       List<Map<String, Object>> items) {
        try {
            ConfirmationCardResponse card = ConfirmationCardResponse.builder()
                    .type("confirmation_card")
                    .message("📋 从「" + fileName + "」识别到 " + items.size() + " 条日程，请确认创建")
                    .data(ConfirmationCardResponse.ConfirmationData.builder()
                            .sourceFile(fileName)
                            .isBatch(true)
                            .totalCount(items.size())
                            .build())
                    .actions(List.of(
                            ConfirmationCardResponse.Action.builder()
                                    .id("confirm_batch")
                                    .label("✅ 全部创建")
                                    .style("primary")
                                    .build(),
                            ConfirmationCardResponse.Action.builder()
                                    .id("cancel")
                                    .label("❌ 取消")
                                    .style("danger")
                                    .build()
                    ))
                    .timeout(300)
                    .build();

            // 将 items 写入卡片 data
            card.getData().setItems(items.stream()
                    .map(m -> ConfirmationCardResponse.ScheduleItem.builder()
                            .title((String) m.get("title"))
                            .date((String) m.get("date"))
                            .time((String) m.get("time"))
                            .location((String) m.get("location"))
                            .repeatType((String) m.get("repeatType"))
                            .build())
                    .collect(Collectors.toList()));

            webSocketPushService.pushToUser(userId, "/queue/confirmations", card);
            log.info("批量导入确认卡片已推送：importId={}, userId={}", importId, userId);
        } catch (Exception e) {
            log.error("推送确认卡片失败：importId={}", importId, e);
        }
    }

    private boolean isImage(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "unknown";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "unknown";
    }

    private LocalDate parseDateSafely(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(dateStr, TimeUtils.DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("日期解析失败：{}，使用当前日期", dateStr);
            return LocalDate.now();
        }
    }

    private LocalTime parseTimeSafely(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return LocalTime.of(9, 0);
        }
        try {
            return LocalTime.parse(timeStr, TimeUtils.TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("时间解析失败：{}，使用默认 09:00", timeStr);
            return LocalTime.of(9, 0);
        }
    }
}
