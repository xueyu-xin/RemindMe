package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.request.RagTestRequest;
import com.dormrepair.remindme.dto.response.KnowledgeResult;
import com.dormrepair.remindme.dto.response.Result;
import com.dormrepair.remindme.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// RAG 测试接口控制器。统一前缀 /api/rag/test，提供私人记忆增删改查、公共知识检索共 6 个调试接口，
// 直接调用 RagService 核心方法用于联调与功能自测；Agent 线上流程走 @AgentTool 标注的 ForAgent 方法，不经过此 Controller
@Slf4j
@RestController
@RequestMapping("/api/rag/test")
@RequiredArgsConstructor
public class RagController {

    // RAG 服务：封装向量库读写、Embedding、相似度检索等全部核心逻辑
    private final RagService ragService;

    // 检索私人记忆：在对应用户的 user_{userId}_knowledge 集合中按 topK 返回与 query 相似度最高的片段
    @PostMapping("/retrievePrivateKnowledge")
    public Result<List<KnowledgeResult>> testRetrievePrivateKnowledge(
            @RequestBody RagTestRequest.RetrievePrivateKnowledgeRequest request) {
        log.info("测试检索私人记忆: userId={}, query={}, topK={}",
                request.getUserId(), request.getQuery(), request.getTopK());
        try {
            List<KnowledgeResult> results = ragService.retrievePrivateKnowledge(
                    request.getUserId(),
                    request.getQuery(),
                    request.getTopK()
            );
            return Result.success("检索成功，共 " + results.size() + " 条", results);
        } catch (Exception e) {
            log.error("测试检索私人记忆失败: userId={}, error={}",
                    request.getUserId(), e.getMessage(), e);
            return Result.error("检索失败: " + e.getMessage());
        }
    }

    // 新增私人记忆：把用户输入的内容 + 分类写入用户私有向量库，返回生成的 memoryId 便于后续定位
    @PostMapping("/addPrivateMemory")
    public Result<String> testAddPrivateMemory(
            @RequestBody RagTestRequest.AddPrivateMemoryRequest request) {
        log.info("测试添加私人记忆: userId={}, category={}, content={}",
                request.getUserId(), request.getCategory(), request.getContent());
        try {
            String memoryId = ragService.addPrivateMemory(
                    request.getUserId(),
                    request.getContent(),
                    request.getCategory()
            );
            if (memoryId != null) {
                return Result.success("添加成功", memoryId);
            } else {
                return Result.error("添加失败：返回的 memoryId 为空");
            }
        } catch (Exception e) {
            log.error("测试添加私人记忆失败: userId={}, error={}",
                    request.getUserId(), e.getMessage(), e);
            return Result.error("添加失败: " + e.getMessage());
        }
    }

    // 更新私人记忆：按「先删旧→再写新」的幂等策略更新一条记忆的内容，解决向量库 update 成本高的问题
    @PostMapping("/updatePrivateMemory")
    public Result<Boolean> testUpdatePrivateMemory(
            @RequestBody RagTestRequest.UpdatePrivateMemoryRequest request) {
        log.info("测试更新私人记忆: memoryId={}, userId={}, newContent={}",
                request.getMemoryId(), request.getUserId(), request.getNewContent());
        try {
            boolean success = ragService.updatePrivateMemory(
                    request.getMemoryId(),
                    request.getUserId(),
                    request.getNewContent()
            );
            return Result.success(success ? "更新成功" : "更新失败", success);
        } catch (Exception e) {
            log.error("测试更新私人记忆失败: memoryId={}, userId={}, error={}",
                    request.getMemoryId(), request.getUserId(), e.getMessage(), e);
            return Result.error("更新失败: " + e.getMessage());
        }
    }

    // 删除私人记忆：从用户私有库中移除指定 memoryId 的文档，多次调用同一条目仍返回成功（幂等）
    @PostMapping("/deletePrivateMemory")
    public Result<Boolean> testDeletePrivateMemory(
            @RequestBody RagTestRequest.DeletePrivateMemoryRequest request) {
        log.info("测试删除私人记忆: memoryId={}, userId={}",
                request.getMemoryId(), request.getUserId());
        try {
            boolean success = ragService.deletePrivateMemory(
                    request.getMemoryId(),
                    request.getUserId()
            );
            return Result.success(success ? "删除成功" : "删除失败", success);
        } catch (Exception e) {
            log.error("测试删除私人记忆失败: memoryId={}, userId={}, error={}",
                    request.getMemoryId(), request.getUserId(), e.getMessage(), e);
            return Result.error("删除失败: " + e.getMessage());
        }
    }

    // 拉取用户全部私人记忆：不做相似度检索，直接从向量库返回该用户名下所有记忆，按创建时间倒序
    @PostMapping("/getAllPrivateMemories")
    public Result<List<KnowledgeResult>> testGetAllPrivateMemories(
            @RequestBody RagTestRequest.GetAllPrivateMemoriesRequest request) {
        log.info("测试获取所有私人记忆: userId={}", request.getUserId());
        try {
            List<KnowledgeResult> results = ragService.getAllPrivateMemories(request.getUserId());
            return Result.success("获取成功，共 " + results.size() + " 条", results);
        } catch (Exception e) {
            log.error("测试获取所有私人记忆失败: userId={}, error={}",
                    request.getUserId(), e.getMessage(), e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    // 检索公共知识：在 public_knowledge 集合中检索，内容仅含系统使用手册类说明
    @PostMapping("/retrievePublicKnowledge")
    public Result<List<KnowledgeResult>> testRetrievePublicKnowledge(
            @RequestBody RagTestRequest.RetrievePublicKnowledgeRequest request) {
        log.info("测试检索公共知识: query={}, topK={}",
                request.getQuery(), request.getTopK());
        try {
            List<KnowledgeResult> results = ragService.retrievePublicKnowledge(
                    request.getQuery(),
                    request.getTopK()
            );
            return Result.success("检索成功，共 " + results.size() + " 条", results);
        } catch (Exception e) {
            log.error("测试检索公共知识失败: query={}, error={}",
                    request.getQuery(), e.getMessage(), e);
            return Result.error("检索失败: " + e.getMessage());
        }
    }
}
