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

/**
 * RAG 测试接口控制器
 * <p>
 * 提供 6 个测试接口用于验证 RAG 模块的核心功能：
 * </p>
 * <ul>
 *   <li>检索私人记忆</li>
 *   <li>添加私人记忆</li>
 *   <li>更新私人记忆</li>
 *   <li>删除私人记忆</li>
 *   <li>获取所有私人记忆</li>
 *   <li>检索公共知识</li>
 * </ul>
 *
 * <p><b>注意</b>：本 Controller 仅用于测试，调用的是 RagService 的核心方法（非 ForAgent 方法）。
 * Agent 系统通过 @AgentTool 注解直接调用 ForAgent 方法，不经过此 Controller。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/test")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    /**
     * 测试检索私人记忆
     * <p>
     * 从用户私有库中检索与查询最相关的记忆。
     * </p>
     *
     * @param request 请求参数（userId、query、topK）
     * @return 检索结果列表
     */
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

    /**
     * 测试添加私人记忆
     * <p>
     * 将一条记忆存入用户私有库。
     * </p>
     *
     * @param request 请求参数（userId、content、category）
     * @return 新建记忆的文档ID
     */
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

    /**
     * 测试更新私人记忆
     * <p>
     * 更新指定记忆的内容（先删后增策略）。
     * </p>
     *
     * @param request 请求参数（memoryId、userId、newContent）
     * @return 更新是否成功
     */
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

    /**
     * 测试删除私人记忆
     * <p>
     * 从用户私有库中删除指定记忆（幂等操作）。
     * </p>
     *
     * @param request 请求参数（memoryId、userId）
     * @return 删除是否成功
     */
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

    /**
     * 测试获取所有私人记忆
     * <p>
     * 返回用户私有库中的全部记忆，按创建时间倒序。
     * </p>
     *
     * @param request 请求参数（userId）
     * @return 用户所有记忆列表
     */
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

    /**
     * 测试检索公共知识
     * <p>
     * 从公共知识库中检索相关知识（生活小技巧、系统说明等）。
     * </p>
     *
     * @param request 请求参数（query、topK）
     * @return 检索结果列表
     */
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
