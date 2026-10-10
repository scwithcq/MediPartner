package com.dsc.medipartner.module.user.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.module.review.domain.dto.ReviewQuery;
import com.dsc.medipartner.module.review.domain.vo.PublicReviewVO;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.user.domain.dto.WorkerQuery;
import com.dsc.medipartner.module.user.domain.vo.WorkerCardVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerDetailVO;
import com.dsc.medipartner.module.user.service.WorkerBrowseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 陪诊师浏览（契约 §6.1 列表 / §6.2 详情 / §6.3 公开评价）。用户侧只读接口，
 * 与 UserController 的 /api/user/profile 不冲突（本控制器统一挂 /api/user/worker 前缀）。
 */
@RestController
@RequestMapping("/api/user/worker")
public class WorkerBrowseController {

    private final WorkerBrowseService workerBrowseService;
    private final ReviewService reviewService;

    public WorkerBrowseController(WorkerBrowseService workerBrowseService, ReviewService reviewService) {
        this.workerBrowseService = workerBrowseService;
        this.reviewService = reviewService;
    }

    /** §6.1 陪诊师列表 */
    @GetMapping("/list")
    public R<PageResult<WorkerCardVO>> list(WorkerQuery query) {
        IPage<WorkerCardVO> page = workerBrowseService.pageWorkers(query);
        return R.ok(PageResult.of(page));
    }

    /** §6.2 陪诊师详情 */
    @GetMapping("/{workerUserId}")
    public R<WorkerDetailVO> detail(@PathVariable Long workerUserId) {
        return R.ok(workerBrowseService.getDetail(workerUserId));
    }

    /** §6.3 陪诊师公开评价列表（匿名脱敏在服务端完成） */
    @GetMapping("/{workerUserId}/reviews")
    public R<PageResult<PublicReviewVO>> reviews(@PathVariable Long workerUserId, ReviewQuery query) {
        IPage<PublicReviewVO> page = reviewService.pagePublicReviews(workerUserId, query);
        return R.ok(PageResult.of(page));
    }
}