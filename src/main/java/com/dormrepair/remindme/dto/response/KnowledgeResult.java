package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// 知识检索结果 DTO。RAG 接口（私人记忆检索/公共知识检索）返回的单条命中记录，承载文本内容、相似度、来源分类及扩展元数据
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeResult {

    // Chroma 中的文档 id，更新/删除记忆时通过此 id 唯一定位
    private String id;

    // 知识/记忆的纯文本内容，AI 拿到后据此回答用户问题
    private String content;

    // 记忆标题，从 metadata.title 提取，用户未填写时为 null，前端可降级用正文截断展示
    private String title;

    // 相似度分数，范围 0~1，越接近 1 代表与查询语义越匹配
    private double score;

    // 知识分类编码：私有记忆为 MemoryCategory 枚举名（如 DIET_PREFERENCE）；公共知识库用其区分手册分类
    private String category;

    // 私有记忆备注：唯一允许用户手动编辑的字段，AI 存储时为空
    private String remark;

    // 所属用户 id：私人记忆记录归属用户；公共知识此项为 null
    private Long userId;

    // 来源标识：PUBLIC（公共知识库，所有用户共享）或 PRIVATE（当前用户私人记忆）
    private String source;

    // 记忆创建时间，便于按时间查看与排序
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createTime;

    // 记忆最后更新时间：新建时等于创建时间，内容或备注更新后刷新为更新时刻
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updateTime;

    // 扩展元数据，可存储文档来源 URL、原始文件名等自由字段
    private Map<String, Object> metadata;
}
