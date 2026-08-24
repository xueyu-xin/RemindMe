package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.RecurringReminderRepository;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 定时任务服务
// 每分钟扫描到期提醒并推送、每小时检查超时未确认的提醒、每日生成周期提醒实例
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    // 各业务依赖：提醒仓储、确认卡片、WebSocket 推送、天气、LLM、用户偏好、周期提醒
    private final ReminderRepository reminderRepository;
    private final ConfirmationService confirmationService;
    private final WeatherService weatherService;
    private final LLMService llmService;
    private final UserService userService;
    private final RecurringReminderService recurringReminderService;
    private final RecurringReminderRepository recurringReminderRepository;
    private final com.dormrepair.remindme.repository.RecurringInstanceRepository recurringInstanceRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final PeriodService periodService;
    private final UserRepository userRepository;
    private final LocationService locationService;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void scanPendingReminders() {
        log.debug("扫描到期提醒...");

        // 扫描逻辑说明：
        // - 使用 advanceMinutes 字段计算实际触发时间：(remindTime - advanceMinutes) <= now
        // - 单点提醒（endTime 为 null）和时间段提醒（endTime 不为 null）推送时机一致
        // - advanceMinutes 默认 10 分钟，可由用户设置

        LocalDateTime now = LocalDateTime.now();
        // 使用宽泛时间窗口查询（now + 1天），覆盖最大可能的 advanceMinutes
        LocalDateTime upperBound = now.plusDays(1);

        List<Reminder> candidates = reminderRepository
                .findByStatusAndConfirmStatusAndExecutionStatusAndRemindTimeBefore(
                        ReminderStatus.PENDING,
                        ConfirmStatus.CONFIRMED,
                        ExecutionStatus.PENDING,
                        upperBound);

        // 按 advanceMinutes 精确过滤：实际触发时间 = remindTime - advanceMinutes
        List<Reminder> pendingReminders = candidates.stream()
                .filter(reminder -> {
                    int advance = reminder.getAdvanceMinutes() != null ? reminder.getAdvanceMinutes() : 10;
                    // 防止 advanceMinutes 为 0 或负数时 NPE 或逻辑错误
                    if (advance <= 0) advance = 0;
                    LocalDateTime triggerTime = reminder.getRemindTime().minusMinutes(advance);
                    return !triggerTime.isAfter(now); // triggerTime <= now
                })
                .toList();

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
        log.info("到期提醒扫描: 候选{}个, 符合触发条件{}个, 按城市分组{}个", candidates.size(), remindersToPush.size(), remindersByCity.size());

        // 批量查询天气并生成建议
        Map<String, WeatherSuggestionCache> weatherCache = new HashMap<>();

        for (Map.Entry<String, List<Reminder>> entry : remindersByCity.entrySet()) {
            String city = entry.getKey();
            List<Reminder> cityReminders = entry.getValue();

            // 省级地点不查天气，直接推送执行卡片
            if ("__NO_WEATHER__".equals(city)) {
                for (Reminder reminder : cityReminders) {
                    try {
                        confirmationService.pushExecuteCard(reminder);
                        reminder.setLastConfirmRemindAt(LocalDateTime.now());
                        reminder.setStatus(ReminderStatus.REMINDED);
                        reminderRepository.save(reminder);
                        log.info("阶段二提醒已推送(省级不查天气): id={}, title={}", reminder.getId(), reminder.getTitle());
                    } catch (Exception e) {
                        log.error("推送阶段二提醒失败(省级): id={}", reminder.getId(), e);
                    }
                }
                continue;
            }

            // 同一城市只查一次天气
            WeatherSuggestionCache cache = queryWeatherForCity(city);
            weatherCache.put(city, cache);

            // 每个提醒的标题和地点不同，分别生成建议
            for (Reminder reminder : cityReminders) {
                try {
                    String weatherSuggestion = null;
                    if (cache.success && cache.weatherText != null && cache.temperature != null) {
                        // 用实际查询天气的城市作为建议中的地点，而非提醒原始 location
                        weatherSuggestion = llmService.generateWeatherSuggestion(
                                cache.weatherText,
                                cache.temperature,
                                reminder.getTitle(),
                                city
                        );
                    }

                    // 推送执行卡片（带天气建议）
                    confirmationService.pushExecuteCard(reminder, weatherSuggestion);

                    reminder.setLastConfirmRemindAt(LocalDateTime.now());
                    // 推送后状态改为 REMINDED，避免被调度器重复扫描推送
                    reminder.setStatus(ReminderStatus.REMINDED);
                    reminderRepository.save(reminder);

                    log.info("阶段二提醒已推送: id={}, title={}, remindTime={}, advanceMinutes={}, weatherSuggestion={}",
                            reminder.getId(), reminder.getTitle(), reminder.getRemindTime(),
                            reminder.getAdvanceMinutes(), weatherSuggestion);

                } catch (Exception e) {
                    log.error("推送阶段二提醒失败: id={}", reminder.getId(), e);
                    // 降级：不带天气建议再推一次
                    try {
                        confirmationService.pushExecuteCard(reminder);
                    } catch (Exception ex) {
                        log.error("降级推送也失败: id={}", reminder.getId(), ex);
                    }
                }
            }
        }
    }

    // 按城市分组提醒
    // 优先级：提醒的 location（城市级别）> 用户默认城市 > 兜底值"广州"
    private Map<String, List<Reminder>> groupRemindersByCity(List<Reminder> reminders) {
        Map<String, List<Reminder>> groups = new HashMap<>();

        for (Reminder reminder : reminders) {
            String location = reminder.getLocation();
            String city = null;

            // 1. 获取用户默认城市作为兜底
            String userDefaultCity = getUserDefaultCity(reminder.getUserId());

            // 2. 如果 location 不为空，判断地点类型
            if (location != null && !location.isBlank()) {
                // 用和风城市搜索判断地点能否查天气
                String weatherLocation = resolveWeatherLocation(location);

                if (weatherLocation == null) {
                    // 省级输入，不查天气，跳过分组
                    log.info("地点{}识别为省级，不查天气", location);
                    groups.computeIfAbsent("__NO_WEATHER__", k -> new java.util.ArrayList<>()).add(reminder);
                    continue;
                } else if ("__DEFAULT__".equals(weatherLocation)) {
                    // POI 或其他，降级使用用户默认城市
                    city = userDefaultCity;
                    log.info("地点{}识别为POI，降级使用用户默认城市{}", location, city);
                } else {
                    // 市级或区级，用原始输入查天气
                    city = weatherLocation;
                    log.info("地点{}识别为城市/区县，使用该地点查询天气", location);
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

    // 判断地点能否查天气，返回值：null=省级不查、__DEFAULT__=POI降级默认城市、其他=用原始输入查天气
    private String resolveWeatherLocation(String location) {
        if (location == null || location.isBlank()) return "__DEFAULT__";

        try {
            List<com.dormrepair.remindme.dto.response.CitySearchResult> results = locationService.searchCities(location);
            if (results.isEmpty()) return "__DEFAULT__";  // POI 搜不到

            com.dormrepair.remindme.dto.response.CitySearchResult first = results.get(0);
            // 去掉省/市后缀后的输入，用于和 adm1 比较
            String normalizedInput = location.replaceAll("[省市]$", "");
            String adm1Normalized = first.getAdm1().replaceAll("[省市]$", "");

            // 判断是否只输入了省：输入去后缀等于 adm1 去后缀，且 adm1 以"省"结尾（排除直辖市和特别行政区）
            if (normalizedInput.equals(adm1Normalized) && first.getAdm1().endsWith("省")) {
                return null;  // 只输入了省，不查天气
            }

            // 市级或区级，用原始输入查天气
            return location;
        } catch (Exception e) {
            log.warn("判断地点类型失败: location={}, error={}", location, e.getMessage());
            return "__DEFAULT__";  // 异常时降级用默认城市
        }
    }

    // 获取用户默认城市
    private String getUserDefaultCity(Long userId) {
        if (userId == null) {
            return "广州";
        }
        try {
            Map<String, Object> preferences = userService.getUserPreferences(userId);
            String city = (String) preferences.get("city");
            if (city != null && !city.isBlank()) {
                return city;
            }
        } catch (Exception e) {
            log.warn("获取用户默认城市失败: userId={}, error={}", userId, e.getMessage());
        }
        return "广州";
    }

    // 查询城市天气并缓存（带容错处理）
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
            // 容错2：HTTP 错误（如 400、401、500），记录日志并跳过
            log.warn("天气查询HTTP错误: city={}, error={}", city, e.getMessage());
            cache.success = false;
        } catch (Exception e) {
            log.error("查询城市天气失败: city={}, error={}", city, e.getMessage());
            cache.success = false;
        }

        return cache;
    }

    // 天气查询缓存
    private static class WeatherSuggestionCache {
        boolean success = false;
        String weatherText;
        String temperature;
    }

    // 每小时执行：检查连续多次未响应的提醒，仅记录日志，不自动确认
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void checkConfirmTimeout() {
        log.debug("检查超时未确认提醒（仅记录日志）...");

        List<Reminder> timeoutReminders = reminderRepository
                .findByStatusAndConfirmStatusAndExecutionStatusAndConfirmAttemptsGreaterThanEqual(
                        ReminderStatus.PENDING,
                        ConfirmStatus.CONFIRMED,
                        ExecutionStatus.PENDING,
                        3);

        for (Reminder reminder : timeoutReminders) {
            log.warn("提醒长时间未响应(非自动确认): id={}, title={}, confirmAttempts={}",
                    reminder.getId(), reminder.getTitle(), reminder.getConfirmAttempts());
        }

        if (!timeoutReminders.isEmpty()) {
            log.info("阶段二提醒超时检查完成: {} 条提醒长时间未响应，保持待定状态", timeoutReminders.size());
        }
    }

    // 每日凌晨 1 点执行：遍历所有 ACTIVE 周期模板，为当天生成提醒实例
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

    // 每天凌晨 0:30 执行：遍历所有开启经期提醒的女性用户，检查是否需要创建经期提醒
    @Scheduled(cron = "0 30 0 * * ?")
    @Transactional
    public void checkPeriodReminders() {
        log.info("开始检查经期提醒...");
        try {
            userRepository.findAll().forEach(user -> {
                try {
                    periodService.checkPeriodReminder(user);
                } catch (Exception e) {
                    log.error("经期提醒检查失败: userId={}, error={}", user.getId(), e.getMessage());
                }
            });
            log.info("经期提醒检查完成");
        } catch (Exception e) {
            log.error("经期提醒检查失败: {}", e.getMessage(), e);
        }
    }
}
