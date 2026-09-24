package com.dsc.medipartner.infra.vector;

import com.dsc.medipartner.module.ai.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * SimpleVectorStore 适配层：
 * - 向量文档 id = 切片 ID 字符串，metadata 记录 embeddingModel 实现换厂商隔离（filterExpression）；
 * - 持久化到 ${medi.file.base-dir}/vector-store.json，重启后 load；
 * - 未配置 API Key 时 ready()=false，上层降级为关键词检索。
 */
@Slf4j
@Component
public class VectorStoreAdapter {

    /** 入库条目：id=切片ID字符串，docId 仅作为 metadata 便于排查 */
    public record VectorDoc(String id, String text, String docId) {
    }

    /** 检索命中：id=切片ID字符串 */
    public record VectorHit(String id, String text, Double score) {
    }

    private final ChatModelFactory chatModelFactory;
    private final Path storeFile;
    private volatile SimpleVectorStore store;

    public VectorStoreAdapter(ChatModelFactory chatModelFactory,
                              @Value("${medi.file.base-dir:./data/files}") String baseDir) {
        this.chatModelFactory = chatModelFactory;
        this.storeFile = Paths.get(baseDir, "vector-store.json").toAbsolutePath().normalize();
    }

    /** 向量能力是否可用（有 Key 且 store 初始化成功） */
    public boolean ready() {
        return chatModelFactory.embeddingAvailable() && ensureStore() != null;
    }

    /** 批量写入切片向量（同 id 覆盖，换 embedding 模型后重建即靠这一点） */
    public synchronized void addBatch(List<VectorDoc> docs) {
        SimpleVectorStore s = ensureStore();
        if (s == null || docs == null || docs.isEmpty()) {
            return;
        }
        String modelName = chatModelFactory.embeddingModelName();
        List<Document> documents = docs.stream()
                .map(d -> new Document(d.id(), d.text(),
                        Map.of("embeddingModel", modelName, "docId", String.valueOf(d.docId()))))
                .toList();
        s.add(documents);
        persist();
    }

    public synchronized void removeBySliceIds(List<String> sliceIds) {
        SimpleVectorStore s = ensureStore();
        if (s == null || sliceIds == null || sliceIds.isEmpty()) {
            return;
        }
        s.delete(sliceIds);
        persist();
    }

    /** 相似度检索，仅命中当前 embeddingModel 的向量；不可用/无结果返回空列表 */
    public synchronized List<VectorHit> search(String query, int topK) {
        SimpleVectorStore s = ensureStore();
        if (s == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThresholdAll()
                    .filterExpression("embeddingModel == '" + chatModelFactory.embeddingModelName() + "'")
                    .build();
            List<Document> results = s.similaritySearch(request);
            if (results == null) {
                return List.of();
            }
            return results.stream()
                    .map(d -> new VectorHit(d.getId(), d.getText(), d.getScore()))
                    .toList();
        } catch (Exception e) {
            log.warn("[vector] 相似度检索失败，降级关键词检索: {}", e.getMessage());
            return List.of();
        }
    }

    /** 持久化文件是否已有向量数据（启动期判断是否需要全量重建） */
    public boolean hasPersistedData() {
        try {
            return Files.isRegularFile(storeFile) && Files.size(storeFile) > 16;
        } catch (IOException e) {
            return false;
        }
    }

    private SimpleVectorStore ensureStore() {
        if (!chatModelFactory.embeddingAvailable()) {
            return null;
        }
        SimpleVectorStore local = store;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (store == null) {
                try {
                    SimpleVectorStore created = SimpleVectorStore.builder(chatModelFactory.embeddingModel()).build();
                    if (Files.isRegularFile(storeFile)) {
                        created.load(storeFile.toFile());
                        log.info("[vector] 已加载本地向量库: {}", storeFile);
                    }
                    store = created;
                } catch (Exception e) {
                    log.error("[vector] 向量库初始化失败（本次运行降级关键词检索）: {}", e.getMessage());
                    return null;
                }
            }
            return store;
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storeFile.getParent());
            store.save(storeFile.toFile());
        } catch (Exception e) {
            log.warn("[vector] 向量库持久化失败（重启后将全量重建）: {}", e.getMessage());
        }
    }
}
