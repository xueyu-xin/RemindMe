package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.AirQualityResponse;
import com.dormrepair.remindme.dto.response.WeatherAlertResponse;
import com.dormrepair.remindme.dto.response.WeatherForecastResponse;
import com.dormrepair.remindme.dto.response.WeatherNowResponse;
import com.dormrepair.remindme.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

// 天气接口控制器。统一前缀 /api/weather，对外暴露和风天气的 4 类核心数据：实时天气、多日预报、空气质量、气象灾害预警
@Slf4j
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    // 天气服务：封装和风天气接口签名、响应转换、异常处理等细节
    private final WeatherService weatherService;

    // 实时天气查询接口。location 支持 LocationID（如"101010100"）或"经度,纬度"（如"116.41,39.92"）两种写法
    @GetMapping("/now")
    public WeatherNowResponse getWeatherNow(@RequestParam String location) {
        log.info("REST接口调用获取实时天气: location={}", location);
        return weatherService.getWeatherNow(location);
    }

    // 天气预报查询接口。days 可选值：3/7/10/15/30，默认给 7 天覆盖日常需求
    @GetMapping("/forecast")
    public WeatherForecastResponse getWeatherForecast(
            @RequestParam String location,
            @RequestParam(defaultValue = "7") Integer days) {
        log.info("REST接口调用获取天气预报: location={}, days={}", location, days);
        return weatherService.getWeatherForecast(location, days);
    }

    // 空气质量查询接口。和实时天气不同，和风空气质量接口要求单独的经纬度传参，走 API 专门的 air 域名
    @GetMapping("/air")
    public AirQualityResponse getAirQuality(
            @RequestParam Double latitude,
            @RequestParam Double longitude) {
        log.info("REST接口调用获取空气质量: lat={}, lon={}", latitude, longitude);
        return weatherService.getAirQuality(latitude, longitude);
    }

    // 气象灾害预警查询接口。返回当地当前生效的台风、暴雨、高温等红色/橙色/黄色预警
    @GetMapping("/alert")
    public WeatherAlertResponse getWeatherAlert(
            @RequestParam Double latitude,
            @RequestParam Double longitude) {
        log.info("REST接口调用获取天气预警: lat={}, lon={}", latitude, longitude);
        return weatherService.getWeatherAlert(latitude, longitude);
    }
}
