package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class OrderServiceImpl implements OrderService {

    private final ServiceOrderMapper orderMapper;
    private final WalletService walletService;
    private final WorkerService workerService;
    private final OrderStateMachineService stateMachineService;
    private final TraceService traceService;
    private final ReportTaskPublisher reportTaskPublisher;
    private final DemandService demandService;
    private final CreditService creditService;
    private final PriceService priceService;

    /** 服务时间需晚于当前时间 30 分钟（契约 §5.2） */
    private static final long MIN_SERVICE_TIME_OFFSET_MINUTES = 30;
    /** 服务时间需早于当前时间 30 天（契约 §5.2） */
    private static final long MAX_SERVICE_TIME_AHEAD_DAYS = 30;

    @Value("${medi.order.max-active-orders:3}")
    private int maxActiveOrders;
    @Value("${medi.order.max-active-orders-low-credit:1}")
    private int maxActiveOrdersLowCredit;
    @Value("${medi.order.min-credit-accept:60}")
    private int minCreditAccept;
    @Value("${medi.order.low-credit-threshold:80}")
    private int lowCreditThreshold;

    public OrderServiceImpl(ServiceOrderMapper orderMapper, WalletService walletService,
                            WorkerService workerService, OrderStateMachineService stateMachineService,
                            TraceService traceService, ReportTaskPublisher reportTaskPublisher,
                            DemandService demandService, CreditService creditService,
                            PriceService priceService) {
        this.orderMapper = orderMapper;
        this.walletService = walletService;
        this.workerService = workerService;
        this.stateMachineService = stateMachineService;
        this.traceService = traceService;
        this.reportTaskPublisher = reportTaskPublisher;
        this.demandService = demandService;
        this.creditService = creditService;
        this.priceService = priceService;
    }

    @Override
    @Transactional
    public String create(CreateOrderRequest request) {
        Long userId = UserContext.userId();

        // 契约 §5.2 改造点 2：服务时间合法性（需晚于当前时间 30 分钟、早于 30 天）
        checkServiceTime(request.getServiceTime());

        // 契约 §5.2 改造点 1 / 附录 H-1：金额一律由服务端计算，前端传入值不参与任何计算
        PriceResult price = priceService.calc(request.getServiceType(), request.getCity(), request.getHours());
        checkPriceConsistency(request, price);

        String orderNo = OrderNoGenerator.next("MP");
        walletService.freeze(userId, orderNo, price.getAmount());

        ServiceOrder order = new ServiceOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAmount(price.getAmount());
        order.setPriceVersion(price.getPriceVersion());
        order.setPriceRuleId(price.getPriceRuleId());
        order.setOrderStatus(OrderState.WAITING_ACCEPT.getCode());
        order.setGeoHash(request.getGeoHash());
        order.setServiceType(price.getServiceType());
        order.setServiceHours(price.getHours());
        order.setSymptomDesc(request.getSymptomDesc());
        order.setCity(request.getCity());
        order.setContactPhone(trimToNull(request.getContactPhone()));
        order.setHospitalPref(request.getHospitalPref());
        order.setServiceTime(request.getServiceTime());
        order.setRemark(trimToNull(request.getRemark()));
        order.setDemandId(request.getDemandId());
        order.setVersion(0);
        orderMapper.insert(order);
        if (request.getDemandId() != null) {
            demandService.markOrdered(request.getDemandId(), orderNo);
        }
        return orderNo;
    }

    /** 服务时间需晚于当前时间 30 分钟、早于 30 天（契约 §5.2，错误码 3007） */
    private void checkServiceTime(LocalDateTime serviceTime) {
        LocalDateTime now = LocalDateTime.now();
        if (serviceTime.isBefore(now.plusMinutes(MIN_SERVICE_TIME_OFFSET_MINUTES))
                || serviceTime.isAfter(now.plusDays(MAX_SERVICE_TIME_AHEAD_DAYS))) {
            throw new BizException(ErrorCode.SERVICE_TIME_ILLEGAL);
        }
    }

    /**
     * 价格一致性校验（契约 §5.2、§9.31 的 3010）：
     * 前端带的价格版本或展示金额与服务端重算结果不一致时，要求用户重新确认，避免按过期价格成交。
     */
    private void checkPriceConsistency(CreateOrderRequest request, PriceResult price) {
        if (StringUtils.hasText(request.getPriceVersion())
                && !request.getPriceVersion().equals(price.getPriceVersion())) {
            throw new BizException(ErrorCode.PRICE_CHANGED);
        }
        if (request.getExpectAmount() != null
                && request.getExpectAmount().compareTo(price.getAmount()) != 0) {
            throw new BizException(ErrorCode.PRICE_CHANGED);
        }
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
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
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
    public FinishResultVO finish(Long workerId, String orderNo, FinishOrderRequest request) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.FINISH);

        // 结束凭证落库（水印校验失败则整体回滚），与状态流转同事务
        traceService.saveVouchers(workerId, orderNo, request);

        LocalDateTime finishedAt = LocalDateTime.now();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.PENDING_CONFIRM.getCode())
                .set(ServiceOrder::getFinishedAt, finishedAt)
                .set(ServiceOrder::getServiceSummary, trimToNull(request.getServiceSummary()))
                .set(ServiceOrder::getActualHours, request.getActualHours())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        // 事务提交后再投递 AI 报告任务，避免消费者读到未提交数据
        publishReportAfterCommit(orderNo);

        FinishResultVO result = new FinishResultVO();
        result.setOrderNo(orderNo);
        result.setOrderStatus(OrderState.PENDING_CONFIRM.getCode());
        result.setFinishedAt(finishedAt);
        // 报告由 MQ 异步生成，结束服务时恒为 PROCESSING（与 ReportStatus 枚举一致）
        result.setReportStatus("PROCESSING");
        return result;
    }

    /** 注册事务提交后回调投递报告任务；无事务上下文时直接投递（兜底，正常路径都在事务内） */
    private void publishReportAfterCommit(String orderNo) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reportTaskPublisher.publishReportTask(orderNo);
                }
            });
        } else {
            reportTaskPublisher.publishReportTask(orderNo);
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
        // 订单完成：信用分 +2 并累计已完成单数（契约 §7.16 备注 1，事件驱动）
        creditService.change(order.getWorkerId(), CreditChangeType.ORDER_COMPLETED, orderNo, null);
        workerService.incrementServiceCount(order.getWorkerId());
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

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private ServiceOrder requireOrder(String orderNo) {
        ServiceOrder order = getByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
