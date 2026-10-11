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
import com.dsc.medipartner.common.util.DesensitizeUtil;
import com.dsc.medipartner.common.util.GeoUtil;
import com.dsc.medipartner.common.util.OrderNoGenerator;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.dto.AcceptOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.MarkAbnormalRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.dto.StartServiceRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderEvent;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.domain.vo.ContactVO;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;
import com.dsc.medipartner.module.order.domain.vo.OrderTimelineVO;
import com.dsc.medipartner.module.order.domain.vo.PricePreviewVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.ActionResolver;
import com.dsc.medipartner.module.order.service.OrderAssembler;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.OrderStatusLogService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.order.service.VirtualPhoneService;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.ticket.service.AppealService;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final VirtualPhoneService virtualPhoneService;
    private final OrderStatusLogService statusLogService;
    private final AppealService appealService;
    private final JobProperties jobProperties;

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

    /**
     * 抢单距离上限（km，阶段 5 §7.6）。
     *
     * <p>配置键以 §7.6 的 {@code medi.order.max-distance-km} 为准（§11 Q4 曾写
     * {@code max-accept-distance-km}，两处冲突时以接口章节为准）；
     * 默认 15km 取自 §7.6 的建议值（Q4 的 10km 更严，课设联调易抢不到单）。
     * ≤ 0 表示关闭该校验。
     */
    @Value("${medi.order.max-distance-km:15}")
    private double maxDistanceKm;

    /**
     * 抢单时服务时间的最小提前量（分钟，阶段 5 §7.6 的 {@code 3007}）。
     *
     * <p>比下单校验（§5.2 的 30 分钟）保持同口径：下单时合规的单在抢单时也应当合规，
     * 两者用不同阈值会造成「能下单但没人能接」的怪状态。
     */
    @Value("${medi.order.service-time-buffer-minutes:30}")
    private int serviceTimeBufferMinutes;

    /**
     * {@code reviewService} 用 {@link Lazy} 注入：ReviewServiceImpl 需要 OrderService 校验订单状态，
     * 而订单列表要查评价状态来算 actions[]，两者构成环。用 @Lazy 让 Spring 注入代理、
     * 首次真正调用时才解析，避免启动期循环依赖（Servlet 容器默认禁止环）。
     */
    public OrderServiceImpl(ServiceOrderMapper orderMapper, WalletService walletService,
                            WorkerService workerService, OrderStateMachineService stateMachineService,
                            TraceService traceService, ReportTaskPublisher reportTaskPublisher,
                            DemandService demandService, CreditService creditService,
                            PriceService priceService, UserService userService,
                            @Lazy ReviewService reviewService, ActionResolver actionResolver,
                            OrderAssembler orderAssembler, VirtualPhoneService virtualPhoneService,
                            OrderStatusLogService statusLogService, @Lazy AppealService appealService,
                            JobProperties jobProperties) {
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
        this.virtualPhoneService = virtualPhoneService;
        this.statusLogService = statusLogService;
        this.appealService = appealService;
        this.jobProperties = jobProperties;
    }

    @Override
    @Transactional
    public String create(CreateOrderRequest request) {
        Long userId = UserContext.userId();

        // 契约 §5.2 改造点 2：服务时间合法性（需晚于当前时间 30 分钟、早于 30 天）
        checkServiceTime(request.getServiceTime());

        // 契约 §5.2 改造点 1 / 附录 H-1：金额一律由服务端计算，前端传入值不参与任何计算
        // 计价城市码优先取前端显式传入的 cityCode（H-12 的口径），未传则回落服务端内部默认
        String cityCode = trimToNull(request.getCityCode());
        PriceResult price = priceService.calc(request.getServiceType(), cityCode, request.getHours());
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
        order.setGeoHash(resolveOrderGeoHash(request));
        order.setServiceType(price.getServiceType());
        order.setServiceHours(price.getHours());
        order.setSymptomDesc(request.getSymptomDesc());
        order.setCity(request.getCity());
        // H-12：计价与运力匹配一律按 city_code；命中的城市码由 PriceService 回吐（未命中为 DEFAULT）
        order.setCityCode(price.getCity());
        order.setContactPhone(trimToNull(request.getContactPhone()));
        order.setHospitalPref(request.getHospitalPref());
        order.setServiceTime(request.getServiceTime());
        order.setRemark(trimToNull(request.getRemark()));
        order.setDemandId(request.getDemandId());
        order.setVersion(0);
        orderMapper.insert(order);
        // §5.5：下单首条流转日志（CREATE，无前状态）
        statusLogService.record(orderNo, null, OrderState.WAITING_ACCEPT.getCode(),
                OrderEvent.CREATE.name(),
                OrderStatusLogServiceImpl.OPERATOR_USER, userId, null);
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
        BigDecimal workerLat = GeoUtil.parseCoordinate(query.getGpsLat());
        BigDecimal workerLng = GeoUtil.parseCoordinate(query.getGpsLng());
        boolean hasWorkerGeo = GeoUtil.isValid(workerLat, workerLng);
        boolean sortByDistance = hasWorkerGeo && isDistanceSort(query);

        List<ServiceOrder> records;
        long total;
        long current = query.getEffectivePageNum();
        long size = query.getEffectivePageSize();

        if (sortByDistance) {
            // 距离排序无法交给 SQL（库里只有 geo_hash，ORDER BY 会退化成字典序）。
            // 做法：跳过 DB 分页，取出符合筛选条件的全部候选（已限上限），
            // 内存精算距离后排序，再手工切片分页。
            //
            // ⚠ 取舍说明：这是「全量拉取 + 内存排序」，在订单量很大时不如 DB 分页高效。
            // 本项目为课设规模（大厅候选通常几十到几百条），且下面加了 DISTANCE_SORT_MAX_CANDIDATES
            // 上限兜底，可接受。若将来量级上升，应改为「GeoHash 前缀分桶 + 桶内查询」。
            List<ServiceOrder> candidates = loadHallCandidatesForDistanceSort(query);
            // 尊重 §1.8 的 order 方向：契约把 sortBy/order 一并纳入排序白名单，
            // 只认 sortBy 而忽略 order 会让 order=desc 静默失效（返回升序却看似正常）。
            boolean ascending = SortWhitelist.Spec.ORDER_HALL_ASC
                    .resolve(query.getSortBy(), query.getOrder()).asc();
            Comparator<ServiceOrder> byDistance = Comparator.comparing(
                    (ServiceOrder o) -> distanceToOrder(workerLat, workerLng, o),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            List<ServiceOrder> sorted = candidates.stream()
                    .sorted((ascending ? byDistance : byDistance.reversed())
                            // 距离相同时按 serviceTime 升序，保证排序稳定可复现。
                            // ⚠ 这一层刻意<b>不</b>随 order 反转：它是「同距离内的确定性兜底」，
                            // 而非用户可见的排序语义；跟着反转会让翻页时顺序抖动。
                            .thenComparing(ServiceOrder::getServiceTime,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(ServiceOrder::getId))
                    .toList();
            total = sorted.size();
            int from = (int) Math.min((current - 1) * size, sorted.size());
            int to = (int) Math.min(from + size, sorted.size());
            records = sorted.subList(from, to);
        } else {
            IPage<ServiceOrder> page = pageHall(query);
            records = page.getRecords();
            total = page.getTotal();
        }

        List<OrderCardVO> cards = new ArrayList<>(records.size());
        long timeoutMinutes = jobProperties.getTimeoutCancelMinutes();
        for (ServiceOrder order : records) {
            // §7.5：大厅是「谁都能看到、谁先抢谁得」，不下发 actions[]
            OrderCardVO card = orderAssembler.toCard(order, null, null, List.of());
            if (hasWorkerGeo) {
                // 未传坐标时保持 null → 键缺失（契约 §7.5）
                card.setDistanceKm(distanceToOrder(workerLat, workerLng, order));
            }
            card.setCountdownSeconds(countdownSeconds(order, timeoutMinutes));
            cards.add(card);
        }

        Page<OrderCardVO> result = new Page<>(current, size, total);
        result.setRecords(cards);
        return result;
    }

    /**
     * 距离排序模式下取出全部候选（不分页）。
     *
     * <p>上限 {@link #DISTANCE_SORT_MAX_CANDIDATES} 是保护措施：万一某个城市堆积了大量待接单，
     * 无上限的全量加载会把内存和响应时间拖垮。超出上限时只对最近发布的部分排序，
     * 并记录 WARN 提示需要改用分桶方案。
     */
    private static final int DISTANCE_SORT_MAX_CANDIDATES = 2000;

    private List<ServiceOrder> loadHallCandidatesForDistanceSort(OrderQuery query) {
        LambdaQueryWrapper<ServiceOrder> wrapper = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                // 距离排序必须拿到坐标，故只取已落 geo_hash 的单。
                // 建单时 geo_hash 由下单带的位置生成，缺失属异常数据，跳过而非报错。
                .isNotNull(ServiceOrder::getGeoHash);
        applyOrderFilters(wrapper, query);
        applyMinAmount(wrapper, query);
        // 即使走内存排序，也用 serviceTime 作为 DB 侧预排序：
        // 万一触发上限截断，留下的至少是「最近要服务」的单，而不是随机的一批
        wrapper.orderByAsc(ServiceOrder::getServiceTime).last("LIMIT " + DISTANCE_SORT_MAX_CANDIDATES);

        List<ServiceOrder> list = orderMapper.selectList(wrapper);
        if (list.size() >= DISTANCE_SORT_MAX_CANDIDATES) {
            log.warn("[order] 距离排序候选达到上限 {}，可能存在数据积压，建议改用 GeoHash 分桶查询", list.size());
        }
        return list;
    }

    /** 查询是否要求按距离排序（白名单已保证 sortBy 合法） */
    private boolean isDistanceSort(OrderQuery query) {
        SortWhitelist.Order order = SortWhitelist.Spec.ORDER_HALL_ASC
                .resolve(query.getSortBy(), query.getOrder());
        return "distance".equals(order.property());
    }

    /**
     * 订单到陪诊师的距离（km）。
     *
     * <p><b>为什么能用 geo_hash 算出距离</b>：本项目 {@code service_order} 只存了 GeoHash
     * （无原始经纬度列），而 GeoHash 字符串本身<b>编码了格子的中心坐标</b>——
     * 解码即得该格中心点，用它代替订单真实坐标，误差上界就是格子尺寸
     * （6 位约 ±0.6km）。对「就近排序」这个用途完全够用。
     *
     * <p>若订单没有 geo_hash，返回 {@code null} 表示「无法计算」，
     * 排序时排到最后，而不是当成 0（0 会被误读为最近）。
     */
    private BigDecimal distanceToOrder(BigDecimal workerLat, BigDecimal workerLng, ServiceOrder order) {
        if (!StringUtils.hasText(order.getGeoHash())) {
            return null;
        }
        double[] center = GeoUtil.decodeGeoHashCenter(order.getGeoHash());
        if (center == null) {
            return null;
        }
        return GeoUtil.distanceKm(workerLat, workerLng,
                BigDecimal.valueOf(center[0]), BigDecimal.valueOf(center[1]));
    }

    /**
     * 距超时自动取消的剩余秒数（§7.5）。
     *
     * <p>以订单创建时间 + {@code medi.job.timeout-cancel-minutes} 为超时时刻。
     * 已过期返回 0（前端据此显示「即将取消」），不返回负数。
     */
    private Long countdownSeconds(ServiceOrder order, long timeoutMinutes) {
        if (order.getCreatedAt() == null) {
            return null;
        }
        LocalDateTime deadline = order.getCreatedAt().plusMinutes(timeoutMinutes);
        long remaining = java.time.Duration.between(LocalDateTime.now(), deadline).getSeconds();
        return Math.max(remaining, 0L);
    }

    /** §7.5 最低客单价过滤（阶段 5） */
    private void applyMinAmount(LambdaQueryWrapper<ServiceOrder> wrapper, OrderQuery query) {
        if (StringUtils.hasText(query.getMinAmount())) {
            try {
                wrapper.ge(ServiceOrder::getAmount, new BigDecimal(query.getMinAmount().trim()));
            } catch (NumberFormatException e) {
                // 金额格式非法时忽略该筛选而非报错（§1.8 的一贯原则：筛选参数出错不阻断列表）
                log.warn("[order] minAmount 格式非法，已忽略该筛选: {}", query.getMinAmount());
            }
        }
    }

    @Override
    public long countNearbyHallOrders(Long workerId, BigDecimal lat, BigDecimal lng) {
        // 口径与大厅列表一致：状态 10 + 已落 geo_hash
        LambdaQueryWrapper<ServiceOrder> wrapper = new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                .isNotNull(ServiceOrder::getGeoHash);
        List<ServiceOrder> candidates = orderMapper.selectList(wrapper);

        if (!GeoUtil.isValid(lat, lng)) {
            // 没有坐标时退化为「全部待接单数」——比返回 0 更有参考价值
            return candidates.size();
        }
        long count = 0;
        for (ServiceOrder order : candidates) {
            BigDecimal d = distanceToOrder(lat, lng, order);
            // 与大厅展示口径保持一致：能算出距离的都算「附近」
            if (d != null) {
                count++;
            }
        }
        return count;
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
        OrderDetailVO vo = orderAssembler.toDetail(order,
                loadWorkerProfile(order.getWorkerId()), loadUser(order.getWorkerId()),
                actionResolver.resolveForUser(order.getOrderStatus(), hasReview(orderNo, "USER")));
        // §5.4：详情页只带最近 5 条流转，完整列表走 §5.5
        orderAssembler.fillTimeline(vo, statusLogService.recentTimeline(order, DETAIL_TIMELINE_LIMIT));
        return vo;
    }

    @Override
    public OrderDetailVO getWorkerOrderDetail(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        OrderDetailVO vo = orderAssembler.toDetail(order, null, loadUser(order.getUserId()),
                actionResolver.resolveForWorker(order.getOrderStatus(), hasReview(orderNo, "WORKER")));
        orderAssembler.fillTimeline(vo, statusLogService.recentTimeline(order, DETAIL_TIMELINE_LIMIT));
        return vo;
    }

    /** §5.4 timeline 字段条数上限 */
    private static final int DETAIL_TIMELINE_LIMIT = 5;

    private List<String> orderNosOf(List<ServiceOrder> orders) {
        List<String> orderNos = new ArrayList<>(orders.size());
        for (ServiceOrder order : orders) {
            orderNos.add(order.getOrderNo());
        }
        return orderNos;
    }

    // ---------------- §5.1 服务费试算 ----------------

    /** 报价有效期（§5.1 默认 30 分钟） */
    private static final long PRICE_QUOTE_VALID_MINUTES = 30;

    @Override
    public PricePreviewVO pricePreview(String serviceType, String city, BigDecimal hours) {
        PriceResult price = priceService.calc(serviceType, city, hours);
        PricePreviewVO vo = new PricePreviewVO();
        vo.setServiceType(price.getServiceType());
        vo.setServiceTypeName(price.getServiceTypeName());
        vo.setCity(price.getCity());
        vo.setHours(price.getHours());
        vo.setBasePrice(price.getBasePrice());
        vo.setExtraHours(price.getExtraHours());
        vo.setExtraHourPrice(price.getExtraHourPrice());
        vo.setAmount(price.getAmount());
        vo.setPriceRuleId(price.getPriceRuleId());
        vo.setPriceVersion(price.getPriceVersion());
        vo.setValidUntil(LocalDateTime.now().plusMinutes(PRICE_QUOTE_VALID_MINUTES));
        vo.setRemark(price.getRemark());
        return vo;
    }

    // ---------------- §5.6 / §7.10 联系对方 ----------------

    @Override
    public ContactVO userContact(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        if (order.getWorkerId() == null) {
            // 尚未接单，无对方可联系（§5.6 错误码 3002）
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL, "订单尚未被接单，暂无陪诊师联系方式");
        }
        SysUser peer = loadUser(order.getWorkerId());
        return buildContact(order, "WORKER", peer, peer == null ? null : peer.getNickname(),
                peer == null ? null : peer.getPhone());
    }

    @Override
    public ContactVO workerContact(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        SysUser peer = loadUser(order.getUserId());
        return buildContact(order, "USER", peer, peer == null ? null : peer.getNickname(),
                peer == null ? null : peer.getPhone());
    }

    // ---------------- §5.5 / §7.9 流转时间轴 ----------------

    @Override
    public OrderTimelineVO userTimeline(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        return buildTimeline(order);
    }

    @Override
    public OrderTimelineVO workerTimeline(Long workerId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        return buildTimeline(order);
    }

    /** 两端共用同一份组装逻辑（§5.5 备注 2 / §7.9 备注），只有权限校验方向不同 */
    private OrderTimelineVO buildTimeline(ServiceOrder order) {
        OrderTimelineVO vo = new OrderTimelineVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setCurrentStatus(order.getOrderStatus());
        vo.setNodes(statusLogService.timeline(order));
        return vo;
    }

    /**
     * 组装联系信息（§5.6）。
     * 虚拟号由 {@code orderNo} 稳定生成，双方查到的是同一个号；真实手机号一律脱敏。
     */
    private ContactVO buildContact(ServiceOrder order, String peerRole, SysUser peer,
                                   String peerNickname, String peerPhone) {
        ContactVO vo = new ContactVO();
        vo.setPeerRole(peerRole);
        vo.setPeerName(DesensitizeUtil.nickname(peerNickname));
        vo.setMaskedPhone(DesensitizeUtil.phone(peerPhone));
        vo.setVirtualPhone(virtualPhoneService.generate(order.getOrderNo()));
        // 虚拟号在订单完成后 24 小时失效（§5.6）
        vo.setVirtualExpireAt(resolveVirtualExpireAt(order));
        // 仅 20~50 可联系；10 待接单与 60/70 终态不可联系（§5.6 错误码 3002）
        vo.setCanCall(isContactable(order.getOrderStatus()));
        vo.setTip("为保护隐私，请通过虚拟号联系");
        return vo;
    }

    private LocalDateTime resolveVirtualExpireAt(ServiceOrder order) {
        LocalDateTime base = order.getConfirmedAt() != null ? order.getConfirmedAt()
                : (order.getFinishedAt() != null ? order.getFinishedAt() : null);
        return base == null ? null : base.plusHours(24);
    }

    private boolean isContactable(Integer orderStatus) {
        OrderState state = OrderState.ofNullable(orderStatus);
        if (state == null) {
            return false;
        }
        return switch (state) {
            case ACCEPTED, IN_SERVICE, PENDING_CONFIRM, COMPLETED -> true;
            default -> false;
        };
    }

    // ---------------- §3.3 / §7.1 聚合统计 ----------------

    @Override
    public Map<String, Long> countByStatusForUser(Long userId) {
        List<ServiceOrder> rows = orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                .select(ServiceOrder::getOrderStatus)
                .eq(ServiceOrder::getUserId, userId));
        Map<String, Long> counts = new LinkedHashMap<>();
        // 契约 §3.3 要求各状态键恒存在，无数据时为 0，前端不必判空
        counts.put("waitingAccept", 0L);
        counts.put("accepted", 0L);
        counts.put("inService", 0L);
        counts.put("pendingConfirm", 0L);
        counts.put("completed", 0L);
        counts.put("cancelled", 0L);
        counts.put("abnormal", 0L);
        for (ServiceOrder row : rows) {
            String key = statusKey(row.getOrderStatus());
            if (key != null) {
                counts.merge(key, 1L, Long::sum);
            }
        }
        return counts;
    }

    private String statusKey(Integer orderStatus) {
        OrderState state = OrderState.ofNullable(orderStatus);
        if (state == null) {
            return null;
        }
        return switch (state) {
            case WAITING_ACCEPT -> "waitingAccept";
            case ACCEPTED -> "accepted";
            case IN_SERVICE -> "inService";
            case PENDING_CONFIRM -> "pendingConfirm";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            case ABNORMAL -> "abnormal";
        };
    }

    @Override
    public long countTodayPending(Long workerId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        return orderMapper.selectCount(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getWorkerId, workerId)
                .eq(ServiceOrder::getOrderStatus, OrderState.ACCEPTED.getCode())
                .ge(ServiceOrder::getServiceTime, startOfDay)
                .lt(ServiceOrder::getServiceTime, endOfDay));
    }

    @Override
    public long countMonthCompleted(Long workerId) {
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return orderMapper.selectCount(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getWorkerId, workerId)
                .eq(ServiceOrder::getOrderStatus, OrderState.COMPLETED.getCode())
                .ge(ServiceOrder::getConfirmedAt, startOfMonth));
    }

    @Override
    public long countHall() {
        return orderMapper.selectCount(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode()));
    }

    // ---------------- §7.15 标记订单异常 ----------------

    @Override
    @Transactional
    public void markAbnormal(Long workerId, String orderNo, MarkAbnormalRequest request) {
        ServiceOrder order = requireOrder(orderNo);
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.MARK_ABNORMAL);

        String abnormalType = request.getAbnormalType().trim().toUpperCase(Locale.ROOT);
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.ABNORMAL.getCode())
                .set(ServiceOrder::getAbnormalType, abnormalType)
                .set(ServiceOrder::getAbnormalRemark, request.getReason().trim())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        // ⚠ 资金保持冻结不动：既不 settle 也不 unfreeze。
        // 异常单只能由管理端 §9.16 取消 / §9.17 退款收尾，双方都没有自助出口（D9 刻意设计）。
        statusLogService.record(orderNo, OrderState.IN_SERVICE.getCode(),
                OrderState.ABNORMAL.getCode(), OrderEvent.MARK_ABNORMAL.name(),
                OrderStatusLogServiceImpl.OPERATOR_WORKER, workerId,
                abnormalType + "：" + request.getReason().trim());

        // 自动建仲裁工单进管理端队列（§7.15）；失败不影响主流程，异常单本身已是可检索状态
        try {
            appealService.createForAbnormal(orderNo, workerId, request.getReason());
        } catch (Exception e) {
            log.warn("[order] 异常单自动建申诉工单失败 orderNo={}: {}", orderNo, e.getMessage());
        }
    }

    /** 陪诊师资料（跨模块只调 Service，见手册 §3.2 铁律）；workerId 为空时返回 null */
    private WorkerProfile loadWorkerProfile(Long workerId) {
        return workerId == null ? null : workerService.getByUserId(workerId);
    }

    /**
     * 决定订单落库的 GeoHash（阶段 5，契约 §5.2「与 geoHash 二选一，后端可代算」）。
     *
     * <p>优先级：前端已算好的 {@code geoHash} > 由 {@code gpsLat}/{@code gpsLng} 代算 > 不落（null）。
     *
     * <p>没有定位的订单 {@code geo_hash} 为 null，会被「按距离排序」的候选查询排除——
     * 这是刻意的：无法计算距离的订单混进距离排序里，只会得到一个位置随机的列表。
     * 默认排序（serviceTime asc）不受影响，它们仍能被正常抢单。
     */
    private String resolveOrderGeoHash(CreateOrderRequest request) {
        if (StringUtils.hasText(request.getGeoHash())) {
            return request.getGeoHash().trim();
        }
        BigDecimal lat = GeoUtil.parseCoordinate(request.getGpsLat());
        BigDecimal lng = GeoUtil.parseCoordinate(request.getGpsLng());
        if (!GeoUtil.isValid(lat, lng)) {
            return null;
        }
        // 与 §7.4 陪诊师定位同精度（6 位约 1.2km），保证两侧距离口径一致
        return GeoUtil.geoHash(lat, lng, GEO_HASH_PRECISION);
    }

    /** GeoHash 精度（契约 §7.4 备注 1：6 位约 1.2km）；与 WorkerServiceImpl 保持一致 */
    private static final int GEO_HASH_PRECISION = 6;

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
        applyMinAmount(wrapper, query);
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
     *
     * <p>{@code distance}（阶段 5）在 SQL 层无法表达（库里只有 geo_hash，排序会退化为字典序），
     * 因此这里<b>显式拦截</b>：命中内存排序属性时回落到本接口默认列，
     * 真实距离排序由服务层在内存精算后完成（§7.4 备注 1 / §7.5）。
     */
    private void applySort(LambdaQueryWrapper<ServiceOrder> wrapper, PageQuery query,
                           SortWhitelist.Spec spec) {
        SortWhitelist.Order order = spec.resolve(query.getSortBy(), query.getOrder());
        if (order.requiresInMemorySort()) {
            // 绝不把 null 列名拼进 SQL；回落该接口默认排序，内存排序在调用方随后覆盖
            order = spec.resolve(null, query.getOrder());
        }
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
    public OrderDetailVO accept(Long workerId, String orderNo, AcceptOrderRequest request) {
        ServiceOrder order = requireOrder(orderNo);

        WorkerProfile profile = workerService.getByUserId(workerId);
        if (profile == null || profile.getCreditScore() == null || profile.getCreditScore() < minCreditAccept) {
            throw new BizException(ErrorCode.CREDIT_NOT_ENOUGH);
        }
        int limit = profile.getCreditScore() < lowCreditThreshold ? maxActiveOrdersLowCredit : maxActiveOrders;
        if (countActive(workerId) >= limit) {
            throw new BizException(ErrorCode.ORDER_ACCEPT_LIMIT);
        }

        // §7.6 阶段 5：服务时间可达性校验（3007）。
        // 必须在乐观锁更新之前做——抢到单再发现服务时间已过，用户还得再取消一次。
        assertServiceTimeReachable(order);

        // §7.6 阶段 5：距离校验（3006）。
        // ⚠ 校验放在 Service 层而非 DTO 注解：注解失败会被 GlobalExceptionHandler
        // 统一压成 1001，契约要求的 3006 将永远拿不到（历史教训：RechargeRequest 的 4006）。
        assertWithinAcceptDistance(workerId, order, request);

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
        statusLogService.record(orderNo, OrderState.WAITING_ACCEPT.getCode(),
                OrderState.ACCEPTED.getCode(), OrderEvent.ACCEPT.name(),
                OrderStatusLogServiceImpl.OPERATOR_WORKER, workerId,
                buildAcceptRemark(request));

        // §7.6 阶段 1.5：返回完整详情，让前端省掉一次详情请求直接进入服务页
        return getWorkerOrderDetail(workerId, orderNo);
    }

    /**
     * 服务时间可达性校验（§7.6 错误码 {@code 3007}）。
     *
     * <p>判定：{@code serviceTime} 必须晚于「当前时间 + 缓冲」。缓冲用于排除
     * 「现在下单、5 分钟后就要服务」这类实际赶不到的单——若只校验「未过期」，
     * 陪诊师会在路上就超时，然后被定时任务标记异常，双方都受损。
     */
    private void assertServiceTimeReachable(ServiceOrder order) {
        LocalDateTime serviceTime = order.getServiceTime();
        if (serviceTime == null) {
            // 无服务时间的历史数据不做拦截：宁可放过也不误拦（契约未定义该情形）
            return;
        }
        LocalDateTime earliest = LocalDateTime.now().plusMinutes(
                Math.max(serviceTimeBufferMinutes, 0));
        if (serviceTime.isBefore(earliest)) {
            throw new BizException(ErrorCode.SERVICE_TIME_ILLEGAL);
        }
    }

    /**
     * 抢单距离校验（§7.6 错误码 {@code 3006}）。
     *
     * <p><b>不硬拦的两种情况</b>：
     * <ol>
     *   <li>任一坐标缺失（陪诊师没传实时定位 / 订单没有 geo_hash）——为了不误伤
     *       定位失败或拒绝授权的用户，此时放行（契约 §7.4 备注 3：定位失败不阻塞主流程）</li>
     *   <li>距离上限配置 ≤ 0 —— 视为关闭该校验（便于联调期临时放开）</li>
     * </ol>
     *
     * <p>距离用 {@code order.geo_hash} 解码出的格中心计算（订单表无原始经纬度列），
     * 误差上界为格子尺寸（6 位约 ±0.6km），对「是否超过 15km」的判定足够。
     */
    private void assertWithinAcceptDistance(Long workerId, ServiceOrder order, AcceptOrderRequest request) {
        if (maxDistanceKm <= 0) {
            return;
        }
        if (!StringUtils.hasText(order.getGeoHash())) {
            return;
        }
        // 优先用抢单请求里的实时定位；没传则回落到该陪诊师最近一次上报的位置
        BigDecimal workerLat = GeoUtil.parseCoordinate(request == null ? null : request.getGpsLat());
        BigDecimal workerLng = GeoUtil.parseCoordinate(request == null ? null : request.getGpsLng());
        if (!GeoUtil.isValid(workerLat, workerLng)) {
            WorkerProfile profile = workerService.getByUserId(workerId);
            if (profile == null) {
                return;
            }
            workerLat = profile.getGpsLat();
            workerLng = profile.getGpsLng();
        }
        if (!GeoUtil.isValid(workerLat, workerLng)) {
            // 拿不到陪诊师位置 → 放行，不因定位缺失而阻止接单
            return;
        }

        double[] center = GeoUtil.decodeGeoHashCenter(order.getGeoHash());
        if (center == null) {
            return;
        }
        BigDecimal distance = GeoUtil.distanceKm(workerLat, workerLng,
                BigDecimal.valueOf(center[0]), BigDecimal.valueOf(center[1]));
        if (distance != null && distance.doubleValue() > maxDistanceKm) {
            // 文案带上具体超出量，前端可直接展示（契约 §7.6：提示具体超出多少公里）
            throw new BizException(ErrorCode.ORDER_OUT_OF_DISTANCE,
                    String.format("该订单距你 %s 公里，超出 %.0f 公里的接单范围",
                            distance.toPlainString(), maxDistanceKm));
        }
    }

    /** 抢单留痕备注：记录客户端点击时间与定位，供抢单争议排查（§7.6 请求体说明） */
    private String buildAcceptRemark(AcceptOrderRequest request) {
        if (request == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(request.getGpsLat()) && StringUtils.hasText(request.getGpsLng())) {
            sb.append("接单位置 ").append(request.getGpsLat().trim())
                    .append(",").append(request.getGpsLng().trim());
        }
        if (StringUtils.hasText(request.getClientTs())) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("客户端时间 ").append(request.getClientTs().trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    @Transactional
    public void start(Long workerId, String orderNo, StartServiceRequest request) {
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

        // §7.11：位置与订单 geo_hash 偏离过远不硬拦，只记入 remark 交管理端事后审查
        String remark = buildStartRemark(order, request);
        statusLogService.record(orderNo, OrderState.ACCEPTED.getCode(),
                OrderState.IN_SERVICE.getCode(), OrderEvent.START.name(),
                OrderStatusLogServiceImpl.OPERATOR_WORKER, workerId, remark);
    }

    /** 组装开始服务的日志备注：带上 GPS 与用户备注；偏离只在有 geo_hash 时做粗判 */
    private String buildStartRemark(ServiceOrder order, StartServiceRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request != null) {
            if (StringUtils.hasText(request.getRemark())) {
                sb.append(request.getRemark().trim());
            }
            if (StringUtils.hasText(request.getGpsLat()) && StringUtils.hasText(request.getGpsLng())) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("定位 ").append(request.getGpsLat().trim())
                        .append(",").append(request.getGpsLng().trim());
            }
            if (StringUtils.hasText(request.getAddress())) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(request.getAddress().trim());
            }
        }
        // remark 列长 255，超出截断避免插入失败
        return sb.length() > 255 ? sb.substring(0, 255) : (sb.length() == 0 ? null : sb.toString());
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

        statusLogService.record(orderNo, OrderState.IN_SERVICE.getCode(),
                OrderState.PENDING_CONFIRM.getCode(), OrderEvent.FINISH.name(),
                OrderStatusLogServiceImpl.OPERATOR_WORKER, workerId,
                trimToNull(request.getServiceSummary()));

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
        statusLogService.record(orderNo, OrderState.PENDING_CONFIRM.getCode(),
                OrderState.COMPLETED.getCode(), OrderEvent.USER_CONFIRM.name(),
                OrderStatusLogServiceImpl.OPERATOR_USER, userId, null);
        // 订单完成：信用分 +2 并累计已完成单数（契约 §7.16 备注 1，事件驱动）
        creditService.change(order.getWorkerId(), CreditChangeType.ORDER_COMPLETED, orderNo, null);
        workerService.incrementServiceCount(order.getWorkerId());
    }

    @Override
    @Transactional
    public void cancel(Long userId, String orderNo) {
        cancel(userId, orderNo, null);
    }

    @Override
    @Transactional
    public void cancel(Long userId, String orderNo, String reason) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        stateMachineService.transition(OrderState.of(order.getOrderStatus()), OrderEvent.USER_CANCEL);

        String cleanReason = trimToNull(reason);
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.CANCELLED.getCode())
                // §5.7 阶段 1.5 新增取消原因，供详情页与时间轴展示
                .set(ServiceOrder::getCancelReason, cleanReason)
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        walletService.unfreeze(userId, orderNo, order.getAmount());
        statusLogService.record(orderNo, OrderState.WAITING_ACCEPT.getCode(),
                OrderState.CANCELLED.getCode(), OrderEvent.USER_CANCEL.name(),
                OrderStatusLogServiceImpl.OPERATOR_USER, userId, cleanReason);
    }

    @Override
    public long countActive(Long workerId) {
        return orderMapper.selectCount(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getWorkerId, workerId)
                .in(ServiceOrder::getOrderStatus, OrderState.ACCEPTED.getCode(), OrderState.IN_SERVICE.getCode()));
    }

    // ================= 阶段 5：定时任务入口（§7.2） =================
    //
    // 设计原则（改动前务必理解）：
    //   1. 「查候选」与「执行流转」分离。查询只返回 orderNo，执行时重新加载并校验状态，
    //      避免「查出来到处理之间状态已变」导致误操作。
    //   2. 所有流转都带 version 乐观锁 + 期望状态条件，影响行数为 0 即视为
    //      「已被他人处理」并静默跳过——定时任务与人并发是常态，不是异常。
    //   3. 一律复用状态机已有事件，不自造流转逻辑。

    @Override
    public List<String> findTimeoutWaitingOrderNos(int limit) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(jobProperties.getTimeoutCancelMinutes());
        return orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                        .select(ServiceOrder::getOrderNo)
                        .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                        .lt(ServiceOrder::getCreatedAt, deadline)
                        .orderByAsc(ServiceOrder::getCreatedAt)
                        .last("LIMIT " + safeLimit(limit)))
                .stream().map(ServiceOrder::getOrderNo).toList();
    }

    /**
     * 待自动确认的订单。
     *
     * <p><b>必须排除 {@code auto_confirm_paused = 1} 的订单</b>（契约 §11 Q5 / §7.2）：
     * 用户对「待确认」订单发起申诉时，阶段 4 已把该标记写好，
     * 若此处不排除，就会出现「仲裁还没结论、钱已经结算给陪诊师」的资损。
     * 阶段 4 的预留就是为这一刻，不需要回头改申诉代码。
     */
    @Override
    public List<String> findAutoConfirmableOrderNos(int limit) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(jobProperties.getAutoConfirmMinutes());
        List<ServiceOrder> candidates = orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                .select(ServiceOrder::getOrderNo)
                .eq(ServiceOrder::getOrderStatus, OrderState.PENDING_CONFIRM.getCode())
                .lt(ServiceOrder::getFinishedAt, deadline)
                .orderByAsc(ServiceOrder::getFinishedAt)
                .last("LIMIT " + safeLimit(limit)));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = candidates.stream().map(ServiceOrder::getOrderNo).toList();

        // 一次性查出被申诉暂停自动确认的单，避免逐单查库（N+1）
        Set<String> paused;
        try {
            paused = appealService.findAutoConfirmPausedOrderNos(orderNos);
        } catch (Exception e) {
            // 查不到暂停标记时**宁可全部跳过**：漏自动确认只是延迟结算（用户仍可手动确认），
            // 而在申诉期间误结算则是真实资损，两者代价不对等
            log.warn("[order] 查询申诉暂停标记失败，本轮跳过全部自动确认以防资损: {}", e.getMessage());
            return List.of();
        }
        return orderNos.stream().filter(no -> !paused.contains(no)).toList();
    }

    @Override
    public List<String> findOverdueInServiceOrderNos(int limit) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(jobProperties.getAbnormalMinutes());
        return orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                        .select(ServiceOrder::getOrderNo)
                        .eq(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                        .lt(ServiceOrder::getStartedAt, deadline)
                        .orderByAsc(ServiceOrder::getStartedAt)
                        .last("LIMIT " + safeLimit(limit)))
                .stream().map(ServiceOrder::getOrderNo).toList();
    }

    @Override
    public List<ServiceOrder> findOrdersMissingTrace(int limit) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(jobProperties.getTraceTimeoutMinutes());
        return orderMapper.selectList(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                .lt(ServiceOrder::getStartedAt, deadline)
                .orderByAsc(ServiceOrder::getStartedAt)
                .last("LIMIT " + safeLimit(limit)));
    }

    /**
     * 系统自动取消超时未接单：{@code 10 -> 60}。
     *
     * <p><b>必须解冻资金</b>：下单时已 freeze 了服务费，取消若不 unfreeze，
     * 用户的钱会永久锁死在冻结态（既不结算也不退还），是明确的资损。
     */
    @Override
    @Transactional
    public boolean systemTimeoutCancel(String orderNo) {
        ServiceOrder order = getByOrderNo(orderNo);
        if (order == null || !isStatus(order, OrderState.WAITING_ACCEPT)) {
            // 已被用户取消或被陪诊师接走：正常并发，静默跳过
            return false;
        }
        stateMachineService.transition(OrderState.WAITING_ACCEPT, OrderEvent.TIMEOUT_CANCEL);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.WAITING_ACCEPT.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.CANCELLED.getCode())
                .set(ServiceOrder::getCancelReason, "超时无人接单，系统自动取消")
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            return false;
        }

        walletService.unfreeze(order.getUserId(), orderNo, order.getAmount());
        statusLogService.record(orderNo, OrderState.WAITING_ACCEPT.getCode(),
                OrderState.CANCELLED.getCode(), OrderEvent.TIMEOUT_CANCEL.name(),
                OrderStatusLogServiceImpl.OPERATOR_SYSTEM, null, "超时无人接单，系统自动取消");
        log.info("[job] 超时未接单自动取消 orderNo={}", orderNo);
        return true;
    }

    /**
     * 系统自动确认完成：{@code 40 -> 50}。
     *
     * <p><b>结算路径与手动确认逐行一致</b>（同一个 {@code walletService.settle}、
     * 同样加信用分与累计单数），唯一区别是 {@code operatorType = SYSTEM}
     * （契约 §11 Q5：「自动确认走与手动确认完全相同的 settle() 路径」）。
     * 刻意不另写一套结算逻辑——两份实现迟早会在某次改动后产生分歧，
     * 而资金路径的分歧就是资损。
     */
    @Override
    @Transactional
    public boolean systemAutoConfirm(String orderNo) {
        ServiceOrder order = getByOrderNo(orderNo);
        if (order == null || !isStatus(order, OrderState.PENDING_CONFIRM)) {
            return false;
        }
        stateMachineService.transition(OrderState.PENDING_CONFIRM, OrderEvent.AUTO_CONFIRM);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.PENDING_CONFIRM.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.COMPLETED.getCode())
                .set(ServiceOrder::getConfirmedAt, LocalDateTime.now())
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            return false;
        }

        walletService.settle(orderNo, order.getUserId(), order.getWorkerId(), order.getAmount());
        statusLogService.record(orderNo, OrderState.PENDING_CONFIRM.getCode(),
                OrderState.COMPLETED.getCode(), OrderEvent.AUTO_CONFIRM.name(),
                OrderStatusLogServiceImpl.OPERATOR_SYSTEM, null, "超时未确认，系统自动完成");
        creditService.change(order.getWorkerId(), CreditChangeType.ORDER_COMPLETED, orderNo, null);
        workerService.incrementServiceCount(order.getWorkerId());
        log.info("[job] 超时未确认自动完成并结算 orderNo={}", orderNo);
        return true;
    }

    /**
     * 系统标记异常：{@code 30 -> 70}。
     *
     * <p>资金保持冻结不动（既不 settle 也不 unfreeze），只能由管理端收尾（契约 D9）。
     */
    @Override
    @Transactional
    public boolean systemMarkAbnormal(String orderNo, String reason) {
        ServiceOrder order = getByOrderNo(orderNo);
        if (order == null || !isStatus(order, OrderState.IN_SERVICE)) {
            return false;
        }
        stateMachineService.transition(OrderState.IN_SERVICE, OrderEvent.MARK_ABNORMAL);

        String remark = StringUtils.hasText(reason) ? reason.trim() : "服务超时未结束，系统标记异常";
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getOrderStatus, OrderState.IN_SERVICE.getCode())
                .eq(ServiceOrder::getVersion, order.getVersion())
                .set(ServiceOrder::getOrderStatus, OrderState.ABNORMAL.getCode())
                .set(ServiceOrder::getAbnormalType, "OTHER")
                .set(ServiceOrder::getAbnormalRemark, remark)
                .set(ServiceOrder::getVersion, order.getVersion() + 1));
        if (updated != 1) {
            return false;
        }

        statusLogService.record(orderNo, OrderState.IN_SERVICE.getCode(),
                OrderState.ABNORMAL.getCode(), OrderEvent.MARK_ABNORMAL.name(),
                OrderStatusLogServiceImpl.OPERATOR_SYSTEM, null, remark);

        // 进管理端仲裁队列；失败不影响主流程（异常状态本身已可检索）
        try {
            appealService.createForAbnormal(orderNo,
                    order.getWorkerId() == null ? order.getUserId() : order.getWorkerId(), remark);
        } catch (Exception e) {
            log.warn("[order] 超时异常单自动建申诉工单失败 orderNo={}: {}", orderNo, e.getMessage());
        }
        log.info("[job] 服务超时未结束，标记异常 orderNo={}", orderNo);
        return true;
    }

    /** 批量上限兜底：防止配置写错（如 0 或负数）导致 LIMIT 0 让任务静默失效 */
    private int safeLimit(int limit) {
        return limit <= 0 ? jobProperties.getBatchSize() : limit;
    }

    /**
     * 订单当前状态是否等于期望状态。
     *
     * <p>用 {@code int} 比较而非 {@code Integer.equals}：{@link OrderState#getCode()} 返回<b>原始 int</b>，
     * 直接 {@code .equals(...)} 会编译失败；而把 status 拆箱成 int 比较，
     * 还能顺带避免 {@code order.getOrderStatus()} 为 null 时拆箱 NPE
     * （历史数据或异常写入都可能出现 null 状态）。
     */
    private boolean isStatus(ServiceOrder order, OrderState expected) {
        Integer current = order.getOrderStatus();
        return current != null && current == expected.getCode();
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
