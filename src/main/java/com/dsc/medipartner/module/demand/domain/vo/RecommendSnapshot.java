package com.dsc.medipartner.module.demand.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 推荐快照，序列化后存 demand_order.ai_recommend_json。
 * 历史工单不受知识库更新影响；degraded=true 表示未配置模型 Key 时的模板降级输出。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendSnapshot {

    /** qwen / zhipu */
    private String provider;
    /** 是否降级模板输出 */
    private Boolean degraded;
    /** yyyy-MM-dd HH:mm:ss */
    private String generatedAt;
    private List<DepartmentVO> departments;
}
