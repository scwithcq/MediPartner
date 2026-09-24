package com.dsc.medipartner.module.knowledge.domain.enums;

/**
 * 知识库切片状态（V8 knowledge_slice.status）。
 * 注意：向量化失败不置 FAILED，切片仍为 ACTIVE（检索降级为关键词打分），vector_id 为 NULL。
 */
public enum KnowledgeSliceStatus {

    /** 待处理 */
    PENDING,
    /** 可检索 */
    ACTIVE,
    /** 处理失败（预留） */
    FAILED,
    /** 已失效（文档下架/重解析删除） */
    INACTIVE
}
