package com.dsc.medipartner.module.knowledge.bootstrap;

import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeDoc;
import com.dsc.medipartner.module.knowledge.service.KnowledgePipeline;
import com.dsc.medipartner.module.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期内置语料导入：classpath:knowledge/*.md -> knowledge_doc(SEED) -> 同步解析切片 -> 向量库重建。
 * 同步执行保证应用就绪即可检索；失败只记日志不阻断启动。medi.knowledge.auto-seed=false 可关闭。
 */
@Slf4j
@Component
public class KnowledgeSeeder implements ApplicationRunner {

    private final KnowledgeService knowledgeService;
    private final KnowledgePipeline pipeline;
    private final boolean autoSeed;

    public KnowledgeSeeder(KnowledgeService knowledgeService,
                           KnowledgePipeline pipeline,
                           @Value("${medi.knowledge.auto-seed:true}") boolean autoSeed) {
        this.knowledgeService = knowledgeService;
        this.pipeline = pipeline;
        this.autoSeed = autoSeed;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoSeed) {
            return;
        }
        try {
            List<KnowledgeDoc> imported = knowledgeService.importSeedDocuments();
            for (KnowledgeDoc doc : imported) {
                pipeline.processDocument(doc.getId());
            }
            knowledgeService.rebuildVectorStoreIfEmpty();
            if (!imported.isEmpty()) {
                log.info("[knowledge] 内置语料导入完成，本次新增 {} 篇", imported.size());
            }
        } catch (Exception e) {
            log.error("[knowledge] 内置语料导入失败（不影响应用启动）", e);
        }
    }
}
