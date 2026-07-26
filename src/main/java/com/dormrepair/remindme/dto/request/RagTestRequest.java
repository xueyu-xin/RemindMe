package com.dormrepair.remindme.dto.request;

import com.dormrepair.remindme.enums.MemoryCategory;
import lombok.Data;

/**
 * RAG 测试接口的请求 DTO 集合
 * <p>
 * 为减少文件数量，6 个测试接口的请求 DTO 合并到此文件中作为内部静态类。
 * 每个 DTO 对应一个测试接口的请求参数。
 * </p>
 */
public class RagTestRequest {

    /**
     * 检索私人记忆请求
     */
    @Data
    public static class RetrievePrivateKnowledgeRequest {
        /** 用户ID */
        private Long userId;
        /** 查询文本 */
        private String query;
        /** 返回条数；为 null 时动态计算 */
        private Integer topK;
    }

    /**
     * 添加私人记忆请求
     */
    @Data
    public static class AddPrivateMemoryRequest {
        /** 用户ID */
        private Long userId;
        /** 记忆内容 */
        private String content;
        /** 记忆分类 */
        private MemoryCategory category;
    }

    /**
     * 更新私人记忆请求
     */
    @Data
    public static class UpdatePrivateMemoryRequest {
        /** 原文档ID */
        private String memoryId;
        /** 用户ID（权限校验） */
        private Long userId;
        /** 新内容 */
        private String newContent;
    }

    /**
     * 删除私人记忆请求
     */
    @Data
    public static class DeletePrivateMemoryRequest {
        /** 文档ID */
        private String memoryId;
        /** 用户ID（权限校验） */
        private Long userId;
    }

    /**
     * 获取所有私人记忆请求
     */
    @Data
    public static class GetAllPrivateMemoriesRequest {
        /** 用户ID */
        private Long userId;
    }

    /**
     * 检索公共知识请求
     */
    @Data
    public static class RetrievePublicKnowledgeRequest {
        /** 查询关键词 */
        private String query;
        /** 返回条数；为 null 时默认取 5 */
        private Integer topK;
    }
}
