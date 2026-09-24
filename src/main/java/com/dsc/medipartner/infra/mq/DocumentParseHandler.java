package com.dsc.medipartner.infra.mq;

/**
 * 解析任务的实际处理入口，由 KnowledgePipeline 实现；
 * MQ 组件只依赖本接口，避免 infra 反向依赖业务模块。
 */
public interface DocumentParseHandler {

    void handle(Long docId);
}
