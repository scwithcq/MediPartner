package com.dsc.medipartner.module.review.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.review.domain.dto.AppendReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.CreateReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.ReplyReviewRequest;
import com.dsc.medipartner.module.review.domain.dto.ReviewQuery;
import com.dsc.medipartner.module.review.domain.dto.WorkerReviewQuery;
import com.dsc.medipartner.module.review.domain.vo.OrderReviewVO;
import com.dsc.medipartner.module.review.domain.vo.PublicReviewVO;
import com.dsc.medipartner.module.review.domain.vo.ReviewVO;
import com.dsc.medipartner.module.review.domain.vo.WorkerReviewVO;

import java.util.List;
import java.util.Map;

/**
 * 评价体系（契约 §5.11~§5.13 用户侧、§6.3 公开列表、§7.20~§7.21 陪诊师侧）。
 * 评价是唯一会改动 worker_profile 冗余统计与信用分的入口，统计口径集中在这里，避免多处各算一套。
 */
public interface ReviewService {

    /** §5.11 评价陪诊师：订单须已完成、一单一评，写完回写好评率并联动信用分 */
    ReviewVO create(Long userId, String orderNo, CreateReviewRequest request);

    /** §5.12 查看该订单的双向评价 */
    OrderReviewVO getOrderReviews(Long userId, String orderNo);

    /** §5.13 追评：评价后 7 天内、一评一追 */
    ReviewVO append(Long userId, String orderNo, AppendReviewRequest request);

    /** §6.3 陪诊师公开评价列表（匿名脱敏在服务端完成） */
    IPage<PublicReviewVO> pagePublicReviews(Long workerUserId, ReviewQuery query);

    /** §6.2 详情页最近 N 条评价 */
    List<PublicReviewVO> recentPublicReviews(Long workerUserId, int limit);

    /** §6.2 星级分布，键 "5"~"1" 恒全量返回，无评价的星级补 0 */
    Map<String, Integer> starDistribution(Long workerUserId);

    /** §7.20 陪诊师收到的评价 */
    IPage<WorkerReviewVO> pageWorkerReviews(Long workerId, WorkerReviewQuery query);

    /** §7.21 回复评价：未回复且 7 天内，一评价一回复 */
    WorkerReviewVO reply(Long workerId, Long reviewId, ReplyReviewRequest request);
}
