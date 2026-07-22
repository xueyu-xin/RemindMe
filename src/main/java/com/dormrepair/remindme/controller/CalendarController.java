package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.CalendarResponse;
import com.dormrepair.remindme.dto.response.PeriodStatusResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import com.dormrepair.remindme.service.PeriodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
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

        boolean hasPendingReminder = dayReminders.stream()
                .anyMatch(r -> r.getStatus().name().equals("PENDING") 
                           && r.getConfirmStatus().name().equals("CONFIRMED")
                           && r.getExecutionStatus() == ExecutionStatus.PENDING);

        String periodStatus = null;
        if (user != null && user.getGender().name().equals("FEMALE")) {
            PeriodStatusResponse status = periodService.calculatePeriodStatus(user);
            periodStatus = status.getStatus();
        }

        List<CalendarResponse.ReminderItem> reminderItems = dayReminders.stream()
                .map(r -> new CalendarResponse.ReminderItem(
                        r.getId(),
                        r.getTitle(),
                        r.getRemindTime().toString(),
                        r.getPriority().name(),
                        r.getStatus().name(),
                        r.getConfirmStatus().name(),
                        r.getLocation()
                ))
                .collect(Collectors.toList());

        return new CalendarResponse.DayItem(
                date,
                hasPendingReminder,
                dayReminders.size(),
                periodStatus,
                reminderItems
        );
    }
}
