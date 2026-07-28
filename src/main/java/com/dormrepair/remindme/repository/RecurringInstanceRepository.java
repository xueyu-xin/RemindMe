package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.RecurringInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecurringInstanceRepository extends JpaRepository<RecurringInstance, Long> {

    boolean existsByRecurringIdAndRemindDate(Long recurringId, LocalDate date);

    Optional<RecurringInstance> findByRecurringIdAndRemindDate(Long recurringId, LocalDate date);

    List<RecurringInstance> findByRecurringId(Long recurringId);
}
