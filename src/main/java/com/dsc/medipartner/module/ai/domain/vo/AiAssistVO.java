package com.dsc.medipartner.module.ai.domain.vo;

import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import lombok.Data;

import java.util.List;

/**
 * AI 发单助手响应（接口文档 §4.1，冻结契约）。
 * elapsedMs 用 Integer、DepartmentVO.confidence 用 Double，保证 JSON 输出为 number。
 * degraded=true 表示未配置模型 API Key，结果由本地规则模板生成。
 */
@Data
public class AiAssistVO {

    private String demandId;
    private String demandNo;
    /** 固定 OPEN */
    private String status;
    /** 固定 3 项，按置信度降序 */
    private List<DepartmentVO> departments;
    private String disclaimer;
    /** qwen / zhipu */
    private String provider;
    private Integer elapsedMs;
    /** yyyy-MM-dd HH:mm:ss */
    private String generatedAt;
    /** 是否降级模板输出 */
    private Boolean degraded;
}
