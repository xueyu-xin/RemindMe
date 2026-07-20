package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.Action;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse.ConfirmationData;
import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.entity.ConfirmationSession.SessionStatus;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.repository.ConfirmationRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmationService {

    private final ConfirmationRepository confirmationRepository;
    private final ReminderRepository reminderRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int TIMEOUT_SECONDS = 30;

    @Transactional
    public ConfirmationCardResponse createSession(Long userId, String originalInput, Map<String, Object> parsedData) {
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(TIMEOUT_SECONDS);

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(parsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        ConfirmationSession session = ConfirmationSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .originalInput(originalInput)
                .parsedData(parsedDataJson)
                .status(SessionStatus.PENDING)
                .expiresAt(expiresAt)
                .retryCount(0)
                .build();

        confirmationRepository.save(session);

        ConfirmationCardResponse card = buildConfirmationCard(session, parsedData);
        pushConfirmationCard(userId, card);

        log.info("创建确认会话: sessionId={}, userId={}, expiresAt={}", sessionId, userId, expiresAt);
        return card;
    }

    @Transactional
    public Map<String, Object> confirm(String sessionId, Long userId) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        session.setStatus(SessionStatus.CONFIRMED);
        confirmationRepository.save(session);

        Map<String, Object> parsedData = parseParsedData(session.getParsedData());
        Reminder reminder = createReminder(userId, parsedData);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "✅ 提醒已创建");
        result.put("reminderId", reminder.getId());
        result.put("title", reminder.getTitle());
        result.put("remindTime", reminder.getRemindTime().format(FORMATTER));

        pushUserMessage(userId, "success", result);
        log.info("确认创建提醒: sessionId={}, reminderId={}", sessionId, reminder.getId());

        return result;
    }

    @Transactional
    public Map<String, Object> cancel(String sessionId, Long userId) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        session.setStatus(SessionStatus.CANCELLED);
        confirmationRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "❌ 已取消创建提醒");

        pushUserMessage(userId, "cancelled", result);
        log.info("取消确认会话: sessionId={}, userId={}", sessionId, userId);

        return result;
    }

    @Transactional
    public ConfirmationCardResponse reparse(String sessionId, Long userId, String newInput, Map<String, Object> newParsedData) {
        ConfirmationSession session = findValidSession(sessionId, userId);

        String parsedDataJson;
        try {
            parsedDataJson = objectMapper.writeValueAsString(newParsedData);
        } catch (JsonProcessingException e) {
            parsedDataJson = "{}";
        }

        session.setOriginalInput(newInput);
        session.setParsedData(parsedDataJson);
        session.setRetryCount(session.getRetryCount() + 1);
        session.setExpiresAt(LocalDateTime.now().plusSeconds(TIMEOUT_SECONDS));
        confirmationRepository.save(session);

        ConfirmationCardResponse card = buildConfirmationCard(session, newParsedData);
        pushConfirmationCard(userId, card);

        log.info("重新解析确认会话: sessionId={}, userId={}, retryCount={}", sessionId, userId, session.getRetryCount());
        return card;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupExpiredSessions() {
        List<ConfirmationSession> expired = confirmationRepository.findByStatusAndExpiresAtBefore(
                SessionStatus.PENDING, LocalDateTime.now());

        for (ConfirmationSession session : expired) {
            session.setStatus(SessionStatus.EXPIRED);
            confirmationRepository.save(session);

            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "⏰ 确认已超时，请重新输入");

            try {
                pushUserMessage(session.getUserId(), "timeout", result);
            } catch (Exception e) {
                log.warn("推送超时通知失败: userId={}", session.getUserId());
            }

            log.info("会话超时: sessionId={}, userId={}", session.getSessionId(), session.getUserId());
        }
    }

    private ConfirmationSession findValidSession(String sessionId, Long userId) {
        return confirmationRepository.findBySessionIdAndUserId(sessionId, userId)
                .filter(s -> s.getStatus() == SessionStatus.PENDING)
                .filter(s -> s.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或已过期"));
    }

    private Reminder createReminder(Long userId, Map<String, Object> parsedData) {
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setTitle((String) parsedData.get("title"));
        reminder.setRemindTime(LocalDateTime.parse((String) parsedData.get("remindTime"), FORMATTER));
        reminder.setPriority(com.dormrepair.remindme.enums.Priority.valueOf(
                ((String) parsedData.getOrDefault("priority", "NORMAL")).toUpperCase()));
        reminder.setLocation((String) parsedData.get("location"));
        reminder.setAdvanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10));
        reminder.setStatus(com.dormrepair.remindme.enums.ReminderStatus.PENDING);
        reminder.setConfirmStatus(com.dormrepair.remindme.enums.ConfirmStatus.PENDING);
        reminder.setSource(com.dormrepair.remindme.enums.ReminderSource.USER_INPUT);
        reminder.setTimezone("Asia/Shanghai");

        return reminderRepository.save(reminder);
    }

    private ConfirmationCardResponse buildConfirmationCard(ConfirmationSession session, Map<String, Object> parsedData) {
        ConfirmationData data = ConfirmationData.builder()
                .sessionId(session.getSessionId())
                .title((String) parsedData.get("title"))
                .remindTime((String) parsedData.get("remindTime"))
                .priority((String) parsedData.getOrDefault("priority", "NORMAL"))
                .location((String) parsedData.get("location"))
                .advanceMinutes((Integer) parsedData.getOrDefault("advanceMinutes", 10))
                .confidence((Double) parsedData.getOrDefault("confidence", 0.95))
                .build();

        List<Action> actions = Arrays.asList(
                Action.builder().id("confirm").label("✅ 确认创建").style("primary").build(),
                Action.builder().id("edit").label("✏️ 修改").style("warning").build(),
                Action.builder().id("cancel").label("❌ 取消").style("danger").build()
        );

        return ConfirmationCardResponse.builder()
                .type("confirmation_card")
                .message("📋 我帮你解析了提醒信息，请确认：")
                .data(data)
                .actions(actions)
                .timeout(TIMEOUT_SECONDS)
                .expiresAt(session.getExpiresAt().format(ISO_FORMATTER))
                .build();
    }

    private void pushConfirmationCard(Long userId, ConfirmationCardResponse card) {
        messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/topic/confirmations", card);
    }

    private void pushUserMessage(Long userId, String type, Map<String, Object> data) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.putAll(data);
        messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/topic/confirmations", message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParsedData(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}