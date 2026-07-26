package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.config.WeatherConfig;
import com.dormrepair.remindme.dto.weather.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.zip.GZIPInputStream;

/**
 * 天气服务
 * <p>
 * 调用新版和风天气API，支持实时天气、天气预报、空气质量、天气预警
 * 使用原生 HttpURLConnection + GZIP 解压，避免 RestTemplate 的 gzip 问题
 * 通过和风天气城市搜索API（/geo/v2/city/lookup）将中文城市名转换为经纬度
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherConfig weatherConfig;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    /**
     * 城市名 → 经纬度缓存（线程安全）
     * 缓存键：城市名（如"广州"）
     * 缓存值：经纬度（如"113.2644,23.1291"，格式为经度,纬度）
     */
    private final Map<String, String> coordCache = new ConcurrentHashMap<>();

    // ==================== 城市名映射表 ====================

    /**
     * 省份 → 省会映射表
     */
    private static final Map<String, String> PROVINCE_CAPITAL_MAP = Map.ofEntries(
            Map.entry("广东", "广州"), Map.entry("浙江", "杭州"), Map.entry("江苏", "南京"),
            Map.entry("山东", "济南"), Map.entry("河南", "郑州"), Map.entry("湖北", "武汉"),
            Map.entry("湖南", "长沙"), Map.entry("四川", "成都"), Map.entry("陕西", "西安"),
            Map.entry("河北", "石家庄"), Map.entry("山西", "太原"), Map.entry("辽宁", "沈阳"),
            Map.entry("吉林", "长春"), Map.entry("黑龙江", "哈尔滨"), Map.entry("安徽", "合肥"),
            Map.entry("福建", "福州"), Map.entry("江西", "南昌"), Map.entry("海南", "海口"),
            Map.entry("贵州", "贵阳"), Map.entry("云南", "昆明"), Map.entry("甘肃", "兰州"),
            Map.entry("青海", "西宁"), Map.entry("台湾", "台北"), Map.entry("内蒙古", "呼和浩特"),
            Map.entry("广西", "南宁"), Map.entry("西藏", "拉萨"), Map.entry("宁夏", "银川"),
            Map.entry("新疆", "乌鲁木齐")
    );

    /**
     * 自治区 → 首府映射表
     */
    private static final Map<String, String> REGION_CAPITAL_MAP = Map.ofEntries(
            Map.entry("广西壮族", "南宁"), Map.entry("内蒙古", "呼和浩特"),
            Map.entry("西藏", "拉萨"), Map.entry("宁夏回族", "银川"),
            Map.entry("新疆维吾尔", "乌鲁木齐")
    );

    // ==================== 城市名标准化 ====================

    /**
     * 标准化城市名，处理多种格式
     * <p>
     * 处理顺序（优先级从高到低）：
     * 1. null/空 → 返回"广州"
     * 2. "省-市-区"格式 → 提取城市部分
     * 3. 直辖市（北京/上海/天津/重庆）→ 去掉"市"后缀
     * 4. 以"市"结尾 → 去掉"市"后缀
     * 5. 以"省"结尾 → 转换为省会城市
     * 6. 以"自治区"结尾 → 转换为首府城市
     * 7. 包含"香港"/"澳门" → 返回对应城市名
     * 8. 其他情况 → 返回原始值
     * </p>
     *
     * @param location 原始城市名
     * @return 标准化后的城市名
     */
    private String normalizeCityName(String location) {
        // 1. null/空 → 返回"广州"
        if (location == null || location.isBlank()) {
            return "广州";
        }

        String normalized = location.trim();

        // 2. "省-市-区"格式 → 提取城市部分
        if (normalized.contains("-")) {
            String[] parts = normalized.split("-");
            if (parts.length >= 2) {
                normalized = parts[1]; // 取市
            }
        }

        // 3. 直辖市 → 去掉"市"后缀
        String[] municipalities = {"北京", "上海", "天津", "重庆"};
        for (String m : municipalities) {
            if (normalized.startsWith(m) && normalized.length() > m.length()) {
                log.debug("直辖市处理: {} → {}", location, m);
                return m;
            }
        }

        // 4. 以"市"结尾 → 去掉"市"后缀
        if (normalized.endsWith("市") && normalized.length() > 1) {
            String result = normalized.substring(0, normalized.length() - 1);
            log.debug("去掉'市'后缀: {} → {}", location, result);
            return result;
        }

        // 5. 以"省"结尾 → 转换为省会城市
        if (normalized.endsWith("省")) {
            String province = normalized.substring(0, normalized.length() - 1);
            String capital = PROVINCE_CAPITAL_MAP.getOrDefault(province, province);
            log.debug("省份转换: {} → {}", location, capital);
            return capital;
        }

        // 6. 以"自治区"结尾 → 转换为首府城市
        if (normalized.endsWith("自治区")) {
            String region = normalized.substring(0, normalized.length() - 3);
            String capital = REGION_CAPITAL_MAP.getOrDefault(region, region);
            log.debug("自治区转换: {} → {}", location, capital);
            return capital;
        }

        // 7. 特别行政区
        if (normalized.contains("香港")) {
            log.debug("香港特别行政区处理: {} → 香港", location);
            return "香港";
        }
        if (normalized.contains("澳门")) {
            log.debug("澳门特别行政区处理: {} → 澳门", location);
            return "澳门";
        }

        // 8. 其他情况 → 返回原始值
        return normalized;
    }

    // ==================== 和风城市搜索 ====================

    /**
     * 判断字符串是否为经纬度格式（如 "113.2644,23.1291"）
     */
    private boolean isCoordinate(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        // 经纬度格式：数字,数字（如 113.2644,23.1291）
        return location.matches("^[-+]?\\d+\\.?\\d*,[-+]?\\d+\\.?\\d*$");
    }

    /**
     * 使用和风天气城市搜索API查询城市信息
     * <p>
     * API地址：/geo/v2/city/lookup
     * 返回格式：{"code":"200","location":[{"name":"汕头","id":"101280501","lat":"23.3541","lon":"116.6819","type":"city"}]}
     * </p>
     *
     * @param cityName 城市名（如"汕头"）
     * @return 经纬度字符串（如"116.6819,23.3541"，格式为经度,纬度），查询失败返回null
     */
    private String searchCity(String cityName) {
        try {
            String url = buildUrl("/geo/v2/city/lookup")
                    + "?location=" + encode(cityName)
                    + "&range=cn"
                    + "&number=1";

            log.debug("调用和风城市搜索API: city={}, url={}", cityName, url);
            String response = httpGet(url);

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                String code = root.has("code") ? root.get("code").asText() : "";

                if ("200".equals(code)) {
                    JsonNode locations = root.get("location");
                    if (locations != null && locations.isArray() && locations.size() > 0) {
                        JsonNode first = locations.get(0);
                        String lon = first.has("lon") ? first.get("lon").asText() : "";
                        String lat = first.has("lat") ? first.get("lat").asText() : "";
                        String name = first.has("name") ? first.get("name").asText() : "";
                        String type = first.has("type") ? first.get("type").asText() : "";

                        if (lon != null && !lon.isBlank() && lat != null && !lat.isBlank()) {
                            String coord = lon + "," + lat;
                            log.info("和风城市搜索成功: {} → {} (name={}, type={})", cityName, coord, name, type);
                            return coord;
                        }
                    }
                } else {
                    log.warn("和风城市搜索API返回失败: city={}, code={}", cityName, code);
                }
            }
        } catch (Exception e) {
            log.warn("调用和风城市搜索API失败: city={}, error={}", cityName, e.getMessage());
        }

        return null;
    }

    /**
     * 使用和风天气城市搜索API将城市名转换为经纬度
     * <p>
     * 和风城市搜索API返回的经纬度格式为"经度,纬度"（如"116.6819,23.3541"），可直接用于天气查询
     * </p>
     *
     * @param cityName 城市名（如"汕头"）
     * @return 经纬度字符串（如"116.6819,23.3541"），转换失败返回原城市名
     */
    private String resolveLocationToCoord(String cityName) {
        // 1. 已为经纬度格式，直接返回
        if (isCoordinate(cityName)) {
            log.debug("已是经纬度格式，直接返回: {}", cityName);
            return cityName;
        }

        // 2. 城市名为空，返回"广州"
        if (cityName == null || cityName.isBlank()) {
            return "广州";
        }

        // 3. 查缓存
        String cached = coordCache.get(cityName);
        if (cached != null) {
            log.debug("缓存命中: {} → {}", cityName, cached);
            return cached;
        }

        // 4. 调用和风城市搜索API
        String coord = searchCity(cityName);

        if (coord != null && !coord.isBlank()) {
            // 缓存结果
            coordCache.put(cityName, coord);
            return coord;
        }

        // 5. 转换失败，返回原城市名（降级处理）
        log.warn("城市名转换失败，使用原城市名: {}", cityName);
        return cityName;
    }

    // ==================== 核心接口 ====================

    /**
     * 获取实时天气
     */
    public WeatherNowResponse getWeatherNow(String location) {
        log.info("查询实时天气: location={}", location);

        // 标准化城市名
        String normalizedLocation = normalizeCityName(location);
        log.info("标准化后城市名: {} → {}", location, normalizedLocation);

        // 将城市名转换为经纬度
        String coord = resolveLocationToCoord(normalizedLocation);
        log.info("经纬度转换: {} → {}", normalizedLocation, coord);

        String url = buildUrl("/v7/weather/now")
                + "?location=" + encode(coord)
                + "&lang=zh";

        String json = httpGet(url);
        WeatherNowResponse response = parseJson(json, WeatherNowResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("实时天气查询成功: location={}, coord={}, temp={}", location, coord, response.getNow().getTemp());
            return response;
        }

        throw new RuntimeException("实时天气查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    /**
     * 获取天气预报
     */
    public WeatherForecastResponse getWeatherForecast(String location, Integer days) {
        log.info("查询天气预报: location={}, days={}", location, days);

        // 标准化城市名
        String normalizedLocation = normalizeCityName(location);
        log.info("标准化后城市名: {} → {}", location, normalizedLocation);

        // 将城市名转换为经纬度
        String coord = resolveLocationToCoord(normalizedLocation);
        log.info("经纬度转换: {} → {}", normalizedLocation, coord);

        int forecastDays = days != null ? days : 7;
        String daysPath = switch (forecastDays) {
            case 3 -> "3d";
            case 7 -> "7d";
            case 10 -> "10d";
            case 15 -> "15d";
            case 30 -> "30d";
            default -> "7d";
        };

        String url = buildUrl("/v7/weather/" + daysPath)
                + "?location=" + encode(coord)
                + "&lang=zh";

        String json = httpGet(url);
        WeatherForecastResponse response = parseJson(json, WeatherForecastResponse.class);

        if (response != null && "200".equals(response.getCode())) {
            log.info("天气预报查询成功: location={}, coord={}, days={}, count={}", location, coord, days, response.getDaily().size());
            return response;
        }

        throw new RuntimeException("天气预报查询失败: code=" + (response != null ? response.getCode() : "null"));
    }

    /**
     * 获取空气质量
     */
    public AirQualityResponse getAirQuality(Double latitude, Double longitude) {
        log.info("查询空气质量: lat={}, lon={}", latitude, longitude);

        String url = buildUrl("/airquality/v1/current/" + latitude + "/" + longitude)
                + "?lang=zh";

        String json = httpGet(url);
        AirQualityResponse response = parseJson(json, AirQualityResponse.class);

        if (response != null && response.getIndexes() != null && !response.getIndexes().isEmpty()) {
            log.info("空气质量查询成功: lat={}, lon={}, aqi={}", latitude, longitude, response.getIndexes().get(0).getAqi());
            return response;
        }

        throw new RuntimeException("空气质量查询失败");
    }

    /**
     * 获取天气预警
     */
    public WeatherAlertResponse getWeatherAlert(Double latitude, Double longitude) {
        log.info("查询天气预警: lat={}, lon={}", latitude, longitude);

        String url = buildUrl("/weatheralert/v1/current/" + latitude + "/" + longitude)
                + "?lang=zh";

        String json = httpGet(url);
        WeatherAlertResponse response = parseJson(json, WeatherAlertResponse.class);

        if (response != null) {
            int alertCount = response.getAlerts() != null ? response.getAlerts().size() : 0;
            log.info("天气预警查询成功: lat={}, lon={}, alertCount={}", latitude, longitude, alertCount);
            return response;
        }

        throw new RuntimeException("天气预警查询失败");
    }

    // ==================== 位置解析 ====================

    /**
     * 解析位置参数，优先级：经纬度 > location > 用户偏好城市
     * <p>
     * 和风天气API经纬度格式：latitude,longitude（如 23.1291,113.2644）
     * </p>
     *
     * @param userId    用户ID（用于获取默认城市）
     * @param latitude  纬度（可选）
     * @param longitude 经度（可选）
     * @param location  城市名（可选）
     * @return 可用于查询的位置字符串（城市名或经纬度）
     */
    private String resolveLocation(Long userId, Double latitude, Double longitude, String location) {
        // 优先级1：经纬度
        if (latitude != null && longitude != null) {
            // 和风天气API要求 location 格式为"经度,纬度"（longitude,latitude）
            String lonLat = longitude + "," + latitude;
            log.info("使用经纬度查询: lat={}, lon={}, locationStr={}", latitude, longitude, lonLat);
            return lonLat;
        }

        // 优先级2：location参数（城市名）
        if (location != null && !location.isBlank()) {
            // 如果是"省-市-区"格式，提取市
            String city = location;
            if (city.contains("-")) {
                String[] parts = city.split("-");
                if (parts.length >= 2) {
                    city = parts[1]; // 取市
                }
            }
            log.info("使用城市名查询: location={}, city={}", location, city);
            return city;
        }

        // 优先级3：用户偏好城市
        if (userId != null) {
            try {
                Map<String, Object> preferences = userService.getUserPreferences(userId);
                String userCity = (String) preferences.get("city");
                if (userCity != null && !userCity.isBlank()) {
                    // 如果是"省-市-区"格式，提取市
                    String city = userCity;
                    if (city.contains("-")) {
                        String[] parts = city.split("-");
                        if (parts.length >= 2) {
                            city = parts[1]; // 取市
                        }
                    }
                    log.info("使用用户默认城市查询: userId={}, city={}", userId, city);
                    return city;
                }
            } catch (Exception e) {
                log.warn("获取用户偏好城市失败: userId={}, error={}", userId, e.getMessage());
            }
        }

        // 默认：广州
        log.info("未提供位置信息，使用默认城市: 广州");
        return "广州";
    }

    // ==================== Agent工具 ====================

    @AgentTool(name = "query_weather_now", description = "查询实时天气，用户询问当前天气时调用。优先使用经纬度查询，其次使用城市名，最后使用用户默认城市")
    public WeatherNowResponse queryWeatherNowForAgent(Long userId, String location, Double latitude, Double longitude) {
        log.info("Agent调用查询实时天气: userId={}, location={}, latitude={}, longitude={}", userId, location, latitude, longitude);
        String resolvedLocation = resolveLocation(userId, latitude, longitude, location);
        return getWeatherNow(resolvedLocation);
    }

    @AgentTool(name = "query_weather_forecast", description = "查询天气预报，用户询问未来天气时调用。优先使用经纬度查询，其次使用城市名，最后使用用户默认城市")
    public WeatherForecastResponse queryWeatherForecastForAgent(Long userId, String location, Integer days, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预报: userId={}, location={}, days={}, latitude={}, longitude={}", userId, location, days, latitude, longitude);
        String resolvedLocation = resolveLocation(userId, latitude, longitude, location);
        return getWeatherForecast(resolvedLocation, days);
    }

    @AgentTool(name = "query_air_quality", description = "查询空气质量，用户询问空气质量时调用")
    public AirQualityResponse queryAirQualityForAgent(Long userId, Double latitude, Double longitude) {
        log.info("Agent调用查询空气质量: userId={}, lat={}, lon={}", userId, latitude, longitude);
        return getAirQuality(latitude, longitude);
    }

    @AgentTool(name = "query_weather_alert", description = "查询天气预警，用户询问是否有天气预警时调用")
    public WeatherAlertResponse queryWeatherAlertForAgent(Long userId, Double latitude, Double longitude) {
        log.info("Agent调用查询天气预警: userId={}, lat={}, lon={}", userId, latitude, longitude);
        return getWeatherAlert(latitude, longitude);
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 HTTP GET 请求，自动处理 gzip 解压
     */
    private String httpGet(String urlStr) {
        try {
            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();

            // 设置请求头
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("X-QW-Api-Key", weatherConfig.getKey());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");  // 请求 gzip 压缩，节省带宽

            int responseCode = conn.getResponseCode();
            log.debug("HTTP响应码: {}", responseCode);

            if (responseCode == 200 || responseCode == 304) {
                // 读取响应，自动处理 gzip
                String contentEncoding = conn.getHeaderField("Content-Encoding");
                BufferedReader reader;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    // gzip 压缩响应，需要解压
                    reader = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(conn.getInputStream()), "UTF-8"));
                } else {
                    // 普通响应
                    reader = new BufferedReader(new InputStreamReader(
                            conn.getInputStream(), "UTF-8"));
                }

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String json = sb.toString();
                log.debug("响应内容: {}", json);

                return json;
            } else {
                // 错误响应
                String errorMsg = readErrorStream(conn);
                log.error("HTTP错误: code={}, message={}", responseCode, errorMsg);
                throw new RuntimeException("HTTP请求失败: " + responseCode + " - " + errorMsg);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("HTTP请求异常", e);
            throw new RuntimeException("HTTP请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取错误流
     */
    private String readErrorStream(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() != null) {
                String contentEncoding = conn.getHeaderField("Content-Encoding");
                BufferedReader reader;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    reader = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(conn.getErrorStream()), "UTF-8"));
                } else {
                    reader = new BufferedReader(new InputStreamReader(
                            conn.getErrorStream(), "UTF-8"));
                }

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return "Unknown error";
    }

    /**
     * 解析 JSON 响应
     */
    private <T> T parseJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("JSON解析失败: json={}, class={}", json, clazz.getSimpleName(), e);
            throw new RuntimeException("JSON解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 API URL
     */
    private String buildUrl(String path) {
        return weatherConfig.getHost() + path;
    }

    /**
     * URL 编码（处理逗号等特殊字符）
     */
    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
