package com.dormrepair.remindme.config;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// Chroma 向量数据库配置。构建 Spring AI 的 ChromaVectorStore 主 Bean，指定公共知识库集合、租户/数据库命名空间，并启用首次启动自动建表
@Configuration
public class ChromaConfig {

    // 公共知识库向量存储主 Bean
    // chromaApi       - Chroma 服务原生客户端（由 spring.ai.vectorstore.chroma.client.* 配置自动装配）
    // embeddingModel  - 文本转向量模型，项目统一使用 Ollama 的 qwen3-embedding:4b
    // tenantName      - Chroma 多租户隔离名，默认 default_tenant，可通过配置项覆盖
    // databaseName    - Chroma 数据库名，默认 default_database，可通过配置项覆盖
    // collectionName  - 固定使用 public_knowledge 集合，仅承载系统使用手册等公共知识库内容
    // initializeSchema- 设为 true，首次启动时若集合不存在会自动创建，避免手动建表步骤
    @Bean
    @Primary
    public ChromaVectorStore vectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel,
                                         @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}") String tenantName,
                                         @Value("${spring.ai.vectorstore.chroma.database-name:default_database}") String databaseName) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName("public_knowledge")
                .tenantName(tenantName)
                .databaseName(databaseName)
                .initializeSchema(true)
                .build();
    }
}
