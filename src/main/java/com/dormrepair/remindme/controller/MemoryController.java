package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.request.MemoryRequest;
import com.dormrepair.remindme.dto.response.KnowledgeResult;
import com.dormrepair.remindme.dto.response.Result;
import com.dormrepair.remindme.enums.MemoryCategory;
import com.dormrepair.remindme.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 记忆库页面接口控制器。统一前缀 /api/memory：记忆内容由 AI 经对话产生，页面只提供
// 只读列表、分类/关键词筛选、删除与备注编辑，底层复用 RagService 的 Chroma 私有库读写方法
@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final RagService ragService;

    // 全量记忆列表：按创建时间倒序返回当前用户的全部私有记忆
    @GetMapping("/list")
    public Result<List<KnowledgeResult>> listMemories(@RequestParam Long userId) {
        log.info("记忆库页面获取记忆列表: userId={}", userId);
        try {
            List<KnowledgeResult> memories = ragService.getAllPrivateMemories(userId);
            return Result.success("获取成功，共 " + memories.size() + " 条", memories);
        } catch (Exception e) {
            log.error("记忆库页面获取记忆列表失败: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("获取记忆列表失败，请稍后再试");
        }
    }

    // 更新备注：备注是记忆中唯一允许用户手动编辑的字段，内容/分类/标题均由 AI 生成
    @PostMapping("/update-remark")
    public Result<Boolean> updateRemark(@RequestBody MemoryRequest.UpdateRemarkRequest request) {
        log.info("记忆库页面更新备注: memoryId={}, userId={}", request.getMemoryId(), request.getUserId());
        if (request.getUserId() == null || request.getMemoryId() == null || request.getMemoryId().isEmpty()) {
            return Result.error("参数不完整");
        }
        try {
            boolean success = ragService.updateMemoryRemark(
                    request.getMemoryId(), request.getUserId(),
                    request.getRemark() == null ? "" : request.getRemark().trim());
            if (success) {
                return Result.success("备注已保存", true);
            }
            return Result.error("备注保存失败，请稍后再试");
        } catch (Exception e) {
            log.error("记忆库页面更新备注失败: memoryId={}, error={}", request.getMemoryId(), e.getMessage(), e);
            return Result.error("备注保存失败，请稍后再试");
        }
    }

    // 删除单条记忆：物理删除，不可恢复
    @PostMapping("/delete")
    public Result<Boolean> deleteMemory(@RequestBody MemoryRequest.DeleteRequest request) {
        log.info("记忆库页面删除记忆: memoryId={}, userId={}", request.getMemoryId(), request.getUserId());
        if (request.getUserId() == null || request.getMemoryId() == null || request.getMemoryId().isEmpty()) {
            return Result.error("参数不完整");
        }
        try {
            boolean success = ragService.deletePrivateMemory(request.getMemoryId(), request.getUserId());
            if (success) {
                return Result.success("删除成功", true);
            }
            return Result.error("删除失败，请稍后再试");
        } catch (Exception e) {
            log.error("记忆库页面删除记忆失败: memoryId={}, error={}", request.getMemoryId(), e.getMessage(), e);
            return Result.error("删除失败，请稍后再试");
        }
    }

    // 批量删除记忆：逐条删除并统计成功/失败数量
    @PostMapping("/batch-delete")
    public Result<Map<String, Integer>> batchDeleteMemory(@RequestBody MemoryRequest.BatchDeleteRequest request) {
        log.info("记忆库页面批量删除记忆: userId={}, count={}",
                request.getUserId(), request.getMemoryIds() == null ? 0 : request.getMemoryIds().size());
        if (request.getUserId() == null || request.getMemoryIds() == null || request.getMemoryIds().isEmpty()) {
            return Result.error("请先勾选要删除的记忆");
        }
        try {
            int successCount = 0;
            int failCount = 0;
            for (String memoryId : request.getMemoryIds()) {
                if (ragService.deletePrivateMemory(memoryId, request.getUserId())) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            Map<String, Integer> data = new HashMap<>();
            data.put("successCount", successCount);
            data.put("failCount", failCount);
            return Result.success("删除完成", data);
        } catch (Exception e) {
            log.error("记忆库页面批量删除失败: userId={}, error={}", request.getUserId(), e.getMessage(), e);
            return Result.error("删除失败，请稍后再试");
        }
    }

    // 导出数据：format=json 返回结构化列表，format=txt 返回纯文本内容，文件名与下载由前端处理
    @GetMapping("/export")
    public Result<Object> exportMemories(@RequestParam Long userId, @RequestParam String format) {
        log.info("记忆库页面导出记忆: userId={}, format={}", userId, format);
        try {
            List<KnowledgeResult> memories = ragService.getAllPrivateMemories(userId);

            if ("txt".equalsIgnoreCase(format)) {
                StringBuilder sb = new StringBuilder();
                sb.append("助手记忆导出（共 ").append(memories.size()).append(" 条）\n");
                sb.append("导出时间：").append(LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
                for (KnowledgeResult memory : memories) {
                    sb.append("【").append(categoryText(memory.getCategory())).append("】");
                    sb.append(memory.getTitle() != null ? memory.getTitle() : "").append("\n");
                    sb.append(memory.getContent() != null ? memory.getContent() : "").append("\n");
                    if (memory.getRemark() != null && !memory.getRemark().isEmpty()) {
                        sb.append("备注：").append(memory.getRemark()).append("\n");
                    }
                    if (memory.getCreateTime() != null) {
                        sb.append("记录时间：").append(memory.getCreateTime()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                    }
                    sb.append("\n");
                }
                return Result.success("导出成功", sb.toString());
            }

            // 默认按 JSON 导出：只保留关键字段
            List<Map<String, Object>> data = new ArrayList<>();
            for (KnowledgeResult memory : memories) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", memory.getId());
                item.put("title", memory.getTitle());
                item.put("content", memory.getContent());
                item.put("category", memory.getCategory());
                item.put("remark", memory.getRemark());
                item.put("createTime", memory.getCreateTime() == null ? null
                        : memory.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                item.put("userId", memory.getUserId());
                data.add(item);
            }
            return Result.success("导出成功", data);
        } catch (Exception e) {
            log.error("记忆库页面导出失败: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("导出失败，请稍后再试");
        }
    }

    // 分类编码转中文名：无分类或编码不合法时显示"未分类"
    private String categoryText(String category) {
        if (category == null || category.isEmpty()) {
            return "未分类";
        }
        try {
            switch (MemoryCategory.valueOf(category)) {
                case PERSONAL_INFO: return "个人信息";
                case ACTIVITY: return "活动事务";
                case EVENT: return "重要日期";
                case PLAN: return "计划目标";
                case EXAM_SCHEDULE: return "考试安排";
                case HEALTH_CONDITION: return "健康状况";
                case DIET_PREFERENCE: return "饮食偏好";
                case HABIT: return "个人习惯";
                case INTEREST: return "兴趣爱好";
                case LANGUAGE_STYLE: return "语言风格";
                case OTHER: return "其他";
                default: return "未分类";
            }
        } catch (IllegalArgumentException e) {
            return "未分类";
        }
    }
}
