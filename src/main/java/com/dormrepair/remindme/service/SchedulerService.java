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

        // 按城市分组（LLM分区+选候选，已拿到coord），批量查询天气
        Map<String, CityWeatherQuery> remindersByCity = groupRemindersByCity(remindersToPush);
        log.info("到期提醒扫描: 候选{}个, 符合触发条件{}个, 按城市分组{}个", candidates.size(), remindersToPush.size(), remindersByCity.size());

        for (Map.Entry<String, CityWeatherQuery> entry : remindersByCity.entrySet()) {
            String key = entry.getKey();
            CityWeatherQuery query = entry.getValue();
            List<Reminder> cityReminders = query.reminders;

            // 省级/乱码/无城市：不查天气，直接推送执行卡片
            if ("__NO_WEATHER__".equals(key)) {
                for (Reminder reminder : cityReminders) {
                    try {
                        confirmationService.pushExecuteCard(reminder);
                        reminder.setLastConfirmRemindAt(LocalDateTime.now());
                        reminder.setStatus(ReminderStatus.REMINDED);
                        reminderRepository.save(reminder);
                        log.info("阶段二提醒已推送(不查天气): id={}, title={}", reminder.getId(), reminder.getTitle());
                    } catch (Exception e) {
                        log.error("推送阶段二提醒失败(不查天气): id={}", reminder.getId(), e);
                    }
                }
                continue;
            }

            // 用LLM选好的coord直接查天气（不再searchCity）
            WeatherSuggestionCache cache = queryWeatherForCity(query.coord, query.displayName);

            // 每个提醒的标题不同，分别生成建议
            for (Reminder reminder : cityReminders) {
                try {
                    String weatherSuggestion = null;
                    if (cache.success && cache.weatherText != null && cache.temperature != null) {
                        // 优先使用提醒话术模板（含 {weather}/{title}/{memory} 占位符），无模板则降级为 LLM 天气建议
                        String template = reminder.getNoteTemplate();
                        if (template != null && !template.isBlank()) {
                            String weatherEmoji = getWeatherEmoji(cache.weatherText);
                            String weatherDesc = weatherEmoji + cache.weatherText + "，" + cache.temperature + "°C";
                            String memoryNote = reminder.getNote() != null ? reminder.getNote() : "";
                            weatherSuggestion = template
                                    .replace("{weather}", weatherDesc)
                                    .replace("{title}", reminder.getTitle())
                                    .replace("{memory}", memoryNote);
                            // 清理残留的未匹配占位符
                            weatherSuggestion = weatherSuggestion.replaceAll("\\{[^}]*\\}", "");
                        } else {
                            weatherSuggestion = llmService.generateWeatherSuggestion(
                                    cache.weatherText,
                                    cache.temperature,
                                    reminder.getTitle(),
                                    query.displayName
                            );
                        }
                    }

                    confirmationService.pushExecuteCard(reminder, weatherSuggestion);
                    reminder.setLastConfirmRemindAt(LocalDateTime.now());
                    reminder.setStatus(ReminderStatus.REMINDED);
                    reminderRepository.save(reminder);

                    log.info("阶段二提醒已推送: id={}, title={}, remindTime={}, advanceMinutes={}, weatherSuggestion={}",
                            reminder.getId(), reminder.getTitle(), reminder.getRemindTime(),
                            reminder.getAdvanceMinutes(), weatherSuggestion);

                } catch (Exception e) {
                    log.error("推送阶段二提醒失败: id={}", reminder.getId(), e);
                    try {
                        confirmationService.pushExecuteCard(reminder);
                    } catch (Exception ex) {
                        log.error("降级推送也失败: id={}", reminder.getId(), ex);
                    }
                }
            }
        }
    }

    // 城市天气查询分组（LLM分区+选候选后，存coord+displayName+reminders）
    private static class CityWeatherQuery {
        String coord;        // "经度,纬度"
        String displayName;  // 标准城市名
        List<Reminder> reminders;
    }

    // 按城市分组提醒（LLM分区+选候选，直接拿coord，不问用户）
    private Map<String, CityWeatherQuery> groupRemindersByCity(List<Reminder> reminders) {
        Map<String, CityWeatherQuery> groups = new HashMap<>();

        for (Reminder reminder : reminders) {
            String location = reminder.getLocation();
            String coord = null;
            String displayName = null;

            // 第一步：判断location类型（空地点代码直接判POI，不调LLM；非空交给LLM判断）
            String type;
            if (location == null || location.isBlank()) {
                type = "POI";
                log.info("地点为空，直接按POI处理（用默认城市）");
            } else {
                type = llmService.judgeLocationType(location);
                log.info("地点{}识别为{}", location, type);
            }

            if ("GARBAGE".equals(type) || "PROVINCE".equals(type)) {
                // 乱码/省级：不查天气，不获取偏好、不searchCity
                addToNoWeatherGroup(groups, reminder);
                continue;
            }

            if ("POI".equals(type)) {
                // POI/未填写：获取默认城市，用默认城市走城市搜索
                String userDefaultCity = getUserDefaultCity(reminder.getUserId());
                if (userDefaultCity == null || userDefaultCity.isBlank()) {
                    log.info("POI/未填写但默认城市为空，不查天气");
                    addToNoWeatherGroup(groups, reminder);
                    continue;
                }
                location = userDefaultCity;
                log.info("地点识别为POI/未填写，降级使用默认城市{}", location);
            }

            // CITY或POI降级后：searchCity拿候选 → LLM选
            Map<String, Object> resolved = llmService.resolveReminderLocation(
                    location,
                    getCandidatesForLocation(location));
            String resolvedType = (String) resolved.get("type");
            if ("CITY".equals(resolvedType)) {
                coord = (String) resolved.get("coord");
                displayName = (String) resolved.get("resolvedCity");
                log.info("地点{}使用{} coord={}", location, displayName, coord);
            } else {
                log.info("地点{}LLM未选出候选(type={})，不查天气", location, resolvedType);
                addToNoWeatherGroup(groups, reminder);
                continue;
            }

            if (coord == null || coord.isBlank()) {
                log.info("提醒{}无法确定查询坐标，不查天气", reminder.getTitle());
                addToNoWeatherGroup(groups, reminder);
                continue;
            }

            // 按displayName分组（同城市复用coord，只查一次天气）
            final String finalCoord = coord;
            final String finalDisplayName = displayName;
            String groupKey = displayName != null ? displayName : coord;
            CityWeatherQuery query = groups.computeIfAbsent(groupKey, k -> {
                CityWeatherQuery q = new CityWeatherQuery();
                q.coord = finalCoord;
                q.displayName = finalDisplayName;
                q.reminders = new java.util.ArrayList<>();
                return q;
            });
            query.reminders.add(reminder);
        }

        return groups;
    }

    private void addToNoWeatherGroup(Map<String, CityWeatherQuery> groups, Reminder reminder) {
        CityWeatherQuery query = groups.computeIfAbsent("__NO_WEATHER__", k -> {
            CityWeatherQuery q = new CityWeatherQuery();
            q.reminders = new java.util.ArrayList<>();
            return q;
        });
        query.reminders.add(reminder);
    }

    // 获取location的城市搜索候选（带坐标，传给LLM选）
    private java.util.List<java.util.Map<String, Object>> getCandidatesForLocation(String location) {
        if (location == null || location.isBlank()) return java.util.Collections.emptyList();
        try {
            Map<String, Object> cityResult = locationService.searchCity(location);
            if (!Boolean.TRUE.equals(cityResult.get("success"))) {
                return java.util.Collections.emptyList();
            }
            // 唯一结果：包装成单元素列表
            if (!cityResult.containsKey("candidates")) {
                java.util.Map<String, Object> single = new java.util.HashMap<>();
                single.put("displayName", cityResult.get("displayName"));
                single.put("latitude", cityResult.get("latitude"));
                single.put("longitude", cityResult.get("longitude"));
                return java.util.List.of(single);
            }
            // 多候选：直接返回
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> candidates = (java.util.List<java.util.Map<String, Object>>) cityResult.get("candidates");
            return candidates;
        } catch (Exception e) {
            log.warn("获取候选失败: location={}, error={}", location, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    // 获取用户默认城市（未设置返回 null）
    private String getUserDefaultCity(Long userId) {
        if (userId == null) {
            return null;
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
        return null;
    }

    // 查询城市天气并缓存（coord已是LLM选好的，直接查天气，不再searchCity）
    private WeatherSuggestionCache queryWeatherForCity(String coord, String displayName) {
        WeatherSuggestionCache cache = new WeatherSuggestionCache();

        if (coord == null || coord.isBlank()) {
            log.warn("坐标为空，跳过天气查询");
            cache.success = false;
            return cache;
        }

        try {
            log.info("查询城市天气: displayName={}, coord={}", displayName, coord);
            var weatherResponse = weatherService.getWeatherNow(coord);

            if (weatherResponse != null && weatherResponse.getNow() != null) {
                cache.success = true;
                cache.weatherText = weatherResponse.getNow().getText();
                cache.temperature = weatherResponse.getNow().getTemp();
                cache.resolvedCity = displayName;
                log.info("天气查询成功: displayName={}, weather={}, temp={}", displayName, cache.weatherText, cache.temperature);
            } else {
                log.warn("天气查询结果为空: coord={}", coord);
                cache.success = false;
            }
        } catch (Exception e) {
            log.error("查询城市天气失败: coord={}, error={}", coord, e.getMessage());
            cache.success = false;
        }

        return cache;
    }

    // 天气查询缓存
    private static class WeatherSuggestionCache {
        boolean success = false;
        String weatherText;
        String temperature;
        String resolvedCity;
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

    // 根据天气文字描述返回对应 emoji，用于话术模板的 {weather} 占位符替换
    private String getWeatherEmoji(String weatherText) {
        if (weatherText == null) return "🌤️";
        if (weatherText.contains("雨")) return "🌧️";
        if (weatherText.contains("雪")) return "❄️";
        if (weatherText.contains("晴")) return "☀️";
        if (weatherText.contains("云")) return "⛅";
        if (weatherText.contains("雾")) return "🌫️";
        return "🌤️";
    }
}
