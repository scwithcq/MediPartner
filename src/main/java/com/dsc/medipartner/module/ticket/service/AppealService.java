package com.dsc.medipartner.module.ticket.service;

import com.dsc.medipartner.module.ticket.domain.dto.CreateAppealRequest;
import com.dsc.medipartner.module.ticket.domain.vo.AppealVO;
import com.dsc.medipartner.module.ticket.domain.vo.CreateAppealVO;

/**
 * 申诉工单（契约 §5.14、§5.15）。
 *
 * <p>跨模块只调 {@code OrderService} 接口，不注入 order 的 Mapper（手册 §3.2 铁律）。
 */
public interface AppealService {

    /** §5.14 发起申诉：仅本人订单、状态须为 40/50/70、一单一诉 */
    CreateAppealVO create(Long userId, String orderNo, CreateAppealRequest request);

    /** §5.15 查询申诉进度；从未申诉过返回 null（Controller 转成空对象 {}） */
    AppealVO getByOrderNo(Long userId, String orderNo);

    /**
     * 系统自动建单（§7.15 标记异常时调用），{@code appeal_type = WORKER_MARK}。
     * 已存在则不重复建单。
     */
    void createForAbnormal(String orderNo, Long applicantId, String reason);
}
