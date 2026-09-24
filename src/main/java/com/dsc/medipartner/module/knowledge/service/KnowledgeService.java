package com.dsc.medipartner.module.knowledge.service;

import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeDoc;

import java.util.List;

/**
 * 知识库服务：导入/发布/检索。管理端上传接口在阶段 7 暴露，本阶段先落地服务能力。
 */
public interface KnowledgeService {

    /** 登记一份文档（同名自动升版本），返回新建的 PENDING 文档 */
    KnowledgeDoc importDocument(String title, String fileUrl, String source);

    /** 导入 classpath:knowledge/*.md 内置语料（SEED 来源按标题去重），返回本次新导入的文档 */
    List<KnowledgeDoc> importSeedDocuments();

    /** 发布文档：投递异步解析任务（已 ACTIVE/PARSING 时幂等跳过） */
    void publish(Long docId);

    /** 检索已发布文档的 ACTIVE 切片：向量优先，不可用/无命中时关键词兜底 */
    List<KnowledgeSearchHit> search(String query, int topK);

    /** ACTIVE 切片总数（0 表示知识库未发布，AI 能力返回 6001） */
    long countActiveSlices();

    /** 向量库持久化数据为空且存在 ACTIVE 切片时全量重建（同 id 覆盖旧向量） */
    void rebuildVectorStoreIfEmpty();
}
