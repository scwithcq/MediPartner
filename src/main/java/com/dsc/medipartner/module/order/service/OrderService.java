package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.dto.StartServiceRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.ContactVO;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;
import com.dsc.medipartner.module.order.domain.vo.OrderTimelineVO;
import com.dsc.medipartner.module.order.domain.vo.PricePreviewVO;

public interface OrderService {

    String create(CreateOrderRequest request);

    ServiceOrder getByOrderNo(String orderNo);

    /** §5.3 我的订单列表（VO 化，含 actions[]） */
    IPage<OrderCardVO> pageUserOrderCards(Long userId, OrderQuery query);

    /** §7.5 接单大厅列表（VO 化，大厅不下发 actions[]） */
    IPage<OrderCardVO> pageHallCards(Long workerId, OrderQuery query);

    /** §7.7 陪诊师接单列表（VO 化，含 actions[]） */
    IPage<OrderCardVO> pageWorkerOrderCards(Long workerId, OrderQuery query);

    /** §5.4 订单详情（仅本人，越权 3008） */
    OrderDetailVO getUserOrderDetail(Long userId, String orderNo);

    /** §7.8 接单详情（仅本人接的单，越权 3008） */
    OrderDetailVO getWorkerOrderDetail(Long workerId, String orderNo);

    /** §5.3 内部：分页查询（保留实体分页供其它模块复用） */
    IPage<ServiceOrder> pageUserOrders(Long userId, OrderQuery query);

    /** §5.1 服务费试算（唯一计价入口 PriceService，前端不得自行计算） */
    PricePreviewVO pricePreview(String serviceType, String city, java.math.BigDecimal hours);

    /** §5.6 联系陪诊师（脱敏 + 稳定虚拟号；仅本人订单） */
    ContactVO userContact(Long userId, String orderNo);

    /** §5.5 订单流转时间轴（仅本人订单） */
    OrderTimelineVO userTimeline(Long userId, String orderNo);

    /** §7.9 陪诊师侧流转时间轴（仅本人接的单） */
    OrderTimelineVO workerTimeline(Long workerId, String orderNo);

    /** §7.10 联系求助者（脱敏 + 稳定虚拟号；仅本人接的单） */
    ContactVO workerContact(Long workerId, String orderNo);

    /** §3.3 各状态订单数（「我的」页聚合用） */
    java.util.Map<String, Long> countByStatusForUser(Long userId);
    /** §7.1 今日待服务订单数（serviceTime 落在今天且状态 20） */
    long countTodayPending(Long workerId);

    /** §7.1 本月完成单数（状态 50 且 confirmedAt 在本月） */
    long countMonthCompleted(Long workerId);

    /** §7.1 大厅当前可抢单数（首页红点） */
    long countHall();

    /** §7.5 内部：接单大厅实体分页 */
    IPage<ServiceOrder> pageHall(OrderQuery query);

    /** §7.7 内部：陪诊师订单实体分页 */
    IPage<ServiceOrder> pageWorkerOrders(Long workerId, OrderQuery query);

    void accept(Long workerId, String orderNo);

    /** §7.11 开始服务：写入 GPS 与到场信息，落流转日志（位置偏离只记不拦） */
    void start(Long workerId, String orderNo, StartServiceRequest request);

    /** 结束服务（契约 §7.14）：落 1~3 张结束凭证、写服务小结与实际时长，状态 30->40，提交后异步触发 AI 报告 */
    FinishResultVO finish(Long workerId, String orderNo, FinishOrderRequest request);

    void confirm(Long userId, String orderNo);

    void cancel(Long userId, String orderNo);

    /** §5.7 取消订单（带取消原因，阶段 1.5 新增入参） */
    void cancel(Long userId, String orderNo, String reason);

    long countActive(Long workerId);
}
