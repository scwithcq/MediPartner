package com.dsc.medipartner.module.knowledge.domain.enums;

/**
 * 知识库文档状态（V8 knowledge_doc.status）。
 */
public enum KnowledgeDocStatus {

    /** 待解析（上传/导入后初始态） */
    PENDING,
    /** 解析中 */
    PARSING,
    /** 已发布（切片可检索） */
    ACTIVE,
    /** 解析失败（可重新发布触发重试） */
    FAILED,
    /** 已下架（切片同步失效） */
    INACTIVE
}
