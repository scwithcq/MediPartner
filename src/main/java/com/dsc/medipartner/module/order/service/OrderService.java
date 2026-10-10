package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;

public interface OrderService {

    String create(CreateOrderRequest request);

    ServiceOrder getByOrderNo(String orderNo);

    /** §5.3 我的订单列表：支持 orderStatus/statusGroup/keyword/排序白名单/updatedSince */
    IPage<ServiceOrder> pageUserOrders(Long userId, OrderQuery query);

    /** §7.5 接单大厅列表：默认 serviceTime asc */
    IPage<ServiceOrder> pageHall(OrderQuery query);

    /** §7.7 陪诊师接单列表 */
    IPage<ServiceOrder> pageWorkerOrders(Long workerId, OrderQuery query);

    void accept(Long workerId, String orderNo);

    void start(Long workerId, String orderNo);

    /** 结束服务（契约 §7.14）：落 1~3 张结束凭证、写服务小结与实际时长，状态 30->40，提交后异步触发 AI 报告 */
    FinishResultVO finish(Long workerId, String orderNo, FinishOrderRequest request);

    void confirm(Long userId, String orderNo);

    void cancel(Long userId, String orderNo);

    long countActive(Long workerId);
}
