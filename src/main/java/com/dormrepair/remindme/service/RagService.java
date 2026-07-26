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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final ChromaVectorStore vectorStore;
    private final ChromaApi chromaApi;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}")
    private String tenantName;

    @Value("${spring.ai.vectorstore.chroma.database-name:default_database}")
    private String databaseName;

    private final Map<Long, ChromaVectorStore> userVectorStoreCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // ==================== 私有记忆管理 ====================

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
            userStore.add(List.of(document));

            log.info("私有记忆添加成功: docId={}, userId={}", docId, userId);
            return docId;
        } catch (Exception e) {
            log.error("添加私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            return null;
        }
    }

    public boolean deletePrivateMemory(String memoryId, Long userId) {
        if (memoryId == null || memoryId.isEmpty() || userId == null) {
            log.warn("删除记忆参数非法: memoryId={}, userId={}", memoryId, userId);
            return false;
        }

        log.info("删除私人记忆: memoryId={}, userId={}", memoryId, userId);
        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            userStore.delete(List.of(memoryId));
            log.info("私人记忆删除成功: memoryId={}, userId={}", memoryId, userId);
            return true;
        } catch (Exception e) {
            log.error("删除私人记忆失败: memoryId={}, userId={}, error={}",
                    memoryId, userId, e.getMessage(), e);
            return false;
        }
    }

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

    public List<KnowledgeResult> getAllPrivateMemories(Long userId) {
        log.info("获取所有私有记忆: userId={}", userId);
        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("的")
                    .topK(1000)
                    .build();
            List<Document> documents = userStore.similaritySearch(searchRequest);

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

    public List<KnowledgeResult> retrievePrivateKnowledge(Long userId, String query, Integer topK) {
        int actualTopK = calculateTopK(userId, topK);
        log.info("检索私人记忆: userId={}, query={}, topK={}", userId, query, actualTopK);

        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(actualTopK)
                    .build();
            List<Document> documents = userStore.similaritySearch(searchRequest);

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
            List<Document> documents = vectorStore.similaritySearch(searchRequest);

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

    @AgentTool(
            name = "retrieve_private_knowledge",
            description = "从用户私有知识库中检索记忆，用于回答关于用户个人信息、偏好、计划等个性化问题。"
    )
    public List<KnowledgeResult> retrievePrivateKnowledgeForAgent(Long userId, String query, Integer topK) {
        log.info("Agent调用检索私有记忆: userId={}, query={}, topK={}", userId, query, topK);
        return retrievePrivateKnowledge(userId, query, topK);
    }

    @AgentTool(
            name = "add_private_memory",
            description = "将用户提供的个人信息存储到私有知识库中，包括个人信息、活动、计划、习惯等。"
    )
    public String addPrivateMemoryForAgent(Long userId, String content, MemoryCategory category) {
        log.info("Agent调用添加私有记忆: userId={}, category={}", userId, category);
        return addPrivateMemory(userId, content, category);
    }

    @AgentTool(
            name = "update_private_memory",
            description = "更新用户私有知识库中的某条记忆内容。"
    )
    public boolean updatePrivateMemoryForAgent(Long userId, String memoryId, String newContent) {
        log.info("Agent调用更新私有记忆: userId={}, memoryId={}", userId, memoryId);
        return updatePrivateMemory(memoryId, userId, newContent);
    }

    @AgentTool(
            name = "delete_private_memory",
            description = "删除用户私有知识库中的某条记忆。"
    )
    public boolean deletePrivateMemoryForAgent(Long userId, String memoryId) {
        log.info("Agent调用删除私有记忆: userId={}, memoryId={}", userId, memoryId);
        return deletePrivateMemory(memoryId, userId);
    }

    @AgentTool(
            name = "get_all_private_memories",
            description = "获取用户私有知识库中的所有记忆。"
    )
    public List<KnowledgeResult> getAllPrivateMemoriesForAgent(Long userId) {
        log.info("Agent调用获取所有私有记忆: userId={}", userId);
        return getAllPrivateMemories(userId);
    }

    @AgentTool(
            name = "retrieve_public_knowledge",
            description = "从公共知识库中检索生活小技巧、系统说明等通用知识。"
    )
    public List<KnowledgeResult> retrievePublicKnowledgeForAgent(Long userId, String query, Integer topK) {
        log.info("Agent调用检索公共知识: userId={}, query={}, topK={}", userId, query, topK);
        return retrievePublicKnowledge(query, topK);
    }

    // ==================== 动态topK ====================

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

    @Data
    @NoArgsConstructor
    public static class PresetKnowledge {
        private String content;
        private String category;
    }
}
