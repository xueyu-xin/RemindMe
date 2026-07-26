package com.dormrepair.remindme.config;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChromaConfig {

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
