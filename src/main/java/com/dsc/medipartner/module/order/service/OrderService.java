package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;

public interface OrderService {

    String create(CreateOrderRequest request);

    ServiceOrder getByOrderNo(String orderNo);

    IPage<ServiceOrder> pageUserOrders(Long userId, Integer orderStatus, long pageNum, long pageSize);

    IPage<ServiceOrder> pageHall(long pageNum, long pageSize);

    IPage<ServiceOrder> pageWorkerOrders(Long workerId, Integer orderStatus, long pageNum, long pageSize);

    void accept(Long workerId, String orderNo);

    void start(Long workerId, String orderNo);

    void finish(Long workerId, String orderNo);

    void confirm(Long userId, String orderNo);

    void cancel(Long userId, String orderNo);

    long countActive(Long workerId);
}
