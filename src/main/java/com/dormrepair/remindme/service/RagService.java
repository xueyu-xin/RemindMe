package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.dto.response.KnowledgeResult;
import com.dormrepair.remindme.enums.MemoryCategory;
import com.dormrepair.remindme.agent.event.PrivateMemoryChangedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
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

    // LLM 服务：记忆标题由 AI 概括生成（新增/更新时自动触发）
    private final LLMService llmService;

    // 主动检索的相似度门槛：低于该分数的弱相关结果直接过滤，避免"问A答B"式罗列
    // 阈值提高至 0.45，只保留强相关记忆，减少上下文污染
    private static final double RETRIEVE_SIMILARITY_THRESHOLD = 0.45;

    // Spring 事件发布器：记忆增删成功后发布变更事件，由监听器推送 WebSocket 通知前端
    private final ApplicationEventPublisher eventPublisher;

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
    // 顺便解析 metadata 里的 title、category、remark、userId、createTime 等字段。
    private KnowledgeResult toKnowledgeResult(Document doc, String source) {
        Map<String, Object> metadata = doc.getMetadata();
        String categoryStr = metadata != null && metadata.get("category") != null
                ? metadata.get("category").toString() : null;
        Long docUserId = metadata != null && metadata.get("userId") != null
                ? Long.valueOf(metadata.get("userId").toString()) : null;
        String titleStr = metadata != null && metadata.get("title") != null
                ? metadata.get("title").toString() : null;
        String remarkStr = metadata != null && metadata.get("remark") != null
                ? metadata.get("remark").toString() : null;

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

        LocalDateTime updateTime = null;
        if (metadata != null && metadata.get("updateTime") != null) {
            try {
                updateTime = LocalDateTime.ofInstant(
                        Instant.parse(metadata.get("updateTime").toString()),
                        ZoneId.systemDefault()
                );
            } catch (Exception e) {
                log.warn("解析 updateTime 失败: {}", metadata.get("updateTime"));
            }
        }

        return KnowledgeResult.builder()
                .id(doc.getId())
                .content(doc.getText())
                .title(titleStr)
                .score(doc.getScore())
                .category(categoryStr)
                .remark(remarkStr)
                .userId(docUserId)
                .source(source)
                .createTime(createTime)
                .updateTime(updateTime)
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

    // 新增一条私有记忆，写入用户独立 collection，返回生成的文档 ID；失败返回 null。
    // 分类与标题均由 AI 按内容生成，成功后发布 ADD 变更事件
    public String addPrivateMemory(Long userId, String content) {
        return addPrivateMemoryInternal(userId, content, null, null);
    }

    // 完整写入入口：title 透传（null 时 AI 生成），remark 透传（null 时不写），新建时 id 与创建时间均为新生成
    private String addPrivateMemoryInternal(Long userId, String content, String title, String remark) {
        return addMemoryDocument(userId, UUID.randomUUID().toString(), content, title, remark, null, "添加私有记忆");
    }

    // 通用写入入口：docId 与 createTime 由调用方决定（新建时传 null 即新生成/取当前时刻），
    // 写库成功后发布 ADD 变更事件，失败返回 null
    private String addMemoryDocument(Long userId, String docId, String content, String title, String remark,
                                     Instant createTime, String logAction) {
        log.info("{}: userId={}, docId={}", logAction, userId, docId);
        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            Map<String, Object> metadata = buildMemoryMetadata(userId, content, title, remark, createTime);

            Document document = new Document(docId, content, metadata);
            callWithRetry(() -> { userStore.add(List.of(document)); return true; }, logAction);

            log.info("{}成功: docId={}, userId={}", logAction, docId, userId);
            eventPublisher.publishEvent(new PrivateMemoryChangedEvent("ADD", userId, docId));
            return docId;
        } catch (Exception e) {
            log.error("{}失败: userId={}, error={}", logAction, userId, e.getMessage(), e);
            return null;
        }
    }

    // 构建记忆 metadata：分类由 AI 从 MemoryCategory 枚举中选定（失败降级 OTHER），标题由 AI 概括，备注透传。
    // createTime 为空时取当前时刻（新建），非空时沿用原值（更新场景）；updateTime 始终为当前时刻
    private Map<String, Object> buildMemoryMetadata(Long userId, String content, String title, String remark,
                                                    Instant createTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", userId);
        metadata.put("createTime", createTime != null ? createTime.toString() : Instant.now().toString());
        metadata.put("updateTime", Instant.now().toString());

        String category = llmService.classifyMemoryCategory(content);
        metadata.put("category", category != null ? category : MemoryCategory.OTHER.name());

        String effectiveTitle = title != null && !title.isEmpty() ? title : generateTitle(content);
        if (effectiveTitle != null && !effectiveTitle.isEmpty()) {
            metadata.put("title", effectiveTitle);
        }
        if (remark != null && !remark.isEmpty()) {
            metadata.put("remark", remark);
        }
        return metadata;
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
            eventPublisher.publishEvent(new PrivateMemoryChangedEvent("DELETE", userId, memoryId));
            return true;
        } catch (Exception e) {
            log.error("删除私人记忆失败: memoryId={}, userId={}, error={}",
                    memoryId, userId, e.getMessage(), e);
            return false;
        }
    }

    // 更新私有记忆内容：先删旧文档，再复用原 id 与创建时间重写，内容/分类/标题更新、备注保留、
    // 更新时间刷新为当前时刻；返回该记忆 id，失败返回 null。写入成功后发布 ADD 变更事件
    public String updatePrivateMemory(String memoryId, Long userId, String newContent) {
        if (memoryId == null || memoryId.isEmpty()
                || userId == null
                || newContent == null || newContent.isEmpty()) {
            log.warn("更新记忆参数非法: memoryId={}, userId={}, newContent={}",
                    memoryId, userId, newContent);
            return null;
        }

        log.info("更新私人记忆: memoryId={}, userId={}", memoryId, userId);
        try {
            KnowledgeResult oldMemory = findMemoryById(memoryId, userId);
            if (oldMemory == null) {
                log.warn("更新失败：旧文档不存在, memoryId={}, userId={}", memoryId, userId);
                return null;
            }

            ChromaVectorStore userStore = getUserVectorStore(userId);
            callWithRetry(() -> { userStore.delete(List.of(memoryId)); return true; }, "删除旧私有记忆");

            // 复用原 id 与创建时间重写新文档，更新时间由 buildMemoryMetadata 刷新为当前时刻
            Instant oldCreateTime = oldMemory.getCreateTime() == null
                    ? null
                    : oldMemory.getCreateTime().atZone(ZoneId.systemDefault()).toInstant();
            String newDocId = addMemoryDocument(userId, memoryId, newContent, null,
                    oldMemory.getRemark(), oldCreateTime, "更新私有记忆");
            if (newDocId == null) {
                log.error("更新失败：添加新文档失败，旧文档已删除！memoryId={}, userId={}",
                        memoryId, userId);
                return null;
            }

            // 更新成功后发布事件，通知记忆变更监听方（与 ADD/DELETE 路径保持一致）
            eventPublisher.publishEvent(new PrivateMemoryChangedEvent("UPDATE", userId, memoryId));

            log.info("私人记忆更新成功: memoryId={}, userId={}", memoryId, userId);
            return newDocId;
        } catch (Exception e) {
            log.error("更新私人记忆异常: memoryId={}, userId={}, error={}",
                    memoryId, userId, e.getMessage(), e);
            return null;
        }
    }

    // 更新记忆备注：仅替换备注并刷新更新时间，其余字段保持不变（备注是用户唯一可手动编辑的字段）
    public boolean updateMemoryRemark(String memoryId, Long userId, String remark) {
        if (memoryId == null || memoryId.isEmpty() || userId == null) {
            log.warn("更新备注参数非法: memoryId={}, userId={}", memoryId, userId);
            return false;
        }

        log.info("更新记忆备注: memoryId={}, userId={}", memoryId, userId);
        try {
            KnowledgeResult oldMemory = findMemoryById(memoryId, userId);
            if (oldMemory == null) {
                log.warn("更新备注失败：旧文档不存在, memoryId={}, userId={}", memoryId, userId);
                return false;
            }

            boolean deleted = deletePrivateMemory(memoryId, userId);
            if (!deleted) {
                log.error("更新备注失败：删除旧文档失败, memoryId={}, userId={}", memoryId, userId);
                return false;
            }

            ChromaVectorStore userStore = getUserVectorStore(userId);
            // 复用原文档 id，仅替换备注重写
            String docId = memoryId;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("createTime", oldMemory.getCreateTime() == null
                    ? Instant.now().toString()
                    : oldMemory.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toString());
            metadata.put("updateTime", Instant.now().toString());
            if (oldMemory.getCategory() != null && !oldMemory.getCategory().isEmpty()) {
                metadata.put("category", oldMemory.getCategory());
            }
            if (oldMemory.getTitle() != null && !oldMemory.getTitle().isEmpty()) {
                metadata.put("title", oldMemory.getTitle());
            }
            if (remark != null && !remark.isEmpty()) {
                metadata.put("remark", remark);
            }

            Document document = new Document(docId, oldMemory.getContent(), metadata);
            callWithRetry(() -> { userStore.add(List.of(document)); return true; }, "更新记忆备注");

            log.info("记忆备注更新成功: oldMemoryId={}, newDocId={}, userId={}", memoryId, docId, userId);
            return true;
        } catch (Exception e) {
            log.error("更新记忆备注异常: memoryId={}, userId={}, error={}", memoryId, userId, e.getMessage(), e);
            return false;
        }
    }

    // 生成记忆标题：先让 AI 概括核心内容，失败时降级为正文开头截断
    private String generateTitle(String content) {
        String aiTitle = llmService.generateMemoryTitle(content);
        if (aiTitle != null && !aiTitle.isBlank()) {
            return aiTitle;
        }
        return autoGenerateTitle(content);
    }

    // 自动生成记忆标题：取正文去掉换行后的开头若干字，供 AI 标题失败时降级使用
    private String autoGenerateTitle(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String plain = content.replaceAll("\\s+", " ").trim();
        return plain.length() <= 20 ? plain : plain.substring(0, 20);
    }

    // 按 ID 查找某条记忆：拉全量记忆后过滤，找不到返回 null
    private KnowledgeResult findMemoryById(String memoryId, Long userId) {
        List<KnowledgeResult> allMemories = getAllPrivateMemories(userId);
        if (allMemories == null || allMemories.isEmpty()) {
            return null;
        }

        return allMemories.stream()
                .filter(item -> memoryId.equals(item.getId()))
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

    // 在用户私有记忆中做向量相似度检索，topK 仅作为返回数量上限，实际返回条数由相似度阈值控制；
    // 低于相似度门槛的弱相关结果会被过滤，结果按相似度分数倒序返回。
    public List<KnowledgeResult> retrievePrivateKnowledge(Long userId, String query, Integer topK) {
        int maxResults = (topK != null && topK > 0) ? topK : 50;
        log.info("检索私人记忆: userId={}, query={}, topK={}", userId, query, maxResults);

        try {
            ChromaVectorStore userStore = getUserVectorStore(userId);
            // topK 仅作为上限，实际返回数量由 similarityThreshold 控制
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(maxResults)
                    .similarityThreshold(RETRIEVE_SIMILARITY_THRESHOLD)
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

            // 最多返回 10 条记忆，保证 Token 消耗可控
            // 同时保留 topK 参数作为检索池大小控制（LLM 可传）
            if (results.size() > 10) {
                results = results.subList(0, 10);
                log.debug("检索结果截断: 原始 {} 条，截断后 10 条", documents.size());
            }

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
                    + "返回的每条记忆包含分类、标题、内容和备注（备注是用户手写的补充说明），回答前请连同备注一起阅读。"
                    + " topK：可选参数，含义为「最多返回多少条记忆」，默认 50。实际返回数量由相似度阈值（0.45）决定，只返回强相关记忆。"
                    + "适用于回答关于用户个人信息、偏好、计划等具体问题。不适用于需要列出全部记忆或从全量数据中筛选的场景（此类场景请使用 get_all_private_memories）。"
    )
    public Map<String, Object> retrievePrivateKnowledgeForAgent(Long userId, String query, Integer topK) {
        log.info("Agent调用检索私有记忆: userId={}, query={}, topK={}", userId, query, topK);

        try {
            List<KnowledgeResult> results = retrievePrivateKnowledge(userId, query, topK);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", results.isEmpty() ? "未找到相关记忆"
                    : "已返回 " + results.size() + " 条候选记忆");
            result.put("count", results.size());
            result.put("memories", results);

            log.info("Agent检索私有记忆成功: userId={}, count={}", userId, results.size());
            return result;
        } catch (Exception e) {
            log.error("Agent检索私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "记忆检索失败，请稍后再试。");
            errorResult.put("count", 0);
            errorResult.put("memories", Collections.emptyList());
            return errorResult;
        }
    }

    // Agent 工具：新增用户私有记忆
    @AgentTool(
            name = "add_private_memory",
            description = """
                    把对方告诉你的、关于 TA 自己的信息存入助手记忆，包括但不限于：
                    - 个人信息（如"你是大三学生"、"你是计算机专业"）
                    - 活动事务（如"你国庆要去长沙旅游"、"你下周三要社团摆摊"）
                    - 重要日期（如"你妈妈的生日是10月16号"）
                    - 长期计划（如"你要考研"）
                    - 考试安排（如"你6月有四级考试"）
                    - 健康状况（如"你对海鲜过敏"）
                    - 饮食偏好（如"你喜欢吃苹果"）
                    - 日常习惯（如"你每天12点半睡"、"你每周三下午有高数课"）
                    - 兴趣爱好（如"你喜欢打篮球"）
                    - 语言风格（如"你喜欢简洁的回答"）

                    重要——content 的人称规则：一般直接记录事实本身，不写人称
                    （如"大三计算机专业学生"、"6月20日期末考试"、"每天早上7点起床"）；
                    如果必须涉及人称，一律用"你"称呼用户（如"你喜欢打篮球"），
                    严禁写成"用户喜欢打篮球"或"我喜欢打篮球"（"我"是助手，会混淆）。
                    只要对方说了关于自己的事实信息，都应该调用此工具存储。

                    注意：只需传 content，分类与标题由 AI 按内容自动生成；
                    备注由用户在记忆库页面手写，助手无需（也无法）写入。"""
    )
    public Map<String, Object> addPrivateMemoryForAgent(Long userId, String content) {
        log.info("Agent调用添加私有记忆: userId={}", userId);

        try {
            String memoryId = addPrivateMemory(userId, content);

            Map<String, Object> result = new HashMap<>();
            if (memoryId != null && !memoryId.isEmpty()) {
                result.put("success", true);
                result.put("message", "记忆已成功存储");
                result.put("memoryId", memoryId);
                result.put("content", content);
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
            errorResult.put("message", "记忆存储失败，请稍后再试。");
            return errorResult;
        }
    }

    // Agent 工具：更新用户私有记忆
    @AgentTool(
            name = "update_private_memory",
            description = "更新用户私有知识库中的某条记忆内容。调用前必须先用 retrieve_private_knowledge 或 "
                    + "get_all_private_memories 查到目标记忆的 ID。更新后分类与标题由 AI 按新内容重新生成，用户手写的备注会保留。"
    )
    public Map<String, Object> updatePrivateMemoryForAgent(Long userId, String memoryId, String newContent) {
        log.info("Agent调用更新私有记忆: userId={}, memoryId={}", userId, memoryId);

        try {
            // 更新采用"先删后写"，返回新文档 ID；分类与标题由 AI 重新生成，备注保留
            String newDocId = updatePrivateMemory(memoryId, userId, newContent);

            Map<String, Object> result = new HashMap<>();
            if (newDocId != null) {
                result.put("success", true);
                result.put("message", "记忆已成功更新");
                result.put("memoryId", newDocId);
                log.info("Agent更新私有记忆成功: oldMemoryId={}, newMemoryId={}, userId={}", memoryId, newDocId, userId);
            } else {
                result.put("success", false);
                result.put("message", "记忆更新失败，可能是memoryId不存在或参数非法");
            }

            return result;
        } catch (Exception e) {
            log.error("Agent更新私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "记忆更新失败，请稍后再试。");
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
            errorResult.put("message", "记忆删除失败，请稍后再试。");
            return errorResult;
        }
    }

    // Agent 工具：列出用户全部私有记忆
    @AgentTool(
            name = "get_all_private_memories",
            description = "获取用户私有知识库中的所有记忆，每条包含分类、标题、内容和备注。"
                    + "适合用户想整体回顾记忆、或按分类/关键词浏览全部记忆时使用。"
                    + "适用于用户明确要求查看所有记忆、或需要从全量数据中筛选特定内容的场景，如'查看所有记忆'、'把所有健康相关的记忆都列出来'。"
    )
    public Map<String, Object> getAllPrivateMemoriesForAgent(Long userId) {
        log.info("Agent调用获取所有私有记忆: userId={}", userId);

        try {
            List<KnowledgeResult> results = getAllPrivateMemories(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", results.isEmpty() ? "记忆库为空" : "已返回 " + results.size() + " 条记忆");
            result.put("count", results.size());
            result.put("memories", results);

            log.info("Agent获取所有私有记忆成功: userId={}, count={}", userId, results.size());
            return result;
        } catch (Exception e) {
            log.error("Agent获取所有私有记忆失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "获取记忆失败，请稍后再试。");
            errorResult.put("count", 0);
            errorResult.put("memories", Collections.emptyList());
            return errorResult;
        }
    }

    // Agent 工具：检索公共知识库（系统使用手册）
    @AgentTool(
            name = "retrieve_public_knowledge",
            description = "从系统使用手册中检索功能帮助信息。手册内容涵盖：提醒管理、天气查询、个人记忆、经期管理、用户设置、位置与时间等功能的操作说明。"
                    + "调用时机：仅在用户明确询问系统功能/操作/使用方法时调用，例如：\"怎么创建提醒\"、\"能查天气吗\"、\"支持什么功能\"、\"如何设置经期\"、\"这软件能做什么\"。"
                    + "不调用时机：用户正在执行具体操作（创建提醒、查天气、问个人记忆、管理设置等）时，不要调用此工具。"
                    + "示例：用户说\"提醒我明天开会\" → 不调用；用户说\"怎么创建提醒\" → 调用。"
    )
    public Map<String, Object> retrievePublicKnowledgeForAgent(Long userId, String query, Integer topK) {
        log.info("Agent调用检索公共知识: userId={}, query={}, topK={}", userId, query, topK);

        try {
            List<KnowledgeResult> results = retrievePublicKnowledge(query, topK);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", results.isEmpty() ? "未找到相关公共知识" : "已返回 " + results.size() + " 条知识");
            result.put("count", results.size());
            result.put("knowledge", results);

            log.info("Agent检索公共知识成功: userId={}, count={}", userId, results.size());
            return result;
        } catch (Exception e) {
            log.error("Agent检索公共知识失败: userId={}, error={}", userId, e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "知识检索失败，请稍后再试。");
            errorResult.put("count", 0);
            errorResult.put("knowledge", Collections.emptyList());
            return errorResult;
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
