package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;

public interface OrderService {

    String create(CreateOrderRequest request);

    ServiceOrder getByOrderNo(String orderNo);

    IPage<ServiceOrder> pageUserOrders(Long userId, Integer orderStatus, long pageNum, long pageSize);

    IPage<ServiceOrder> pageHall(long pageNum, long pageSize);

    IPage<ServiceOrder> pageWorkerOrders(Long workerId, Integer orderStatus, long pageNum, long pageSize);

    void accept(Long workerId, String orderNo);

    void start(Long workerId, String orderNo);

    /** 结束服务（契约 §7.14）：落 1~3 张结束凭证、写服务小结与实际时长，状态 30->40，提交后异步触发 AI 报告 */
    FinishResultVO finish(Long workerId, String orderNo, FinishOrderRequest request);

    void confirm(Long userId, String orderNo);

    void cancel(Long userId, String orderNo);

    long countActive(Long workerId);
}
