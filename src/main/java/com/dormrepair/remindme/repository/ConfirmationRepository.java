package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConfirmationRepository extends JpaRepository<ConfirmationSession, String> {

    Optional<ConfirmationSession> findBySessionIdAndUserId(String sessionId, Long userId);

    List<ConfirmationSession> findByUserIdAndStatus(Long userId, SessionStatus status);

    List<ConfirmationSession> findByStatusAndExpiresAtBefore(SessionStatus status, LocalDateTime dateTime);

    List<ConfirmationSession> findByUserId(Long userId);

    void deleteByStatusAndExpiresAtBefore(SessionStatus status, LocalDateTime dateTime);
}