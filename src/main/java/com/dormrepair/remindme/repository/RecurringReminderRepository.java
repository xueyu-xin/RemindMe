package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.RecurringReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RecurringReminderRepository extends JpaRepository<RecurringReminder, Long> {

    List<RecurringReminder> findByUserIdAndStatus(Long userId, String status);

    List<RecurringReminder> findByStatus(String status);

    List<RecurringReminder> findByUserIdAndStatusAndStartDateBeforeAndEndDateAfter(
            Long userId, String status, LocalDate startDate, LocalDate endDate);
}
