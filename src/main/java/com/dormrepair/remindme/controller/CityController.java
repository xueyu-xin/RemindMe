package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.CitySearchResult;
import com.dormrepair.remindme.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 城市搜索接口控制器
 * <p>
 * 提供城市搜索 REST API，供前端选择城市时调用
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/city")
@RequiredArgsConstructor
public class CityController {

    private final LocationService locationService;

    /**
     * 搜索城市
     *
     * @param keyword 搜索关键词（城市名/拼音）
     * @return 匹配的城市列表（最多5条）
     */
    @GetMapping("/search")
    public List<CitySearchResult> searchCities(@RequestParam String keyword) {
        log.info("搜索城市: keyword={}", keyword);
        return locationService.searchCities(keyword);
    }
}
