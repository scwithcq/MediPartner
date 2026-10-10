package com.dsc.medipartner.module.review.domain.vo;

import lombok.Data;

/** 某订单的双向评价（契约 §5.12）：workerReview 为陪诊师对我的评价，无则键缺失。 */
@Data
public class OrderReviewVO {

    private ReviewVO myReview;
    private ReviewVO workerReview;
}
