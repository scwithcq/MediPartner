package com.dsc.medipartner.module.review.domain.row;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 陪诊师评价聚合统计（§5.11 联动）：写回 worker_profile 的 good_rate / review_count / avg_star。
 * 无评价时 avgStar 为 null，由服务层兜底 5.0。
 */
@Data
public class ReviewStatsRow {

    private Integer reviewCount;
    /** star >= 4 计好评 */
    private Integer goodCount;
    private BigDecimal avgStar;
}
