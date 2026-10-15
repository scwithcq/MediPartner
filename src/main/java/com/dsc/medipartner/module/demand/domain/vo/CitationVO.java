package com.dsc.medipartner.module.demand.domain.vo;

import lombok.Data;

/**
 * AI 推荐引用来源（§4.1 citations 元素）。docId 走 Long->String 全局序列化为 string。
 */
@Data
public class CitationVO {

    private String docId;
    private String docTitle;
    /** 切片摘录，<= 200 字 */
    private String sliceText;
    /** 完整引用切片，用于展开查看；旧快照详情按原文匹配补齐。 */
    private String fullSliceText;
    private String sliceId;
}
