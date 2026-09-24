package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.ai.domain.dto.AiAssistRequest;
import com.dsc.medipartner.module.ai.domain.dto.AssistRegenerateRequest;
import com.dsc.medipartner.module.ai.domain.vo.AiAssistVO;

/**
 * AI 能力服务（接口文档 §4.1 生成推荐 / §4.2 重新生成 / 阶段3 服务报告）。
 */
public interface AiService {

    /** AI 发单助手：RAG 检索 -> 模型/降级生成 -> 建 OPEN 工单 -> 写快照 */
    AiAssistVO assist(Long userId, AiAssistRequest request);

    /** 重新生成：仅 OPEN 工单，同单上限 medi.ai.max-regenerate 次 */
    AiAssistVO regenerate(Long userId, AssistRegenerateRequest request);

    /** 订单完成后异步生成服务报告（阶段 3 实装，当前为骨架） */
    void generateReport(Long orderId);
}
