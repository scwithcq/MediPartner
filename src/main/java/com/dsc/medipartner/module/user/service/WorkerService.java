package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.dto.UpdateWorkerProfileRequest;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.vo.WorkerOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerProfileVO;

import java.math.BigDecimal;

public interface WorkerService {

    WorkerProfile getByUserId(Long userId);

    /** 我的陪诊师资料（契约 §7.2）：档案不存在 2001 */
    WorkerProfileVO getProfile(Long workerUserId);

    /** 修改接单资料（契约 §7.3）：PENDING 期间 2003，intro 命中敏感词 1001 */
    WorkerProfileVO updateProfile(Long workerUserId, UpdateWorkerProfileRequest request);

    /** 评价产生后回写冗余统计列（good_rate / review_count / avg_star） */
    void updateReviewStats(Long workerUserId, int reviewCount, int goodCount, BigDecimal avgStar);

    /** 订单确认后已完成单数 +1 */
    void incrementServiceCount(Long workerUserId);

    /** §7.1 工作台首页聚合：昵称、信用、接单能力、各类计数与本月收益 */
    WorkerOverviewVO getOverview(Long workerUserId);
}
