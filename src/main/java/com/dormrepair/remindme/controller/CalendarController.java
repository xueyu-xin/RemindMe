package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.CalendarResponse;
import com.dormrepair.remindme.dto.response.PeriodStatusResponse;
import com.dormrepair.remindme.entity.RecurringInstance;
import com.dormrepair.remindme.entity.RecurringReminder;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ExecutionStatus;
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

/**
 * 日历数据API控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final ReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final PeriodService periodService;
    private final RecurringReminderRepository recurringReminderRepository;
    private final RecurringInstanceRepository recurringInstanceRepository;

    @GetMapping("/month")
    public ResponseEntity<CalendarResponse> getMonthView(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        
        LocalDate now = LocalDate.now();
        int targetYear = year != null ? year : now.getYear();
        int targetMonth = month != null ? month : now.getMonthValue();

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

    @GetMapping("/day")
    public ResponseEntity<CalendarResponse> getDayView(
            @RequestParam Long userId,
            @RequestParam LocalDate date) {
        
        User user = userRepository.findById(userId).orElse(null);
        
        CalendarResponse response = new CalendarResponse();
        response.setYear(date.getYear());
        response.setMonth(date.getMonthValue());
        response.setDay(date.getDayOfMonth());
        response.setDays(List.of(createDayItem(date, userId, user)));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/week")
    public ResponseEntity<CalendarResponse> getWeekView(
            @RequestParam Long userId,
            @RequestParam(required = false) LocalDate date) {
        
        LocalDate targetDate = date != null ? date : LocalDate.now();
        LocalDate monday = targetDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        
        User user = userRepository.findById(userId).orElse(null);

        List<CalendarResponse.DayItem> days = new ArrayList<>();
        
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

    @GetMapping("/period-status")
    public ResponseEntity<PeriodStatusResponse> getPeriodStatus(@RequestParam Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        PeriodStatusResponse response = periodService.calculatePeriodStatus(user);
        return ResponseEntity.ok(response);
    }

    private CalendarResponse.DayItem createDayItem(LocalDate date, Long userId, User user) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.atTime(LocalTime.MAX);

        List<Reminder> dayReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(userId))
                .filter(r -> r.getRemindTime() != null)
                .filter(r -> r.getRemindTime().isAfter(dayStart) && r.getRemindTime().isBefore(dayEnd))
                .collect(Collectors.toList());

        // 周期提醒检测：检查 RecurringInstance 表中是否存在该天的记录，或 RecurringReminder 模板中该天是否在范围
        List<RecurringReminder> userRecurringTemplates = recurringReminderRepository
                .findByUserIdAndStatus(userId, "ACTIVE");
        boolean hasRecurringTemplateOnDate = userRecurringTemplates.stream()
                .anyMatch(t -> !date.isBefore(t.getStartDate()) && !date.isAfter(t.getEndDate()));
        // 检查每个模板该天是否有实例
        boolean hasRecurring = false;
        java.util.Map<Long, RecurringReminder> recurringMap = new java.util.HashMap<>();
        java.util.Set<Long> recurringReminderIds = new java.util.HashSet<>();
        for (RecurringReminder t : userRecurringTemplates) {
            if (!date.isBefore(t.getStartDate()) && !date.isAfter(t.getEndDate())) {
                hasRecurring = true;
                RecurringInstance inst = recurringInstanceRepository
                        .findByRecurringIdAndRemindDate(t.getId(), date).orElse(null);
                if (inst != null && inst.getReminderId() != null) {
                    recurringReminderIds.add(inst.getReminderId());
                    recurringMap.put(inst.getReminderId(), t);
                }
            }
        }
        final boolean finalHasRecurring = hasRecurring || hasRecurringTemplateOnDate;
        final java.util.Map<Long, RecurringReminder> finalRecurringMap = recurringMap;
        final java.util.Set<Long> finalRecurringReminderIds = recurringReminderIds;

        boolean hasPendingReminder = dayReminders.stream()
                .anyMatch(r -> r.getStatus().name().equals("PENDING") 
                           && r.getConfirmStatus().name().equals("CONFIRMED")
                           && r.getExecutionStatus() == ExecutionStatus.PENDING);

        // 经期状态判断（按具体日期）
        String periodStatus = null;
        if (user != null && user.getGender() != null && user.getGender().name().equals("FEMALE")) {
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
                            repeatType
                    );
                })
                .collect(Collectors.toList());

        return new CalendarResponse.DayItem(
                date,
                hasPendingReminder,
                finalHasRecurring,
                dayReminders.size(),
                periodStatus,
                festival,
                lunarDate,
                reminderItems
        );
    }

}
