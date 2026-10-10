package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.SortWhitelist;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.ActionResolver;
import com.dsc.medipartner.module.order.service.OrderAssembler;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
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
    private final UserService userService;
    private final ReviewService reviewService;
    private final ActionResolver actionResolver;
    private final OrderAssembler orderAssembler;

    /** 服务时间需晚于当前时间 30 分钟（契约 §5.2） */
    private static final long MIN_SERVICE_TIME_OFFSET_MINUTES = 30;
    /** 服务时间需早于当前时间 30 天（契约 §5.2） */
    private static final long MAX_SERVICE_TIME_AHEAD_DAYS = 30;
    /** §1.9 增量轮询时间格式 */
    private static final DateTimeFormatter UPDATED_SINCE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
                            PriceService priceService, UserService userService,
                            ReviewService reviewService, ActionResolver actionResolver,
                            OrderAssembler orderAssembler) {
        this.orderMapper = orderMapper;
        this.walletService = walletService;
        this.workerService = workerService;
        this.stateMachineService = stateMachineService;
        this.traceService = traceService;
        this.reportTaskPublisher = reportTaskPublisher;
        this.demandService = demandService;
        this.creditService = creditService;
        this.priceService = priceService;
        this.userService = userService;
        this.reviewService = reviewService;
        this.actionResolver = actionResolver;
        this.orderAssembler = orderAssembler;
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
    public IPage<OrderCardVO> pageUserOrderCards(Long userId, OrderQuery query) {
        IPage<ServiceOrder> page = pageUserOrders(userId, query);
        List<ServiceOrder> records = page.getRecords();
        // 批量取评价状态，避免逐单查询造成 N+1
        Set<String> reviewed = reviewService.findReviewedOrderNos(orderNosOf(records), "USER");
        List<OrderCardVO> cards = new ArrayList<>(records.size());
        for (ServiceOrder order : records) {
            cards.add(orderAssembler.toCard(order,
                    loadWorkerProfile(order.getWorkerId()), loadUser(order.getWorkerId()),
                    actionResolver.resolveForUser(order.getOrderStatus(), reviewed.contains(order.getOrderNo()))));
        }
        return mapPage(page, cards);
    }

    @Override
    public IPage<OrderCardVO> pageHallCards(Long workerId, OrderQuery query) {
        IPage<ServiceOrder> page = pageHall(query);
        // §7.5：大厅是「谁都能看到、谁先抢谁得」，不下发 actions[]，可抢与否由 §7.1 的 accepting 判断
        List<OrderCardVO> cards = new ArrayList<>(page.getRecords().size());
        for (ServiceOrder order : page.getRecords()) {
            cards.add(orderAssembler.toCard(order, null, null, List.of()));
        }
        return mapPage(page, cards);
    }

    @Override
    public IPage<OrderCardVO> pageWorkerOrderCards(Long workerId, OrderQuery query) {
        IPage<ServiceOrder> page = pageWorkerOrders(workerId, query);
        List<ServiceOrder> records = page.getRecords();
        Set<String> replied = reviewService.findReviewedOrderNos(orderNosOf(records), "WORKER");
        List<OrderCardVO> cards = new ArrayList<>(records.size());
        for (ServiceOrder order : records) {
            cards.add(orderAssembler.toCard(order, null, loadUser(order.getUserId()),
                    actionResolver.resolveForWorker(order.getOrderStatus(),
                            replied.contains(order.getOrderNo()))));
        }
        return mapPage(page, cards);
    }

    @Override
    public OrderDetailVO getUserOrderDetail(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        return orderAssembler.toDetail(order,
                loadWorkerProfile(order.getWorkerId()), loadUser(order.getWorkerId()),
                actionResolver.resolveForUser(order.getOrderStatus(), hasReview(orderNo, "USER")));
    }

    @Override
    public OrderDetailVO getWorkerOrderDetail(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        return orderAssembler.toDetail(order, null, loadUser(order.getUserId()),
                actionResolver.resolveForWorker(order.getOrderStatus(), hasReview(orderNo, "WORKER")));
    }

    private List<String> orderNosOf(List<ServiceOrder> orders) {
        List<String> orderNos = new ArrayList<>(orders.size());
        for (ServiceOrder order : orders) {
            orderNos.add(order.getOrderNo());
        }
        return orderNos;
    }

    /** 陪诊师资料（跨模块只调 Service，见手册 §3.2 铁律）；workerId 为空时返回 null */
    private WorkerProfile loadWorkerProfile(Long workerId) {
        return workerId == null ? null : workerService.getByUserId(workerId);
    }

    /** 用户资料；userId 为空时返回 null */
    private SysUser loadUser(Long userId) {
        return userId == null ? null : userService.getById(userId);
    }

    /** 单订单评价状态（详情页用），查询失败退化为「未评价」而不是让详情整体报错 */
    private boolean hasReview(String orderNo, String role) {
        try {
            return !reviewService.findReviewedOrderNos(List.of(orderNo), role).isEmpty();
        } catch (Exception e) {
            log.warn("[order] 查询订单评价状态失败，按未评价处理 orderNo={}: {}", orderNo, e.getMessage());
            return false;
        }
    }

    /** 在保留分页元信息的前提下替换记录集 */
    private <T> IPage<T> mapPage(IPage<?> source, List<T> records) {
        Page<T> page = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        page.setRecords(records);
        return page;
    }

    @Override
    public IPage<ServiceOrder> pageUserOrders(Long userId, OrderQuery query) {
        LambdaQueryWrapper<ServiceOrder> wrapper = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getUserId, userId);
        applyOrderFilters(wrapper, query);
        applySort(wrapper, query, SortWhitelist.Spec.ORDER_LIST);
        return orderMapper.selectPage(newPage(query), wrapper);
    }

    @Override
    public IPage<ServiceOrder> pageHall(OrderQuery query) {
        LambdaQueryWrapper<ServiceOrder> wrapper = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode());
        applyOrderFilters(wrapper, query);
        applySort(wrapper, query, SortWhitelist.Spec.ORDER_HALL_ASC);
        return orderMapper.selectPage(newPage(query), wrapper);
    }

    @Override
    public IPage<ServiceOrder> pageWorkerOrders(Long workerId, OrderQuery query) {
        LambdaQueryWrapper<ServiceOrder> wrapper = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getWorkerId, workerId);
        applyOrderFilters(wrapper, query);
        applySort(wrapper, query, SortWhitelist.Spec.ORDER_LIST);
        return orderMapper.selectPage(newPage(query), wrapper);
    }

    /**
     * 状态与关键词筛选（§5.3、§7.7）。
     * {@code orderStatus} 与 {@code statusGroup} 互斥，同传时以 {@code orderStatus} 为准。
     */
    private void applyOrderFilters(LambdaQueryWrapper<ServiceOrder> wrapper, OrderQuery query) {
        if (query.getOrderStatus() != null) {
            wrapper.eq(ServiceOrder::getOrderStatus, query.getOrderStatus());
        } else if (StringUtils.hasText(query.getStatusGroup())) {
            List<Integer> statuses = resolveStatusGroup(query.getStatusGroup());
            if (statuses != null) {
                wrapper.in(ServiceOrder::getOrderStatus, statuses);
            }
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(ServiceOrder::getOrderNo, kw)
                    .or().like(ServiceOrder::getCity, kw)
                    .or().like(ServiceOrder::getSymptomDesc, kw));
        }
        applyUpdatedSince(wrapper, query.getUpdatedSince());
    }

    /** §5.3 状态分组：PROCESSING(10,20,30,40) / FINISHED(50) / CLOSED(60,70)；未知值返回 null（不筛选） */
    private List<Integer> resolveStatusGroup(String statusGroup) {
        return switch (statusGroup.trim().toUpperCase(Locale.ROOT)) {
            case "PROCESSING" -> List.of(OrderState.WAITING_ACCEPT.getCode(), OrderState.ACCEPTED.getCode(),
                    OrderState.IN_SERVICE.getCode(), OrderState.PENDING_CONFIRM.getCode());
            case "FINISHED" -> List.of(OrderState.COMPLETED.getCode());
            case "CLOSED" -> List.of(OrderState.CANCELLED.getCode(), OrderState.ABNORMAL.getCode());
            default -> null;
        };
    }

    /**
     * 排序落地（§1.8）：列名只可能来自 {@link SortWhitelist} 枚举常量，
     * 前端原始字符串绝不进入 SQL。非法 sortBy 已在 resolve 内回落默认值，不报错。
     */
    private void applySort(LambdaQueryWrapper<ServiceOrder> wrapper, PageQuery query,
                           SortWhitelist.Spec spec) {
        SortWhitelist.Order order = spec.resolve(query.getSortBy(), query.getOrder());
        wrapper.last("ORDER BY " + order.sqlColumn() + (order.asc() ? " ASC" : " DESC")
                + ", id DESC");
    }

    /** §1.9 增量轮询：只返回 updated_at 严格大于 updatedSince 的记录 */
    private void applyUpdatedSince(LambdaQueryWrapper<ServiceOrder> wrapper, String updatedSince) {
        LocalDateTime since = parseUpdatedSince(updatedSince);
        if (since != null) {
            wrapper.gt(ServiceOrder::getUpdatedAt, since);
        }
    }

    private LocalDateTime parseUpdatedSince(String updatedSince) {
        if (!StringUtils.hasText(updatedSince)) {
            return null;
        }
        try {
            return LocalDateTime.parse(updatedSince.trim(), UPDATED_SINCE_FORMAT);
        } catch (DateTimeParseException e) {
            // §1.3：格式非法时忽略该参数，不报错
            log.warn("[order] updatedSince 格式非法，已忽略: {}", updatedSince);
            return null;
        }
    }

    private Page<ServiceOrder> newPage(PageQuery query) {
        return new Page<>(query.getEffectivePageNum(), query.getEffectivePageSize());
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
