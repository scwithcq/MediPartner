package com.dsc.medipartner.module.review.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.review.domain.dto.AppendReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.CreateReviewRequest;
import com.dsc.medipartner.module.review.domain.vo.OrderReviewVO;
import com.dsc.medipartner.module.review.domain.vo.ReviewVO;
import com.dsc.medipartner.module.review.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧评价（契约 §5.11~§5.13）。与 OrderController 共用 /api/order 前缀但子路径不冲突：
 * 评价相关路径均带 /review 段。归属与状态校验在 ReviewService 内完成。
 */
@RestController
@RequestMapping("/api/order")
public class OrderReviewController {

    private final ReviewService reviewService;

    public OrderReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** §5.11 评价陪诊师：订单须已完成、一单一评 */
    @PostMapping("/{orderNo}/review")
    public R<ReviewVO> create(@PathVariable String orderNo, @Valid @RequestBody CreateReviewRequest request) {
        return R.ok(reviewService.create(UserContext.userId(), orderNo, request));
    }

    /** §5.12 查看该订单的双向评价 */
    @GetMapping("/{orderNo}/review")
    public R<OrderReviewVO> orderReviews(@PathVariable String orderNo) {
        return R.ok(reviewService.getOrderReviews(UserContext.userId(), orderNo));
    }

    /** §5.13 追评：评价后 7 天内、一评一追 */
    @PostMapping("/{orderNo}/review/append")
    public R<ReviewVO> append(@PathVariable String orderNo, @Valid @RequestBody AppendReviewRequest request) {
        return R.ok(reviewService.append(UserContext.userId(), orderNo, request));
    }
}
