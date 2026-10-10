package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;

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

    /** §7.5 内部：接单大厅实体分页 */
    IPage<ServiceOrder> pageHall(OrderQuery query);

    /** §7.7 内部：陪诊师订单实体分页 */
    IPage<ServiceOrder> pageWorkerOrders(Long workerId, OrderQuery query);

    void accept(Long workerId, String orderNo);

    void start(Long workerId, String orderNo);

    /** 结束服务（契约 §7.14）：落 1~3 张结束凭证、写服务小结与实际时长，状态 30->40，提交后异步触发 AI 报告 */
    FinishResultVO finish(Long workerId, String orderNo, FinishOrderRequest request);

    void confirm(Long userId, String orderNo);

    void cancel(Long userId, String orderNo);

    long countActive(Long workerId);
}
