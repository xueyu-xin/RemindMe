package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.dto.response.KnowledgeResult;
import com.dormrepair.remindme.enums.MemoryCategory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// RAG 记忆服务：基于 Chroma 向量库管理两类知识——
// 用户私有记忆（每用户独立 collection）和公共知识库（系统使用手册），
// 对 Agent 暴露 6 个工具：检索/新增/更新/删除/列出私有记忆、检索公共知识。
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    // 公共知识库的向量库（系统使用手册），所有用户共享
    private final ChromaVectorStore vectorStore;
    private final ChromaApi chromaApi;
    private final EmbeddingModel embeddingModel;

    // Chroma 多租户与数据库配置，构建用户私有库时使用
    @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}")
    private String tenantName;

    @Value("${spring.ai.vectorstore.chroma.database-name:default_database}")
    private String databaseName;

    // 每个用户对应一个独立的 ChromaVectorStore，懒加载并缓存
    private final Map<Long, ChromaVectorStore> userVectorStoreCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 按用户 ID 获取（必要时创建）私有向量库，collection 名固定为 user_{id}_knowledge
    private ChromaVectorStore getUserVectorStore(Long userId) {
        return userVectorStoreCache.computeIfAbsent(userId, id -> {
            String collectionName = "user_" + id + "_knowledge";

            ChromaVectorStore store = ChromaVectorStore.builder(chromaApi, embeddingModel)
                    .collectionName(collectionName)
                    .tenantName(tenantName)
                    .databaseName(databaseName)
                    .initializeSchema(true)
                    .build();

            try {
                store.afterPropertiesSet();
                log.debug("用户库初始化成功: userId={}, collection={}", id, collectionName);
            } catch (Exception e) {
                log.error("用户库初始化失败: userId={}, collection={}, error={}",
                        id, collectionName, e.getMessage(), e);
            }

            return store;
        });
    }

    // 把 Spring AI 的 Document 统一转成对外暴露的 KnowledgeResult，
    // 顺便解析 metadata 里的 category、userId、createTime 等字段。
    private KnowledgeResult toKnowledgeResult(Document doc, String source) {
        Map<String, Object> metadata = doc.getMetadata();
        String categoryStr = metadata != null && metadata.get("category") != null
                ? metadata.get("category").toString() : null;
        Long docUserId = metadata != null && metadata.get("userId") != null
                ? Long.valueOf(metadata.get("userId").toString()) : null;

        LocalDateTime createTime = null;
        if (metadata != null && metadata.get("createTime") != null) {
            try {
                createTime = LocalDateTime.ofInstant(
                        Instant.parse(metadata.get("createTime").toString()),
                        ZoneId.systemDefault()
                );
            } catch (Exception e) {
                log.warn("解析 createTime 失败: {}", metadata.get("createTime"));
            }
        }

        return KnowledgeResult.builder()
                .id(doc.getId())
                .content(doc.getText())
                .score(doc.getScore())
                .category(categoryStr)
                .userId(docUserId)
                .source(source)
                .createTime(createTime)
                .metadata(metadata)
                .build();
    }

    // ==================== ChromaDB 重试包装器 ====================

    // ChromaDB 操作重试包装器：仅对网络异常（RestClientException，含 ResourceAccessException/5xx）重试，
    // 4xx 不重试，指数退避 100ms→200ms→400ms，重试耗尽抛 RuntimeException（由调用方外层 catch 兜底返回默认值）。
    // Spring AI 的网络超时已被包装为 ResourceAccessException（RestClientException 子类），故直接 catch
    // RestClientException 即可覆盖 SocketTimeoutException 等底层网络异常。
    private <T> T callWithRetry(Supplier<T> action, String operation) {
        int maxRetries = 3;
        int attempt = 0;
        RuntimeException lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                return action.get();
            } catch (RuntimeException e) {
                // 仅对网络异常（RestClientException，包含 ResourceAccessException/HttpStatusCodeException）重试
                if (!(e instanceof RestClientException)) {
                    throw e;
                }
                // 4xx 客户端错误不重试
                if (e instanceof HttpStatusCodeException) {
                    int statusCode = ((HttpStatusCodeException) e).getStatusCode().value();
                    if (statusCode >= 400 && statusCode < 500) {
                        throw e;
                    }
                }
                lastException = e;
                log.warn("ChromaDB {} 调用失败，第 {} 次重试: {}", operation, attempt, e.getMessage());
                if (attempt >= maxRetries) {
                    break;
                }
                long waitMs = (long) (Math.pow(2, attempt - 1) * 100);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException("ChromaDB " + operation + " 调用失败，已重试 " + maxRetries + " 次", lastException);
    }

    // ==================== 私有记忆管理 ====================

    // 新增一条私有记忆，写入用户独立 collection，返回生成的文档 ID；失败返回 null
    public String addPrivateMemory(Long userId, String content, MemoryCategory category) {
        log.info("添加私有记忆: userId={}, category={}", userId, category);
        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);

            String docId = UUID.randomUUID().toString();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("category", category.name());
            metadata.put("createTime", Instant.now().toString());

            Document document = new Document(docId, content, metadata);
            callWithRetry(() -> { userStore.add(List.of(document)); return true; }, "添加私有记忆");

            log.info("私有记忆添加成功: docId={}, userId={}", docId, userId);
            return docId;
        } catch (Exception e) {
            log.error("添加私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            return null;
        }
    }

    // 删除指定私有记忆，参数非法或库中不存在时返回 false
    public boolean deletePrivateMemory(String memoryId, Long userId) {
        if (memoryId == null || memoryId.isEmpty() || userId == null) {
            log.warn("删除记忆参数非法: memoryId={}, userId={}", memoryId, userId);
            return false;
        }

        log.info("删除私人记忆: memoryId={}, userId={}", memoryId, userId);
        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            callWithRetry(() -> { userStore.delete(List.of(memoryId)); return true; }, "删除私有记忆");
            log.info("私人记忆删除成功: memoryId={}, userId={}", memoryId, userId);
            return true;
        } catch (Exception e) {
            log.error("删除私人记忆失败: memoryId={}, userId={}, error={}",
                    memoryId, userId, e.getMessage(), e);
            return false;
        }
    }

    // 更新私有记忆：先查出旧分类 → 删旧文档 → 按原分类新增新文档。
    // 删除成功但新增失败时会丢失原记忆，调用方需注意返回值。
    public boolean updatePrivateMemory(String memoryId, Long userId, String newContent) {
        if (memoryId == null || memoryId.isEmpty()
                || userId == null
                || newContent == null || newContent.isEmpty()) {
            log.warn("更新记忆参数非法: memoryId={}, userId={}, newContent={}",
                    memoryId, userId, newContent);
            return false;
        }

        log.info("更新私人记忆: memoryId={}, userId={}", memoryId, userId);
        try {
            MemoryCategory oldCategory = findMemoryCategory(memoryId, userId);
            if (oldCategory == null) {
                log.warn("更新失败：旧文档不存在, memoryId={}, userId={}", memoryId, userId);
                return false;
            }

            boolean deleted = deletePrivateMemory(memoryId, userId);
            if (!deleted) {
                log.error("更新失败：删除旧文档失败, memoryId={}, userId={}", memoryId, userId);
                return false;
            }

            String newDocId = addPrivateMemory(userId, newContent, oldCategory);
            if (newDocId == null) {
                log.error("更新失败：添加新文档失败，旧文档已删除！memoryId={}, userId={}",
                        memoryId, userId);
                return false;
            }

            log.info("私人记忆更新成功: oldMemoryId={}, newDocId={}, userId={}",
                    memoryId, newDocId, userId);
            return true;
        } catch (Exception e) {
            log.error("更新私人记忆异常: memoryId={}, userId={}, error={}",
                    memoryId, userId, e.getMessage(), e);
            return false;
        }
    }

    // 反查某条记忆的 MemoryCategory：先拉全量记忆再按 ID 过滤，找不到返回 null
    private MemoryCategory findMemoryCategory(String memoryId, Long userId) {
        List<KnowledgeResult> allMemories = getAllPrivateMemories(userId);
        if (allMemories == null || allMemories.isEmpty()) {
            return null;
        }

        return allMemories.stream()
                .filter(item -> memoryId.equals(item.getId()))
                .map(KnowledgeResult::getCategory)
                .filter(categoryStr -> categoryStr != null && !categoryStr.isEmpty())
                .map(categoryStr -> {
                    try {
                        return MemoryCategory.valueOf(categoryStr);
                    } catch (IllegalArgumentException e) {
                        log.warn("未知的记忆分类: {}, memoryId={}", categoryStr, memoryId);
                        return null;
                    }
                })
                .findFirst()
                .orElse(null);
    }

    // 列出用户全部私有记忆：用一个高频字"的"做近似搜索拉取 top 1000，
    // 再按创建时间倒序返回；查不到或异常时返回空列表。
    public List<KnowledgeResult> getAllPrivateMemories(Long userId) {
        log.info("获取所有私有记忆: userId={}", userId);
        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("的")
                    .topK(1000)
                    .build();
            List<Document> documents = callWithRetry(
                    () -> userStore.similaritySearch(searchRequest), "获取所有私有记忆");

            if (documents == null || documents.isEmpty()) {
                log.debug("用户私有库为空: userId={}", userId);
                return Collections.emptyList();
            }

            List<KnowledgeResult> results = documents.stream()
                    .map(doc -> toKnowledgeResult(doc, "private"))
                    .sorted(Comparator.comparing(
                            KnowledgeResult::getCreateTime,
                            Comparator.nullsLast(Comparator.reverseOrder())
                    ))
                    .collect(Collectors.toList());

            log.info("获取私有记忆成功: userId={}, count={}", userId, results.size());
            return results;
        } catch (Exception e) {
            log.error("获取私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ==================== 知识检索 ====================

    // 在用户私有记忆中做向量相似度检索，topK 未指定时按记忆总量动态计算，
    // 结果按相似度分数倒序返回。
    public List<KnowledgeResult> retrievePrivateKnowledge(Long userId, String query, Integer topK) {
        int actualTopK = calculateTopK(userId, topK);
        log.info("检索私人记忆: userId={}, query={}, topK={}", userId, query, actualTopK);

        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(actualTopK)
                    .build();
            List<Document> documents = callWithRetry(
                    () -> userStore.similaritySearch(searchRequest), "检索私有记忆");

            if (documents == null || documents.isEmpty()) {
                log.debug("私有记忆检索无结果: userId={}, query={}", userId, query);
                return Collections.emptyList();
            }

            List<KnowledgeResult> results = documents.stream()
                    .map(doc -> toKnowledgeResult(doc, "private"))
                    .sorted(Comparator.comparingDouble(KnowledgeResult::getScore).reversed())
                    .collect(Collectors.toList());

            log.info("私有记忆检索成功: userId={}, query={}, count={}", userId, query, results.size());
            return results;
        } catch (Exception e) {
            log.error("私有记忆检索失败: userId={}, query={}, error={}", userId, query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // 在公共知识库（系统使用手册）中做向量检索，默认 topK=5，结果按分数倒序返回
    public List<KnowledgeResult> retrievePublicKnowledge(String query, Integer topK) {
        if (query == null || query.isEmpty()) {
            log.warn("检索公共知识参数非法: query 为空");
            return Collections.emptyList();
        }
        int actualTopK = (topK != null && topK > 0) ? topK : 5;

        log.info("检索公共知识: query={}, topK={}", query, actualTopK);
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(actualTopK)
                    .build();
            List<Document> documents = callWithRetry(
                    () -> vectorStore.similaritySearch(searchRequest), "检索公共知识");

            if (documents == null || documents.isEmpty()) {
                log.debug("公共知识检索无结果: query={}", query);
                return Collections.emptyList();
            }

            List<KnowledgeResult> results = documents.stream()
                    .map(doc -> toKnowledgeResult(doc, "public"))
                    .sorted(Comparator.comparingDouble(KnowledgeResult::getScore).reversed())
                    .collect(Collectors.toList());

            log.info("公共知识检索成功: query={}, count={}", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("公共知识检索失败: query={}, error={}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ==================== Agent 工具方法 ====================

    // Agent 工具：检索用户私有记忆
    @AgentTool(
            name = "retrieve_private_knowledge",
            description = "从用户私有知识库中检索记忆，用于回答关于用户个人信息、偏好、计划等个性化问题。"
    )
    public Map<String, Object> retrievePrivateKnowledgeForAgent(Long userId, String query, Integer topK) {
        log.info("Agent调用检索私有记忆: userId={}, query={}, topK={}", userId, query, topK);

        try {
            List<KnowledgeResult> results = retrievePrivateKnowledge(userId, query, topK);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", results.isEmpty() ? "未找到相关记忆" : "检索成功，共找到 " + results.size() + " 条记忆");
            result.put("count", results.size());
            result.put("memories", results);

            log.info("Agent检索私有记忆成功: userId={}, count={}", userId, results.size());
            return result;
        } catch (Exception e) {
            log.error("Agent检索私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "检索失败：" + e.getMessage());
            errorResult.put("count", 0);
            errorResult.put("memories", Collections.emptyList());
            return errorResult;
        }
    }

    // Agent 工具：新增用户私有记忆
    @AgentTool(
            name = "add_private_memory",
            description = """
                    将用户提供的任何关于自己的信息存储到私有知识库中，包括但不限于：
                    - 个人信息（如"我是大三学生"、"我是计算机专业"）
                    - 饮食偏好（如"我喜欢吃苹果"）
                    - 课程安排（如"每周三下午有高数课"）
                    - 长期计划（如"我要考研"）
                    - 考试安排（如"6月有四级考试"）
                    - 日常习惯（如"我每天跑步"）
                    - 兴趣爱好（如"我喜欢打篮球"）

                    注意：只要用户提供了关于自己的事实信息，都应该调用此工具存储。"""
    )
    public Map<String, Object> addPrivateMemoryForAgent(Long userId, String content, MemoryCategory category) {
        log.info("Agent调用添加私有记忆: userId={}, category={}", userId, category);

        try {
            String memoryId = addPrivateMemory(userId, content, category);

            Map<String, Object> result = new HashMap<>();
            if (memoryId != null && !memoryId.isEmpty()) {
                result.put("success", true);
                result.put("message", "记忆已成功存储");
                result.put("memoryId", memoryId);
                result.put("content", content);
                result.put("category", category.name());
                log.info("Agent添加私有记忆成功: userId={}, memoryId={}", userId, memoryId);
            } else {
                result.put("success", false);
                result.put("message", "记忆存储失败，返回的memoryId为空");
            }

            return result;
        } catch (Exception e) {
            log.error("Agent添加私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "记忆存储失败：" + e.getMessage());
            return errorResult;
        }
    }

    // Agent 工具：更新用户私有记忆
    @AgentTool(
            name = "update_private_memory",
            description = "更新用户私有知识库中的某条记忆内容。"
    )
    public Map<String, Object> updatePrivateMemoryForAgent(Long userId, String memoryId, String newContent) {
        log.info("Agent调用更新私有记忆: userId={}, memoryId={}", userId, memoryId);

        try {
            boolean updated = updatePrivateMemory(memoryId, userId, newContent);

            Map<String, Object> result = new HashMap<>();
            if (updated) {
                result.put("success", true);
                result.put("message", "记忆已成功更新");
                result.put("memoryId", memoryId);
                log.info("Agent更新私有记忆成功: userId={}, memoryId={}", userId, memoryId);
            } else {
                result.put("success", false);
                result.put("message", "记忆更新失败，可能是memoryId不存在或参数非法");
            }

            return result;
        } catch (Exception e) {
            log.error("Agent更新私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "记忆更新失败：" + e.getMessage());
            return errorResult;
        }
    }

    // Agent 工具：删除用户私有记忆
    @AgentTool(
            name = "delete_private_memory",
            description = "删除用户私有知识库中的某条记忆。"
    )
    public Map<String, Object> deletePrivateMemoryForAgent(Long userId, String memoryId) {
        log.info("Agent调用删除私有记忆: userId={}, memoryId={}", userId, memoryId);

        try {
            boolean deleted = deletePrivateMemory(memoryId, userId);

            Map<String, Object> result = new HashMap<>();
            if (deleted) {
                result.put("success", true);
                result.put("message", "记忆已成功删除");
                result.put("memoryId", memoryId);
                log.info("Agent删除私有记忆成功: userId={}, memoryId={}", userId, memoryId);
            } else {
                result.put("success", false);
                result.put("message", "记忆删除失败，可能是memoryId不存在或参数非法");
            }

            return result;
        } catch (Exception e) {
            log.error("Agent删除私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "记忆删除失败：" + e.getMessage());
            return errorResult;
        }
    }

    // Agent 工具：列出用户全部私有记忆
    @AgentTool(
            name = "get_all_private_memories",
            description = "获取用户私有知识库中的所有记忆。"
    )
    public Map<String, Object> getAllPrivateMemoriesForAgent(Long userId) {
        log.info("Agent调用获取所有私有记忆: userId={}", userId);

        try {
            List<KnowledgeResult> results = getAllPrivateMemories(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", results.isEmpty() ? "私有记忆库为空" : "检索成功，共找到 " + results.size() + " 条记忆");
            result.put("count", results.size());
            result.put("memories", results);

            log.info("Agent获取所有私有记忆成功: userId={}, count={}", userId, results.size());
            return result;
        } catch (Exception e) {
            log.error("Agent获取所有私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "获取记忆失败：" + e.getMessage());
            errorResult.put("count", 0);
            errorResult.put("memories", Collections.emptyList());
            return errorResult;
        }
    }

    // Agent 工具：检索公共知识库（系统使用手册）
    @AgentTool(
            name = "retrieve_public_knowledge",
            description = "从公共知识库中检索系统使用手册（提醒管理、天气查询、个人记忆、经期管理、用户设置、位置与时间等功能说明）。注意：公共知识库仅承载使用手册，不再记录生活小常识/科普内容。"
    )
    public Map<String, Object> retrievePublicKnowledgeForAgent(Long userId, String query, Integer topK) {
        log.info("Agent调用检索公共知识: userId={}, query={}, topK={}", userId, query, topK);

        try {
            List<KnowledgeResult> results = retrievePublicKnowledge(query, topK);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", results.isEmpty() ? "未找到相关公共知识" : "检索成功，共找到 " + results.size() + " 条知识");
            result.put("count", results.size());
            result.put("knowledge", results);

            log.info("Agent检索公共知识成功: userId={}, count={}", userId, results.size());
            return result;
        } catch (Exception e) {
            log.error("Agent检索公共知识失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "检索失败：" + e.getMessage());
            errorResult.put("count", 0);
            errorResult.put("knowledge", Collections.emptyList());
            return errorResult;
        }
    }

    // ==================== 动态topK ====================

    // 计算 topK：调用方显式指定则优先用；否则按用户记忆总量分档（<20→5、≤100→10、其余→15）
    private int calculateTopK(Long userId, Integer topK) {
        if (topK != null) {
            log.debug("使用调用方指定 topK: userId={}, topK={}", userId, topK);
            return Math.max(1, topK);
        }

        int memoryCount = getPrivateMemoryCount(userId);
        int dynamicTopK;
        if (memoryCount < 20) {
            dynamicTopK = 5;
        } else if (memoryCount <= 100) {
            dynamicTopK = 10;
        } else {
            dynamicTopK = 15;
        }

        log.debug("动态计算 topK: userId={}, memoryCount={}, topK={}", userId, memoryCount, dynamicTopK);
        return dynamicTopK;
    }

    // 统计用户私有记忆条数，仅作为动态 topK 的输入，异常时按 0 处理
    private int getPrivateMemoryCount(Long userId) {
        try {
            List<KnowledgeResult> all = getAllPrivateMemories(userId);
            return all != null ? all.size() : 0;
        } catch (Exception e) {
            log.warn("查询用户记忆数量失败: userId={}, error={}", userId, e.getMessage());
            return 0;
        }
    }

    // ==================== 公共知识库初始化 ====================

    // 启动时初始化公共知识库：若已有数据则跳过，否则从 public_knowledge.json 导入预置使用手册。
    // 整个过程失败不影响应用启动。
    @PostConstruct
    public void initPublicKnowledge() {
        log.info("开始初始化公共知识库...");
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("的")
                    .topK(1)
                    .build();
            List<Document> existing = vectorStore.similaritySearch(searchRequest);
            if (existing != null && !existing.isEmpty()) {
                log.info("公共知识库已有数据，跳过初始化");
                return;
            }

            List<PresetKnowledge> presetList = loadPresetKnowledge();
            if (presetList.isEmpty()) {
                log.warn("预置知识文件为空或不存在，跳过公共库初始化");
                return;
            }

            List<Document> documents = new ArrayList<>();
            for (PresetKnowledge item : presetList) {
                String docId = UUID.randomUUID().toString();
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("category", item.getCategory());
                metadata.put("source", "public");
                metadata.put("createTime", Instant.now().toString());
                documents.add(new Document(docId, item.getContent(), metadata));
            }

            vectorStore.add(documents);
            log.info("公共知识库初始化完成：成功导入 {} 条预置知识", documents.size());
        } catch (Exception e) {
            log.warn("公共知识库初始化失败（不影响项目启动）: {}", e.getMessage(), e);
        }
    }

    // 从 classpath 读取 public_knowledge.json，解析成预置知识列表；文件缺失返回空列表
    private List<PresetKnowledge> loadPresetKnowledge() {
        try (InputStream is = new ClassPathResource("public_knowledge.json").getInputStream()) {
            List<PresetKnowledge> list = objectMapper.readValue(
                    is, new TypeReference<List<PresetKnowledge>>() {});
            log.debug("加载预置知识文件成功: {} 条", list != null ? list.size() : 0);
            return list != null ? list : Collections.emptyList();
        } catch (Exception e) {
            log.warn("加载预置知识文件失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // 预置知识条目，对应 public_knowledge.json 里的一条使用手册
    @Data
    @NoArgsConstructor
    public static class PresetKnowledge {
        private String content;
        private String category;
    }
}
