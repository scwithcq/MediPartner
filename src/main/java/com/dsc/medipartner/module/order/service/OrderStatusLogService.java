package com.dsc.medipartner.module.order.service;

import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.OrderTimelineNodeVO;

import java.util.List;

/**
 * 订单流转日志（契约 §5.5、§7.9、§9.15 三端共用）。
 *
 * <p>设计要点：日志由<b>状态机 listener 统一落库</b>，业务代码不手写，保证不漏记；
 * 三端读取复用同一 Service，只在权限校验与附加字段（管理端多 operatorId/snapshot）上区分。
 */
public interface OrderStatusLogService {

    /**
     * 记录一次状态流转。由状态机 listener 调用，业务代码不要直接调。
     *
     * @param orderNo      订单号
     * @param fromState    变更前状态码，可为 null（下单初始）
     * @param toState      变更后状态码
     * @param event        触发事件（附录 B.2）
     * @param operatorType USER / WORKER / ADMIN / SYSTEM
     * @param operatorId   操作人 ID，SYSTEM 传 null
     * @param remark       补充说明（取消原因、异常说明、位置偏离等）
     */
    void record(String orderNo, Integer fromState, Integer toState, String event,
                String operatorType, Long operatorId, String remark);

    /** 记录一次留痕/评价类动作（不改状态，只补日志，附录 A 末尾说明） */
    void recordAction(String orderNo, String event, String operatorType, Long operatorId,
                      String title, String remark);

    /**
     * 订单时间轴（§5.5、§7.9）：{@code nodes} 按时间<b>升序</b>返回。
     *
     * <p>阶段 1 的存量订单没有日志，此时按 {@code createdAt/acceptedAt/startedAt/finishedAt/confirmedAt}
     * 反推补齐节点，{@code remark} 标注「历史数据补全」（§5.5 备注 3）。
     */
    List<OrderTimelineNodeVO> timeline(ServiceOrder order);

    /** 详情页最近 5 条（§5.4 timeline 字段），按时间升序 */
    List<OrderTimelineNodeVO> recentTimeline(ServiceOrder order, int limit);
}
