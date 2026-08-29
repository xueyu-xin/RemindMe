package com.dormrepair.remindme.dto.request;

import lombok.Data;

// 记忆库页面接口请求 DTO 集合。把记忆删除与备注更新相关接口的请求体合并为一个文件，通过内部静态类区分，避免 DTO 文件过多
public class MemoryRequest {

    // 删除单条记忆请求
    @Data
    public static class DeleteRequest {
        // 记忆文档 id
        private String memoryId;
        // 用户 id，权限校验
        private Long userId;
    }

    // 批量删除记忆请求：前端勾选多条后一次性提交，后端逐条删除并统计结果
    @Data
    public static class BatchDeleteRequest {
        // 用户 id，权限校验
        private Long userId;
        // 要删除的记忆文档 id 列表
        private java.util.List<String> memoryIds;
    }

    // 更新备注请求：备注是唯一允许用户手动编辑的字段，记忆内容与分类均由 AI 生成
    @Data
    public static class UpdateRemarkRequest {
        // 记忆文档 id（Chroma 中生成的 ID），唯一定位要修改的条目
        private String memoryId;
        // 用户 id，权限校验
        private Long userId;
        // 新备注内容，可为空字符串表示清空
        private String remark;
    }
}
