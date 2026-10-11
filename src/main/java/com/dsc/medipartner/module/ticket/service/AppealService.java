package com.dsc.medipartner.module.ticket.service;

import com.dsc.medipartner.module.ticket.domain.dto.CreateAppealRequest;
import com.dsc.medipartner.module.ticket.domain.vo.AppealVO;
import com.dsc.medipartner.module.ticket.domain.vo.CreateAppealVO;

import java.util.Collection;
import java.util.Set;

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

    /**
     * 找出这批订单中「已暂停自动确认」的订单号（阶段 5，契约 §11 Q5 / §7.2）。
     *
     * <p>供定时任务的 48h 自动确认排除使用：用户对「待确认」订单发起申诉时，
     * 阶段 4 已把 {@code appeal_ticket.auto_confirm_paused} 置 1。
     * 若不排除，会出现「仲裁未有结论、款项已结算给陪诊师」的资损。
     *
     * <p><b>一次性批量查询而非逐单查</b>：定时任务每轮可能扫出上百单，
     * 逐单查库会形成 N+1，放大成百次往返。
     *
     * @param orderNos 待检查的订单号集合
     * @return 其中 {@code auto_confirm_paused = 1} 的订单号；入参为空返回空集
     */
    Set<String> findAutoConfirmPausedOrderNos(Collection<String> orderNos);
}
