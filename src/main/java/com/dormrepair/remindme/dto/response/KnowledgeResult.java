package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识检索结果DTO
 * <p>
 * 用于封装从向量数据库（Chroma）检索到的知识条目，
 * 包含文档内容、相似度分数、分类及元数据等信息。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeResult {

    /**
     * 文档ID（Chroma中的唯一标识）
     */
    private String id;

    /**
     * 文本内容
     */
    private String content;

    /**
     * 相似度分数（0~1，越高越相关）
     */
    private double score;

    /**
     * 知识分类（如：PERSONAL_INFO、COURSE、PLAN 等）
     */
    private String category;

    /**
     * 所属用户ID（公共知识为null）
     */
    private Long userId;

    /**
     * 来源标识：PUBLIC（公共知识库）/ PRIVATE（用户私有记忆）
     */
    private String source;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createTime;

    /**
     * 附加元数据
     */
    private Map<String, Object> metadata;
}
