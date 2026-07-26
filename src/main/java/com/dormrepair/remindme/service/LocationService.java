package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 位置服务 - 基于高德地图逆地理编码
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    @Value("${amap.api.key:}")
    private String amapKey;

    /**
     * 逆地理编码：根据经纬度获取"省-市-区"格式地址
     */
    public String reverseGeocode(Double latitude, Double longitude) {
        // 检查 Key 是否配置
        if (amapKey == null || amapKey.isBlank()) {
            log.warn("高德地图 API Key 未配置，返回默认地址");
            return "广东省-广州市-天河区";
        }

        // 拼接请求地址（高德要求：经度在前，纬度在后）
        String url = String.format(
                "https://restapi.amap.com/v3/geocode/regeo?location=%f,%f&key=%s&output=json",
                longitude, latitude, amapKey
        );

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            // 检查高德返回状态
            int status = root.path("status").asInt(0);
            if (status != 1) {
                log.error("高德逆地理编码失败: status={}, info={}", status, root.path("info").asText());
                throw new RuntimeException("逆地理编码失败: " + root.path("info").asText());
            }

            JsonNode regeo = root.path("regeocode").path("addressComponent");
            String province = regeo.path("province").asText("");
            String city = regeo.path("city").asText("");
            String district = regeo.path("district").asText("");

            // 直辖市 city 可能为空，用 province 代替
            if (city.isBlank()) {
                city = province;
            }

            String address = province + "-" + city + "-" + district;
            log.info("逆地理编码结果: ({},{}) -> {}", latitude, longitude, address);
            return address;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("逆地理编码异常: latitude={}, longitude={}", latitude, longitude, e);
            throw new RuntimeException("逆地理编码异常: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户当前地理位置
     * 有GPS坐标则调用高德API转地址并更新默认城市，否则返回数据库中的默认城市
     */
    @AgentTool(name = "get_location", description = "获取用户当前地理位置，如果有GPS坐标则调用高德API转地址，否则返回数据库中的默认城市")
    public Map<String, Object> getLocation(Long userId, Double latitude, Double longitude) {
        Map<String, Object> result = new HashMap<>();

        // 有GPS坐标：逆地理编码 + 更新默认城市
        if (latitude != null && longitude != null) {
            try {
                String address = reverseGeocode(latitude, longitude);
                userService.updateDefaultCity(userId, address);
                result.put("success", true);
                result.put("address", address);
                result.put("source", "gps");
                log.info("GPS定位成功: userId={}, address={}", userId, address);
            } catch (Exception e) {
                log.error("GPS定位失败: userId={}", userId, e);
                result.put("success", false);
                result.put("message", "定位失败: " + e.getMessage());
            }
            return result;
        }

        // 无GPS坐标：从数据库读取默认城市
        try {
            User user = userService.getUserById(userId).orElse(null);
            if (user != null && user.getCity() != null && !user.getCity().isBlank()) {
                result.put("success", true);
                result.put("address", user.getCity());
                result.put("source", "database");
            } else {
                result.put("success", false);
                result.put("message", "未设置默认城市，请在设置中配置");
            }
        } catch (Exception e) {
            log.error("查询用户默认城市失败: userId={}", userId, e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        return result;
    }
}
