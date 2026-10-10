package com.dsc.medipartner.module.review.domain.row;

import lombok.Data;

/** 星级分布聚合行（§6.2 starDistribution）。 */
@Data
public class StarCountRow {

    private Integer star;
    private Integer cnt;
}
