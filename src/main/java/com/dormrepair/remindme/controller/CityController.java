package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.CitySearchResult;
import com.dormrepair.remindme.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 城市数据搜索接口控制器。统一前缀 /api/city，为「设置默认城市」「天气查询」等场景提供城市名联想与编码反查能力
@Slf4j
@RestController
@RequestMapping("/api/city")
@RequiredArgsConstructor
public class CityController {

    // 位置服务：内部封装城市表查询、拼音匹配、坐标关联等逻辑
    private final LocationService locationService;

    // 城市关键词搜索。支持中文城市名、拼音首字母、全拼等多种匹配方式，默认返回 Top 5 命中项
    // keyword - 用户输入的搜索关键词
    @GetMapping("/search")
    public List<CitySearchResult> searchCities(@RequestParam String keyword) {
        log.info("搜索城市: keyword={}", keyword);
        return locationService.searchCities(keyword);
    }
}
