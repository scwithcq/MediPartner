package com.dsc.medipartner.module.knowledge.service;

/**
 * 知识库检索命中（向量或关键词兜底），供 AI 发单助手拼 Prompt 与生成引用来源。
 * sliceText 为完整切片文本，引用展示时由上层截断到 200 字。
 */
public record KnowledgeSearchHit(String sliceId, String docId, String docTitle, String sliceText, Double score) {
}
