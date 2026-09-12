package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class OrderServiceImpl implements OrderService {

    private final ServiceOrderMapper orderMapper;
    private final WalletService walletService;
    private final WorkerService workerService;
    private final OrderStateMachineService stateMachineService;

    @Value("${medi.order.max-active-orders:3}")
    private int maxActiveOrders;
    @Value("${medi.order.max-active-orders-low-credit:1}")
    private int maxActiveOrdersLowCredit;
    @Value("${medi.order.min-credit-accept:60}")
    private int minCreditAccept;
    @Value("${medi.order.low-credit-threshold:80}")
    private int lowCreditThreshold;

    public OrderServiceImpl(ServiceOrderMapper orderMapper, WalletService walletService,
                            WorkerService workerService, OrderStateMachineService stateMachineService) {
        this.orderMapper = orderMapper;
        this.walletService = walletService;
        this.workerService = workerService;
        this.stateMachineService = stateMachineService;
    }

    @Override
    @Transactional
    public String create(CreateOrderRequest request) {
        Long userId = UserContext.userId();
        String orderNo = OrderNoGenerator.next("MP");
        walletService.freeze(userId, orderNo, request.getAmount());

        ServiceOrder order = new ServiceOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAmount(request.getAmount());
        order.setOrderStatus(OrderState.WAITING_ACCEPT.getCode());
        order.setGeoHash(request.getGeoHash());
        order.setServiceType(StringUtils.hasText(request.getServiceType()) ? request.getServiceType() : "ACCOMPANY");
        order.setSymptomDesc(request.getSymptomDesc());
        order.setCity(request.getCity());
        order.setHospitalPref(request.getHospitalPref());
        order.setServiceTime(request.getServiceTime());
        order.setVersion(0);
        orderMapper.insert(order);
        return orderNo;
    }

    @Override
    public ServiceOrder getByOrderNo(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderNo, orderNo));
    }

    @Override
    public IPage<ServiceOrder> pageUserOrders(Long userId, Integer orderStatus, long pageNum, long pageSize) {
        Page<ServiceOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ServiceOrder> query = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getUserId, userId)
                .eq(orderStatus != null, ServiceOrder::getOrderStatus, orderStatus)
                .orderByDesc(ServiceOrder::getCreatedAt);
        return orderMapper.selectPage(page, query);
    }

    @Override
    public IPage<ServiceOrder> pageHall(long pageNum, long pageSize) {
        Page<ServiceOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ServiceOrder> query = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                .orderByAsc(ServiceOrder::getCreatedAt);
        return orderMapper.selectPage(page, query);
    }

    @Override
    public IPage<ServiceOrder> pageWorkerOrders(Long workerId, Integer orderStatus, long pageNum, long pageSize) {
        Page<ServiceOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ServiceOrder> query = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getWorkerId, workerId)
                .eq(orderStatus != null, ServiceOrder::getOrderStatus, orderStatus)
                .orderByDesc(ServiceOrder::getCreatedAt);
        return orderMapper.selectPage(page, query);
    }

    @Override
    @Transactional
    public void accept(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);

        WorkerProfile profile = workerService.getByUserId(workerId);
        if (profile == null || profile.getCreditScore() == null || profile.getCreditScore() < minCreditAccept) {
            throw new BizException(ErrorCode.CREDIT_NOT_ENOUGH);
        }
        int limit = profile.getCreditScore() < lowCreditThreshold ? maxActiveOrdersLowCredit : maxActiveOrders;
        if (countActive(workerId) >= limit) {
            throw new BizException(ErrorCode.ORDER_ACCEPT_LIMIT);
        }

        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.ACCEPT);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getWorkerId, workerId)
                .set(ServiceOrder::getOrderStatus, OrderState.ACCEPTED.getCode())
                .set(ServiceOrder::getAcceptedAt, LocalDateTime.now())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_ALREADY_ACCEPTED);
        }
    }

    @Override
    @Transactional
    public void start(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.START);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.ACCEPTED.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                .set(ServiceOrder::getStartedAt, LocalDateTime.now())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }
    }

    @Override
    @Transactional
    public void finish(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.FINISH);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.PENDING_CONFIRM.getCode())
                .set(ServiceOrder::getFinishedAt, LocalDateTime.now())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }
    }

    @Override
    @Transactional
    public void confirm(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.USER_CONFIRM);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.PENDING_CONFIRM.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.COMPLETED.getCode())
                .set(ServiceOrder::getConfirmedAt, LocalDateTime.now())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        walletService.settle(orderNo, order.getUserId(), order.getWorkerId(), order.getAmount());
    }

    @Override
    @Transactional
    public void cancel(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.USER_CANCEL);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.CANCELLED.getCode())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        walletService.unfreeze(userId, orderNo, order.getAmount());
    }

    @Override
    public long countActive(Long workerId) {
        return orderMapper.selectCount(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getWorkerId, workerId)
                .in(ServiceOrder::getOrderStatus, OrderState.ACCEPTED.getCode(), OrderState.IN_SERVICE.getCode()));
    }

    private ServiceOrder requireOrder(String orderNo) {
        ServiceOrder order = getByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
