package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.MarkAbnormalRequest;
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

    /** §7.15 标记订单异常：30 -> 70，资金保持冻结，自动建 WORKER_MARK 申诉工单 */
    void markAbnormal(Long workerId, String orderNo, MarkAbnormalRequest request);

    long countActive(Long workerId);

    // ---------------- 阶段 5：定时任务入口（§7.2） ----------------
    //
    // 以下方法专供定时任务调用。刻意与用户侧方法分开而不是加 operatorType 参数：
    // ① 用户侧方法都要校验 userId 归属，系统调用没有「当前用户」，
    //    复用会迫使在用户侧方法里散布 if (system) 分支，反而更容易写漏权限校验；
    // ② 分开后「谁能触发这个流转」在签名上就一目了然。

    /** 超时未接单的订单号（状态 10 且创建超过阈值），按创建时间升序，最多 limit 条 */
    java.util.List<String> findTimeoutWaitingOrderNos(int limit);

    /** 结束服务后超时未确认的订单号（状态 40 且 finished_at 超过阈值，且未被申诉暂停自动确认），升序 */
    java.util.List<String> findAutoConfirmableOrderNos(int limit);

    /** 服务中超时的订单号（状态 30 且 started_at 超过阈值），升序 */
    java.util.List<String> findOverdueInServiceOrderNos(int limit);

    /**
     * 系统自动取消（超时无人接单）：{@code 10 -> 60}，事件 {@code TIMEOUT_CANCEL}。
     *
     * @return true 表示本次确实发生了流转（false = 已被他人抢先处理，属正常并发）
     */
    boolean systemTimeoutCancel(String orderNo);

    /**
     * 系统自动确认完成：{@code 40 -> 50}，事件 {@code AUTO_CONFIRM}。
     *
     * <p><b>结算路径与手动确认完全一致</b>（同一 {@code walletService.settle}），
     * 仅 {@code operatorType} 记为 {@code SYSTEM}（契约 §11 Q5 明确要求）。
     */
    boolean systemAutoConfirm(String orderNo);

    /** 系统标记异常：{@code 30 -> 70}，事件 {@code MARK_ABNORMAL}，资金保持冻结 */
    boolean systemMarkAbnormal(String orderNo, String reason);

    /** 服务中且已开始超过阈值、但尚无任何留痕的订单号（用于留痕超时告警与扣分） */
    java.util.List<ServiceOrder> findOrdersMissingTrace(int limit);

    // ---------------- 阶段 5：就近排序（§7.4 / §7.5 / §7.6） ----------------

    /**
     * 附近可接单数（接单大厅候选量），用于 §7.4 上报定位后刷新红点。
     *
     * <p>口径与大厅列表一致：状态 10、未被自己屏蔽、且在自己可服务的城市内。
     * 距离只做<b>粗筛</b>（GeoHash 前缀 + 内存精算），不做硬性距离上限——
     * 上限仅在抢单时校验（§7.6 的 {@code 3006}）。
     *
     * @param workerId 陪诊师用户 ID（用于取其服务城市与坐标）
     * @param lat      本次上报的纬度
     * @param lng      本次上报的经度
     */
    long countNearbyHallOrders(Long workerId, java.math.BigDecimal lat, java.math.BigDecimal lng);
}
