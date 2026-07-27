package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.AirQualityResponse;
import com.dormrepair.remindme.dto.response.WeatherAlertResponse;
import com.dormrepair.remindme.dto.response.WeatherForecastResponse;
import com.dormrepair.remindme.dto.response.WeatherNowResponse;
import com.dormrepair.remindme.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 天气接口控制器
 * <p>
 * 提供天气相关的 REST API 接口，用于测试和风天气API调用
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /**
     * 获取实时天气
     *
     * @param location LocationID（如"101010100"）或经纬度（如"116.41,39.92"）
     * @return 实时天气数据
     */
    @GetMapping("/now")
    public WeatherNowResponse getWeatherNow(@RequestParam String location) {
        log.info("REST接口调用获取实时天气: location={}", location);
        return weatherService.getWeatherNow(location);
    }

    /**
     * 获取天气预报
     *
     * @param location LocationID或经纬度
     * @param days     预报天数（3/7/10/15/30，默认7天）
     * @return 天气预报数据
     */
    @GetMapping("/forecast")
    public WeatherForecastResponse getWeatherForecast(
            @RequestParam String location,
            @RequestParam(defaultValue = "7") Integer days) {
        log.info("REST接口调用获取天气预报: location={}, days={}", location, days);
        return weatherService.getWeatherForecast(location, days);
    }

    /**
     * 获取空气质量
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @return 空气质量数据
     */
    @GetMapping("/air")
    public AirQualityResponse getAirQuality(
            @RequestParam Double latitude,
            @RequestParam Double longitude) {
        log.info("REST接口调用获取空气质量: lat={}, lon={}", latitude, longitude);
        return weatherService.getAirQuality(latitude, longitude);
    }

    /**
     * 获取天气预警
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @return 天气预警数据
     */
    @GetMapping("/alert")
    public WeatherAlertResponse getWeatherAlert(
            @RequestParam Double latitude,
            @RequestParam Double longitude) {
        log.info("REST接口调用获取天气预警: lat={}, lon={}", latitude, longitude);
        return weatherService.getWeatherAlert(latitude, longitude);
    }
}
