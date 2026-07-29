package com.dormrepair.remindme.dto.request;

import com.dormrepair.remindme.enums.MemoryCategory;
import lombok.Data;

// RAG 测试接口请求 DTO 集合。把 6 个调试接口的请求体合并为一个文件，通过内部静态类区分，避免 DTO 文件过多
public class RagTestRequest {

    // 检索私人记忆请求：用于 /api/rag/private/search 接口，按向量相似度从 Chroma 私人集合中取相关记忆
    @Data
    public static class RetrievePrivateKnowledgeRequest {
        // 用户 id，鉴权用，限定只在该用户的私人记忆空间内检索
        private Long userId;
        // 查询文本，会向量化后做余弦相似度匹配
        private String query;
        // 返回 TopK 条数，null 时走服务端默认策略（按置信度动态计算）
        private Integer topK;
    }

    // 添加私人记忆请求：用于 /api/rag/private/add 接口，把一条新记忆写入用户的私人 Chroma 集合
    @Data
    public static class AddPrivateMemoryRequest {
        // 用户 id，标识记忆归属
        private Long userId;
        // 记忆文本内容，会被切分并向量化后落库
        private String content;
        // 记忆分类，用于后续按类型过滤，可选：日常习惯/偏好设置/重要日期等
        private MemoryCategory category;
    }

    // 更新私人记忆请求：用于 /api/rag/private/update 接口，修改已有记忆的文本内容
    @Data
    public static class UpdatePrivateMemoryRequest {
        // 记忆文档 id（Chroma 中生成的 ID），唯一定位要修改的条目
        private String memoryId;
        // 用户 id（权限校验），防止修改别人的记忆
        private Long userId;
        // 新内容，会重新向量化覆盖原始向量
        private String newContent;
    }

    // 删除私人记忆请求：用于 /api/rag/private/delete 接口，按文档 id 物理删除私人记忆条目
    @Data
    public static class DeletePrivateMemoryRequest {
        // 记忆文档 id
        private String memoryId;
        // 用户 id（权限校验）
        private Long userId;
    }

    // 获取所有私人记忆请求：用于 /api/rag/private/list 接口，拉取某个用户私人集合中的全部记忆
    @Data
    public static class GetAllPrivateMemoriesRequest {
        // 用户 id，限定拉取范围
        private Long userId;
    }

    // 检索公共知识请求：用于 /api/rag/public/search 接口，从公共知识集合（如使用说明、常见问题）中检索答案
    @Data
    public static class RetrievePublicKnowledgeRequest {
        // 查询关键词/问题文本
        private String query;
        // 返回 TopK 条数，null 时默认 5 条
        private Integer topK;
    }
}
