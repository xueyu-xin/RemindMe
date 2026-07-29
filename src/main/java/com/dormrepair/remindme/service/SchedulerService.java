package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.RecurringReminderRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final ReminderRepository reminderRepository;
    private final ConfirmationService confirmationService;
    private final WebSocketPushService webSocketPushService;
    private final WeatherService weatherService;
    private final LLMService llmService;
    private final UserService userService;
    private final RecurringReminderService recurringReminderService;
    private final RecurringReminderRepository recurringReminderRepository;
    private final com.dormrepair.remindme.repository.RecurringInstanceRepository recurringInstanceRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${amap.api.key:}")
    private String amapKey;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void scanPendingReminders() {
        log.debug("扫描到期提醒...");

        // 扫描逻辑说明：
        // - 单点提醒（endTime 为 null）：在 remindTime 时推送通知
        // - 时间段提醒（endTime 不为 null）：也在 remindTime（开始时间）时推送通知
        //   用户收到通知后，可以看到这是一个时间段提醒，并知道活动的结束时间
        // - 两种提醒的推送时机相同，都是基于 remindTime，因此现有逻辑无需特殊处理

        List<Reminder> pendingReminders = reminderRepository
                .findByStatusAndConfirmStatusAndExecutionStatusAndRemindTimeBefore(
                        ReminderStatus.PENDING,
                        ConfirmStatus.CONFIRMED,
                        ExecutionStatus.PENDING,
                        LocalDateTime.now());

        // 过滤需要推送的提醒（排除重复推送）
        List<Reminder> remindersToPush = pendingReminders.stream()
                .filter(reminder -> {
                    if (reminder.getLastConfirmRemindAt() != null) {
                        int intervalMinutes = reminder.getAdvanceMinutes() > 0 ? reminder.getAdvanceMinutes() : 10;
                        long minutesSinceLastPush = Duration.between(reminder.getLastConfirmRemindAt(), LocalDateTime.now()).toMinutes();
                        return minutesSinceLastPush >= intervalMinutes;
                    }
                    return true;
                })
                .toList();

        if (remindersToPush.isEmpty()) {
            return;
        }

        // 按城市分组，批量查询天气
        Map<String, List<Reminder>> remindersByCity = groupRemindersByCity(remindersToPush);
        log.info("到期提醒按城市分组: {} 个城市, {} 个提醒", remindersByCity.size(), remindersToPush.size());

        // 批量查询天气并生成建议
        Map<String, WeatherSuggestionCache> weatherCache = new HashMap<>();

        for (Map.Entry<String, List<Reminder>> entry : remindersByCity.entrySet()) {
            String city = entry.getKey();
            List<Reminder> cityReminders = entry.getValue();

            // 查询天气（同一城市只查一次）
            WeatherSuggestionCache cache = queryWeatherForCity(city);
            weatherCache.put(city, cache);

            // 为每个提醒生成建议（标题和地点不同，分别生成）
            for (Reminder reminder : cityReminders) {
                try {
                    String weatherSuggestion = null;
                    if (cache.success && cache.weatherText != null && cache.temperature != null) {
                        weatherSuggestion = llmService.generateWeatherSuggestion(
                                cache.weatherText,
                                cache.temperature,
                                reminder.getTitle(),
                                reminder.getLocation()
                        );
                    }

                    // 推送执行卡片（带天气建议）
                    confirmationService.pushExecuteCard(reminder, weatherSuggestion);

                    reminder.setLastConfirmRemindAt(LocalDateTime.now());
                    if (reminder.getConfirmAttempts() == 0) {
                        reminder.setConfirmAttempts(1);
                    } else {
                        reminder.setConfirmAttempts(reminder.getConfirmAttempts() + 1);
                    }
                    reminderRepository.save(reminder);

                    log.info("阶段二提醒已推送: id={}, title={}, attempt={}, weatherSuggestion={}",
                            reminder.getId(), reminder.getTitle(), reminder.getConfirmAttempts(), weatherSuggestion);

                } catch (Exception e) {
                    log.error("推送阶段二提醒失败: id={}", reminder.getId(), e);
                    // 降级处理：不带天气建议推送
                    try {
                        confirmationService.pushExecuteCard(reminder);
                    } catch (Exception ex) {
                        log.error("降级推送也失败: id={}", reminder.getId(), ex);
                    }
                }
            }
        }
    }

    /**
     * 地点类型枚举
     */
    private enum LocationType {
        CITY,      // 城市
        DISTRICT,  // 区/县
        POI,       // 兴趣点（如"图书馆"）
        OTHER      // 其他（无法识别）
    }

    /**
     * 按城市分组提醒（使用高德API判断地点类型）
     * <p>
     * 优先级：提醒的location（城市级别）> 用户默认城市 > 默认值"广州"
     * </p>
     */
    private Map<String, List<Reminder>> groupRemindersByCity(List<Reminder> reminders) {
        Map<String, List<Reminder>> groups = new HashMap<>();

        for (Reminder reminder : reminders) {
            String location = reminder.getLocation();
            String city = null;

            // 1. 获取用户默认城市作为兜底
            String userDefaultCity = getUserDefaultCity(reminder.getUserId());

            // 2. 如果location不为空，判断地点类型
            if (location != null && !location.isBlank()) {
                // 先处理"省-市-区"格式
                String normalizedLocation = location;
                if (location.contains("-")) {
                    String[] parts = location.split("-");
                    if (parts.length >= 2) {
                        normalizedLocation = parts[1]; // 取市
                    }
                }

                // 调用高德API判断地点类型
                LocationType type = identifyLocationType(normalizedLocation);

                if (type == LocationType.CITY || type == LocationType.DISTRICT) {
                    // 是城市或区县，直接使用
                    city = normalizedLocation;
                    log.info("地点{}识别为{}，使用该地点查询天气", location, type.name());
                } else {
                    // 是POI或其他，降级使用用户默认城市
                    city = userDefaultCity;
                    log.info("地点{}识别为{}，降级使用用户默认城市{}", location, type.name(), city);
                }
            } else {
                // 没有设置地点，使用用户默认城市
                city = userDefaultCity;
                log.info("提醒{}未设置地点，使用用户默认城市{}", reminder.getTitle(), city);
            }

            // 最终兜底：如果还是空，使用"广州"
            if (city == null || city.isBlank()) {
                city = "广州";
            }

            groups.computeIfAbsent(city, k -> new java.util.ArrayList<>()).add(reminder);
        }

        return groups;
    }

    /**
     * 获取用户默认城市
     */
    private String getUserDefaultCity(Long userId) {
        if (userId == null) {
            return "广州";
        }
        try {
            Map<String, Object> preferences = userService.getUserPreferences(userId);
            String city = (String) preferences.get("city");
            if (city != null && !city.isBlank()) {
                // 处理"省-市-区"格式
                if (city.contains("-")) {
                    String[] parts = city.split("-");
                    if (parts.length >= 2) {
                        return parts[1]; // 取市
                    }
                }
                return city;
            }
        } catch (Exception e) {
            log.warn("获取用户默认城市失败: userId={}, error={}", userId, e.getMessage());
        }
        return "广州";
    }

    /**
     * 调用高德地理编码API判断地点类型
     * <p>
     * 使用接口：https://restapi.amap.com/v3/geocode/geo
     * 解析返回结果中的 level 字段
     * </p>
     */
    private LocationType identifyLocationType(String location) {
        if (location == null || location.isBlank()) {
            return LocationType.OTHER;
        }

        // 如果未配置高德Key，直接返回OTHER（降级使用默认城市）
        if (amapKey == null || amapKey.isBlank()) {
            log.debug("未配置高德API Key，跳过地点类型识别");
            return LocationType.OTHER;
        }

        try {
            String encodedLocation = URLEncoder.encode(location, "UTF-8");
            String url = String.format(
                    "https://restapi.amap.com/v3/geocode/geo?address=%s&key=%s&output=json",
                    encodedLocation, amapKey
            );

            log.debug("调用高德地理编码API: location={}", location);
            String response = restTemplate.getForObject(url, String.class);

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                String status = root.has("status") ? root.get("status").asText() : "0";

                if ("1".equals(status)) {
                    JsonNode geocodes = root.get("geocodes");
                    if (geocodes != null && geocodes.isArray() && geocodes.size() > 0) {
                        JsonNode first = geocodes.get(0);
                        String level = first.has("level") ? first.get("level").asText() : "";
                        log.debug("地点{}的level: {}", location, level);

                        switch (level) {
                            case "city":
                                return LocationType.CITY;
                            case "district":
                                return LocationType.DISTRICT;
                            case "province":
                                // 省级也当作城市处理（取省会）
                                return LocationType.CITY;
                            case "poi":
                            case "street":
                            case "road":
                                return LocationType.POI;
                            default:
                                return LocationType.OTHER;
                        }
                    }
                } else {
                    log.debug("高德地理编码API返回失败: status={}", status);
                }
            }
        } catch (RestClientException e) {
            log.warn("调用高德地理编码API失败(网络错误): location={}, error={}", location, e.getMessage());
        } catch (Exception e) {
            log.warn("调用高德地理编码API失败: location={}, error={}", location, e.getMessage());
        }

        return LocationType.OTHER;
    }

    /**
     * 查询城市天气并缓存（带容错处理）
     */
    private WeatherSuggestionCache queryWeatherForCity(String city) {
        WeatherSuggestionCache cache = new WeatherSuggestionCache();

        // 容错1：城市为空，不查询
        if (city == null || city.isBlank()) {
            log.warn("城市名为空，跳过天气查询");
            cache.success = false;
            return cache;
        }

        try {
            log.info("查询城市天气: city={}", city);
            var weatherResponse = weatherService.getWeatherNow(city);

            if (weatherResponse != null && weatherResponse.getNow() != null) {
                cache.success = true;
                cache.weatherText = weatherResponse.getNow().getText();
                cache.temperature = weatherResponse.getNow().getTemp();
                log.info("天气查询成功: city={}, weather={}, temp={}", city, cache.weatherText, cache.temperature);
            } else {
                log.warn("天气查询结果为空: city={}", city);
                cache.success = false;
            }
        } catch (RestClientException e) {
            // 容错2：HTTP错误（如400、401、500等），记录日志并跳过
            log.warn("天气查询HTTP错误: city={}, error={}", city, e.getMessage());
            cache.success = false;
        } catch (Exception e) {
            log.error("查询城市天气失败: city={}, error={}", city, e.getMessage());
            cache.success = false;
        }

        return cache;
    }

    /**
     * 天气查询缓存
     */
    private static class WeatherSuggestionCache {
        boolean success = false;
        String weatherText;
        String temperature;
    }

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void checkConfirmTimeout() {
        log.debug("检查超时未确认提醒...");

        List<Reminder> timeoutReminders = reminderRepository
                .findByStatusAndConfirmStatusAndExecutionStatusAndConfirmAttemptsGreaterThanEqual(
                        ReminderStatus.PENDING,
                        ConfirmStatus.CONFIRMED,
                        ExecutionStatus.PENDING,
                        3);

        for (Reminder reminder : timeoutReminders) {
            try {
                reminder.setExecutionStatus(ExecutionStatus.DEFAULTED);
                reminder.setStatus(ReminderStatus.SENT);
                reminderRepository.save(reminder);
                log.info("提醒默认确认执行(3次稍后): id={}", reminder.getId());

                // 推送自动确认消息
                pushExecuteAutoConfirm(reminder);
            } catch (Exception e) {
                log.error("检查确认超时失败: id={}", reminder.getId(), e);
            }
        }
    }

    private void pushExecuteAutoConfirm(Reminder reminder) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "execute_auto_confirm");
            data.put("success", true);
            data.put("message", "⏰ 提醒已自动确认（3次稍后未响应）：" + reminder.getTitle());
            data.put("reminderId", reminder.getId());
            data.put("title", reminder.getTitle());

            webSocketPushService.pushToUser(reminder.getUserId(), "/topic/confirmations", data);
            log.info("推送阶段二自动确认消息: userId={}, reminderId={}", reminder.getUserId(), reminder.getId());
        } catch (Exception e) {
            log.error("推送阶段二自动确认消息失败: userId={}, error={}", reminder.getUserId(), e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void generateDailyRecurringInstances() {
        log.info("开始生成每日周期提醒实例...");
        try {
            LocalDate today = LocalDate.now();
            List<com.dormrepair.remindme.entity.RecurringReminder> activeReminders =
                    recurringReminderRepository.findByStatus("ACTIVE");

            int generated = 0;
            int skipped = 0;

            for (com.dormrepair.remindme.entity.RecurringReminder template : activeReminders) {
                try {
                    // 1. 检查今天是否在周期范围内
                    if (today.isBefore(template.getStartDate()) || today.isAfter(template.getEndDate())) {
                        continue;
                    }

                    // 2. 检查 last_generated_date 是否已更新为今天（避免重复）
                    if (template.getLastGeneratedDate() != null && template.getLastGeneratedDate().equals(today)) {
                        skipped++;
                        continue;
                    }

                    // 3. 检查实例表中是否已存在
                    if (recurringInstanceRepository.existsByRecurringIdAndRemindDate(template.getId(), today)) {
                        skipped++;
                        template.setLastGeneratedDate(today);
                        recurringReminderRepository.save(template);
                        continue;
                    }

                    // 4. 为今天生成实例
                    Map<String, Object> result = recurringReminderService.generateInstanceForDate(template, today);
                    String status = (String) result.get("status");

                    // 5. 更新 last_generated_date = 今天
                    template.setLastGeneratedDate(today);
                    recurringReminderRepository.save(template);

                    if ("success".equals(status)) {
                        generated++;
                    } else {
                        log.warn("周期实例生成状态: recurringId={}, date={}, status={}",
                                template.getId(), today, status);
                    }
                } catch (Exception e) {
                    log.error("生成周期实例失败: recurringId={}, error={}", template.getId(), e.getMessage());
                }
            }

            log.info("每日周期实例生成完成: 生成 {} 条，跳过 {} 条", generated, skipped);
        } catch (Exception e) {
            log.error("每日周期实例生成失败: {}", e.getMessage(), e);
        }
    }
}
