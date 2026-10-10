package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.domain.vo.CreditVO;

/**
 * 信用中心（契约 §7.16）：所有信用分变动的唯一入口，事件驱动、写 credit_change_log 流水。
 */
public interface CreditService {

    /**
     * 变更信用分并落流水：分数钳制在 0~100。
     *
     * @param reviewId 好评/差评联动时传评价 ID，用于 §7.20 impactScore 溯源，可为 null
     */
    void change(Long workerUserId, CreditChangeType type, String orderNo, Long reviewId);

    /** 信用中心聚合：等级/上限/30 天曲线/最近 20 条流水/规则 */
    CreditVO getCreditCenter(Long workerUserId);

    /** 某条评价对信用分的影响（带符号），无流水返回 null（VO 键缺失） */
    Integer getImpactScore(Long reviewId);
}
