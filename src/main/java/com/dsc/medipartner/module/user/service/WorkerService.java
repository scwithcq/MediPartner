package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.dto.UpdateWorkerProfileRequest;
import com.dsc.medipartner.module.user.domain.dto.WorkerApplyRequest;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.vo.WorkerApplyStatusVO;
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

    /**
     * §3.4 入驻申请。
     * 已有 PASS 记录返回 1003；PENDING 期间重复提交返回 5002；REJECT 允许重新提交（覆盖并重置 PENDING）。
     *
     * @return {@code {applyId, auditStatus=PENDING}}
     */
    java.util.Map<String, Object> apply(Long userId, WorkerApplyRequest request);

    /** §3.5 查询入驻申请状态；从未申请过返回 null（Controller 转成空对象 {}） */
    WorkerApplyStatusVO getApplyStatus(Long userId);

    /**
     * §7.4 上报定位（阶段 5）。
     *
     * <p>落 {@code worker_profile} 的 {@code gps_lat}/{@code gps_lng}/{@code geo_hash}
     * 与 {@code geo_updated_at}。GeoHash 取前 6 位（约 1.2km 精度）仅用于<b>粗筛</b>，
     * 真实距离由内存精算（契约 §7.4 备注 1）。
     *
     * @return {@code {geoHash, nearbyOrderCount}}
     * @throws com.dsc.medipartner.common.exception.BizException
     *         档案不存在（{@code 2001}）、坐标缺失或越界（{@code 1001}）
     */
    com.dsc.medipartner.module.user.domain.vo.GeoReportVO reportGeo(
            Long workerUserId, com.dsc.medipartner.module.user.domain.dto.ReportGeoRequest request);
}
