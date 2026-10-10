package com.dsc.medipartner.module.review.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 陪诊师评价列表查询（契约 §6.3）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReviewQuery extends PageQuery {

    /** 按星级筛选 1~5 */
    private Integer star;
    /** true 只看有文字内容的 */
    private Boolean hasContent;
    /** true 只看陪诊师已回复的 */
    private Boolean hasReply;
}
