package com.dsc.medipartner.module.demand.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 推荐科室（接口文档 4.1 departments 元素）。
 * confidence 用 Double 保证 JSON 输出为 number（BigDecimal 会被全局序列化成字符串）。
 */
@Data
public class DepartmentVO {

    private String departmentName;
    private String hospitalSuggest;
    /** 推荐理由，不超过 200 字 */
    private String reason;
    /** 置信度 0~1，两位小数 */
    private Double confidence;
    private List<CitationVO> citations;
    /** 建议服务类型码值（ServiceType 枚举名），一键下单预填 */
    private String suggestServiceType;
}
