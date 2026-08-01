package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.CalendarResponse;
import com.dormrepair.remindme.dto.response.PeriodStatusResponse;
import com.dormrepair.remindme.entity.RecurringInstance;
import com.dormrepair.remindme.entity.RecurringReminder;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.Gender;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.RecurringInstanceRepository;
import com.dormrepair.remindme.repository.RecurringReminderRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import com.dormrepair.remindme.service.PeriodService;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

// 日历数据接口控制器。统一前缀 /api/calendar，为日历月视图/周视图/日视图提供数据，包含提醒事项、周期提醒、经期状态、农历与节日等聚合信息
@Slf4j
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    // 一次性提醒数据访问层：按用户+日期范围筛选当日提醒列表
    private final ReminderRepository reminderRepository;
    // 用户数据访问层：根据 userId 查用户信息（性别、经期设置等）
    private final UserRepository userRepository;
    // 经期计算服务：判断某一天处于经期/排卵期或返回当前周期状态
    private final PeriodService periodService;
    // 周期提醒模板数据访问层：取用户 ACTIVE 状态的周期提醒模板
    private final RecurringReminderRepository recurringReminderRepository;
    // 周期提醒实例数据访问层：对应具体某一天的周期提醒实例（与实际生成的 Reminder 关联）
    private final RecurringInstanceRepository recurringInstanceRepository;
    // 周期提醒服务：用 shouldGenerateForDate 判断某天是否被周期模板覆盖（按 repeatType/repeatDays 过滤）
    private final com.dormrepair.remindme.service.RecurringReminderService recurringReminderService;

    // 月视图数据接口：按年/月返回该月每一天的聚合信息（提醒、周期、经期、农历）
    // userId - 当前登录用户 ID，用于按用户维度过滤数据
    // year   - 目标年份（可空，缺省为当前年）
    // month  - 目标月份，1-12（可空，缺省为当前月）
    @GetMapping("/month")
    public ResponseEntity<CalendarResponse> getMonthView(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        
        LocalDate now = LocalDate.now();
        int targetYear = year != null ? year : now.getYear();
        int targetMonth = month != null ? month : now.getMonthValue();

        // 计算该月起止日期，从第一天循环到最后一天，逐日生成 DayItem
        LocalDate firstDay = LocalDate.of(targetYear, targetMonth, 1);
        LocalDate lastDay = firstDay.with(TemporalAdjusters.lastDayOfMonth());

        User user = userRepository.findById(userId).orElse(null);

        List<CalendarResponse.DayItem> days = new ArrayList<>();
        
        for (LocalDate date = firstDay; !date.isAfter(lastDay); date = date.plusDays(1)) {
            days.add(createDayItem(date, userId, user));
        }

        CalendarResponse response = new CalendarResponse();
        response.setYear(targetYear);
        response.setMonth(targetMonth);
        response.setDays(days);

        return ResponseEntity.ok(response);
    }

    // 日视图数据接口：返回指定日期当天的详细聚合信息（提醒列表、经期状态、节日等）
    // userId - 用户 ID
    // date   - 查询日期，格式 yyyy-MM-dd
    @GetMapping("/day")
    public ResponseEntity<CalendarResponse> getDayView(
            @RequestParam Long userId,
            @RequestParam LocalDate date) {
        
        User user = userRepository.findById(userId).orElse(null);
        
        CalendarResponse response = new CalendarResponse();
        response.setYear(date.getYear());
        response.setMonth(date.getMonthValue());
        response.setDay(date.getDayOfMonth());
        // 日视图只包含单天，包装成单元素 List 复用 DayItem 结构
        response.setDays(List.of(createDayItem(date, userId, user)));

        return ResponseEntity.ok(response);
    }

    // 周视图数据接口：以周一为起始，返回所在周（周一~周日）共 7 天的聚合信息
    // userId - 用户 ID
    // date   - 基准日期（可空，缺省为今天），会被换算到该周的周一再展开 7 天
    @GetMapping("/week")
    public ResponseEntity<CalendarResponse> getWeekView(
            @RequestParam Long userId,
            @RequestParam(required = false) LocalDate date) {
        
        LocalDate targetDate = date != null ? date : LocalDate.now();
        // previousOrSame(MONDAY)：若 targetDate 本身是周一则保持，否则回退到上一个周一
        LocalDate monday = targetDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        
        User user = userRepository.findById(userId).orElse(null);

        List<CalendarResponse.DayItem> days = new ArrayList<>();
        
        // 从周一连续 7 天逐日生成
        for (int i = 0; i < 7; i++) {
            LocalDate currentDate = monday.plusDays(i);
            days.add(createDayItem(currentDate, userId, user));
        }

        CalendarResponse response = new CalendarResponse();
        response.setYear(monday.getYear());
        response.setMonth(monday.getMonthValue());
        response.setDays(days);

        return ResponseEntity.ok(response);
    }

    // 经期状态查询接口：返回当前用户最近一次经期、下次预计日期、当前所处阶段（经期中/排卵期/安全期等）
    // userId - 用户 ID（女性用户才会有有效返回，男性或缺省字段会走空值兜底）
    @GetMapping("/period-status")
    public ResponseEntity<PeriodStatusResponse> getPeriodStatus(@RequestParam Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        PeriodStatusResponse response = periodService.calculatePeriodStatus(user);
        return ResponseEntity.ok(response);
    }

    // 构造单日日历数据聚合单元（三大视图共用此方法）
    // 聚合维度：当日一次性提醒 + 周期提醒模板/实例 + 待办挂起标记 + 女性经期阶段 + 农历/节日 + 去重后的提醒明细
    private CalendarResponse.DayItem createDayItem(LocalDate date, Long userId, User user) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.atTime(LocalTime.MAX);

        List<Reminder> dayReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(userId))
                .filter(r -> r.getRemindTime() != null)
                .filter(r -> r.getRemindTime().isAfter(dayStart) && r.getRemindTime().isBefore(dayEnd))
                .collect(Collectors.toList());

        // 周期提醒检测：只在「该天确有已生成实例」时才标记 ⟳/红点，避免模板覆盖但无实际日程的日期被误标
        // （例如 repeatDays 为空的旧 WEEKLY 模板会兜底为每天都覆盖，若按模板覆盖标记会把整个范围都标上）
        List<RecurringReminder> userRecurringTemplates = recurringReminderRepository
                .findByUserIdAndStatus(userId, "ACTIVE");
        boolean hasRecurringInstance = false;
        java.util.Map<Long, RecurringReminder> recurringMap = new java.util.HashMap<>();
        java.util.Set<Long> recurringReminderIds = new java.util.HashSet<>();
        for (RecurringReminder t : userRecurringTemplates) {
            if (recurringReminderService.shouldGenerateForDate(t, date)) {
                RecurringInstance inst = recurringInstanceRepository
                        .findByRecurringIdAndRemindDate(t.getId(), date).orElse(null);
                if (inst != null && inst.getReminderId() != null) {
                    hasRecurringInstance = true;
                    recurringReminderIds.add(inst.getReminderId());
                    recurringMap.put(inst.getReminderId(), t);
                }
            }
        }
        final boolean finalHasRecurring = hasRecurringInstance;
        final java.util.Map<Long, RecurringReminder> finalRecurringMap = recurringMap;
        final java.util.Set<Long> finalRecurringReminderIds = recurringReminderIds;

        boolean hasPendingReminder = dayReminders.stream()
                .anyMatch(r -> r.getStatus() == ReminderStatus.PENDING
                           && r.getConfirmStatus() == ConfirmStatus.CONFIRMED
                           && r.getExecutionStatus() == ExecutionStatus.PENDING);

        // 经期状态判断（按具体日期）
        String periodStatus = null;
        if (user != null && user.getGender() != null && user.getGender() == Gender.FEMALE) {
            if (periodService.isMenstruating(user, date)) {
                periodStatus = "经期";
            } else if (periodService.isOvulating(user, date)) {
                periodStatus = "排卵期";
            }
        }

        // 农历日期和节日
        String festival = null;
        String lunarDate = null;
        try {
            Date utilDate = Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Solar solar = Solar.fromDate(utilDate);
            Lunar lunar = solar.getLunar();

            // 农历日期（如"初一"、"十五"）
            lunarDate = lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();

            // 节日：优先农历节日，其次公历节日
            var lunarFestivals = lunar.getFestivals();
            var solarFestivals = solar.getFestivals();
            if (lunarFestivals != null && !lunarFestivals.isEmpty()) {
                festival = lunarFestivals.get(0);
            } else if (solarFestivals != null && !solarFestivals.isEmpty()) {
                festival = solarFestivals.get(0);
            }
        } catch (Exception e) {
            log.warn("农历计算失败: date={}, error={}", date, e.getMessage());
        }

        java.util.Set<Long> alreadyAdded = new java.util.HashSet<>();
        List<CalendarResponse.ReminderItem> reminderItems = dayReminders.stream()
                .filter(r -> {
                    if (alreadyAdded.contains(r.getId())) return false;
                    alreadyAdded.add(r.getId());
                    return true;
                })
                .map(r -> {
                    boolean isRecurring = finalRecurringReminderIds.contains(r.getId());
                    RecurringReminder t = finalRecurringMap.get(r.getId());
                    String recurringRange = null;
                    String repeatType = null;
                    if (t != null) {
                        recurringRange = t.getStartDate() + " ~ " + t.getEndDate();
                        repeatType = t.getRepeatType().name();
                    }
                    return new CalendarResponse.ReminderItem(
                            r.getId(),
                            r.getTitle(),
                            r.getRemindTime().toString(),
                            r.getEndTime() != null ? r.getEndTime().toString() : null,
                            r.getPriority().name(),
                            r.getStatus().name(),
                            r.getConfirmStatus().name(),
                            r.getLocation(),
                            isRecurring,
                            recurringRange,
                            repeatType,
                            r.getSource() != null ? r.getSource().name() : null
                    );
                })
                .collect(Collectors.toList());

        // 红点(hasReminder)：当日有待执行的一次性提醒，或存在已生成的周期实例
        boolean hasReminder = hasPendingReminder || finalHasRecurring;

        return new CalendarResponse.DayItem(
                date,
                hasReminder,
                finalHasRecurring,
                dayReminders.size(),
                periodStatus,
                festival,
                lunarDate,
                reminderItems
        );
    }

}
