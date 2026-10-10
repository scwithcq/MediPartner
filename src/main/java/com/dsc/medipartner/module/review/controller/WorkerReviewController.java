package com.dsc.medipartner.module.review.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.review.domain.dto.ReplyReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.WorkerReviewQuery;
import com.dsc.medipartner.module.review.domain.vo.WorkerReviewVO;
import com.dsc.medipartner.module.review.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 陪诊师侧评价（契约 §7.20~§7.21）：收到的评价列表与回复。
 * workerId 取自登录态，回复归属校验在 ReviewService 内完成。
 */
@RestController
@RequestMapping("/api/worker/review")
public class WorkerReviewController {

    private final ReviewService reviewService;

    public WorkerReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** §7.20 我收到的评价，可按星级 / 待回复筛选 */
    @GetMapping("/list")
    public R<PageResult<WorkerReviewVO>> list(WorkerReviewQuery query) {
        IPage<WorkerReviewVO> page = reviewService.pageWorkerReviews(UserContext.userId(), query);
        return R.ok(PageResult.of(page));
    }

    /** §7.21 回复评价：未回复且 7 天内，一评价一回复 */
    @PostMapping("/{reviewId}/reply")
    public R<WorkerReviewVO> reply(@PathVariable Long reviewId, @Valid @RequestBody ReplyReviewRequest request) {
        return R.ok(reviewService.reply(UserContext.userId(), reviewId, request));
    }
}
