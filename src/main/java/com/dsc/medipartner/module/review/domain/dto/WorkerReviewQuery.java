package com.dsc.medipartner.module.review.domain.dto;

import com.dsc.medipartner.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 陪诊师「收到的评价」查询（契约 §7.20）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkerReviewQuery extends PageQuery {

    /** 按星级筛选 1~5，不传看全部 */
    private Integer star;
    /** true 只看待回复：尚未回复且仍在 7 天回复窗口内 */
    private Boolean unreplied;
}
