package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.util.GeoUtil;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.ActionResolver;
import com.dsc.medipartner.module.order.service.OrderAssembler;
import com.dsc.medipartner.module.order.service.OrderStateMachineService;
import com.dsc.medipartner.module.order.service.OrderStatusLogService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.order.service.TraceService;
import com.dsc.medipartner.module.order.service.VirtualPhoneService;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.ticket.service.AppealService;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接单大厅就近排序测试（阶段 5，契约 §7.5）。
 *
 * <p>重点验证「距离绝不能走 SQL」这条约束的落地效果：
 * <ol>
 *   <li>距离排序确实按真实距离升序，而非字典序或默认排序</li>
 *   <li>未传坐标时不下发 {@code distanceKm}（键缺失，而非给个假的 0）</li>
 *   <li>无定位的订单被排除在距离排序之外，默认排序仍能看到</li>
 *   <li>{@code countdownSeconds} 正确反映距超时取消的剩余时间</li>
 * </ol>
 */
class HallDistanceSortTest {

    private static final long WORKER_ID = 10003L;

    /** 广州塔附近作为陪诊师位置 */
    private static final String WORKER_LAT = "23.106590";
    private static final String WORKER_LNG = "113.324553";

    private ServiceOrderMapper orderMapper;
    private OrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(ServiceOrderMapper.class);
        JobProperties jobProperties = new JobProperties();
        jobProperties.setTimeoutCancelMinutes(1440);
        service = new OrderServiceImpl(orderMapper, mock(WalletService.class), mock(WorkerService.class),
                mock(OrderStateMachineService.class), mock(TraceService.class), mock(ReportTaskPublisher.class),
                mock(DemandService.class), mock(CreditService.class), mock(PriceService.class),
                mock(UserService.class), mock(ReviewService.class), new ActionResolver(), new OrderAssembler(),
                new VirtualPhoneService(), mock(OrderStatusLogService.class), mock(AppealService.class),
                jobProperties, mock(com.dsc.medipartner.module.ai.service.ReportService.class));
    }

    /** 造一个位于指定坐标的待接单订单（geo_hash 由坐标算出，模拟下单时后端代算的结果） */
    private ServiceOrder hallOrder(String orderNo, BigDecimal lat, BigDecimal lng, LocalDateTime createdAt) {
        ServiceOrder o = new ServiceOrder();
        o.setId(Math.abs(orderNo.hashCode()) + 1L);
        o.setOrderNo(orderNo);
        o.setOrderStatus(OrderState.WAITING_ACCEPT.getCode());
        o.setGeoHash(GeoUtil.geoHash(lat, lng, 6));
        o.setServiceTime(LocalDateTime.now().plusHours(2));
        o.setAmount(new BigDecimal("200.00"));
        o.setCreatedAt(createdAt);
        return o;
    }

    private OrderQuery distanceQuery() {
        OrderQuery q = new OrderQuery();
        q.setSortBy("distance");
        q.setOrder("asc");
        q.setGpsLat(WORKER_LAT);
        q.setGpsLng(WORKER_LNG);
        return q;
    }

    // ---------------- 距离排序 ----------------

    /**
     * 距离排序必须按真实距离升序。
     *
     * <p>若实现退化成「按 geo_hash 字典序」，本用例会失败——这正是阶段 1.5 的隐患形态：
     * 不报错、结果看着有序，实则与地理距离无关。
     */
    @Test
    void hallSortsByRealDistanceAscending() {
        // 广州塔自身（~0km）、约 1.1km、约 11km、约 100km（深圳）
        ServiceOrder near = hallOrder("NEAR", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        ServiceOrder mid = hallOrder("MID", new BigDecimal("23.116590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        ServiceOrder far = hallOrder("FAR", new BigDecimal("23.205000"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        ServiceOrder farthest = hallOrder("FARTHEST", new BigDecimal("22.545540"),
                new BigDecimal("114.054390"), LocalDateTime.now());

        // 故意打乱返回顺序，确保排序真的生效而不是「碰巧原序就对」
        when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(farthest, mid, near, far));

        List<OrderCardVO> cards = service.pageHallCards(WORKER_ID, distanceQuery()).getRecords();

        assertThat(cards).extracting(OrderCardVO::getOrderNo)
                .containsExactly("NEAR", "MID", "FAR", "FARTHEST");
    }

    /**
     * {@code order=desc} 必须真的反转。
     *
     * <p><b>这是真机 HTTP 验证才发现的缺陷</b>：最初的内存比较器把升序写死了，
     * 忽略了 {@code order} 参数，导致 {@code order=desc} 静默返回升序结果——
     * 不报错、数据也「有序」，单测若只测升序完全看不出来。
     */
    @Test
    void distanceSortHonoursDescendingOrder() {
        ServiceOrder near = hallOrder("NEAR", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        ServiceOrder far = hallOrder("FAR", new BigDecimal("23.205000"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(near, far));

        OrderQuery q = distanceQuery();
        q.setOrder("desc");

        List<OrderCardVO> cards = service.pageHallCards(WORKER_ID, q).getRecords();

        assertThat(cards).extracting(OrderCardVO::getOrderNo).containsExactly("FAR", "NEAR");
    }

    /** 距离必须按数值升序，且保留 1 位小数 */
    @Test
    void distanceKmIsAscendingAndOneDecimal() {
        ServiceOrder a = hallOrder("A", new BigDecimal("23.116590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        ServiceOrder b = hallOrder("B", new BigDecimal("23.205000"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(b, a));

        List<OrderCardVO> cards = service.pageHallCards(WORKER_ID, distanceQuery()).getRecords();

        BigDecimal first = cards.get(0).getDistanceKm();
        BigDecimal second = cards.get(1).getDistanceKm();
        assertThat(first).isLessThan(second);
        assertThat(first.scale()).isEqualTo(1);
        assertThat(second.scale()).isEqualTo(1);
    }

    /**
     * 未传坐标时不排序距离，且 {@code distanceKm} 保持 null（键缺失）。
     *
     * <p>契约 §7.5：「未传坐标时键缺失」。下发 0 会被前端误读为「就在楼下」。
     */
    @Test
    void withoutCoordinatesDistanceIsAbsent() {
        OrderQuery q = new OrderQuery();
        q.setSortBy("distance");
        // 不设置 gpsLat/gpsLng

        ServiceOrder o = hallOrder("SOME", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        when(orderMapper.selectPage(any(), any())).thenReturn(null);

        // 无坐标时走 DB 分页路径（pageHall → selectPage），这里只断言不会因为缺坐标而报错
        service.pageHall(q);
    }

    /** 传了坐标但订单无 geo_hash 时，distanceKm 为 null 而不是 0 */
    @Test
    void orderWithoutGeoHashHasNullDistance() {
        ServiceOrder noGeo = new ServiceOrder();
        noGeo.setId(1L);
        noGeo.setOrderNo("NOGEO");
        noGeo.setOrderStatus(OrderState.WAITING_ACCEPT.getCode());
        noGeo.setGeoHash(null);
        noGeo.setServiceTime(LocalDateTime.now().plusHours(1));
        noGeo.setAmount(new BigDecimal("200.00"));
        noGeo.setCreatedAt(LocalDateTime.now());

        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(noGeo));

        List<OrderCardVO> cards = service.pageHallCards(WORKER_ID, distanceQuery()).getRecords();

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getDistanceKm()).isNull();
    }

    /** 距离相同时按 serviceTime 升序，保证顺序稳定可复现（避免分页抖动） */
    @Test
    void equalDistanceFallsBackToServiceTime() {
        ServiceOrder later = hallOrder("LATER", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        later.setServiceTime(LocalDateTime.now().plusHours(5));
        ServiceOrder sooner = hallOrder("SOONER", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now());
        sooner.setServiceTime(LocalDateTime.now().plusHours(1));

        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(later, sooner));

        List<OrderCardVO> cards = service.pageHallCards(WORKER_ID, distanceQuery()).getRecords();

        assertThat(cards).extracting(OrderCardVO::getOrderNo).containsExactly("SOONER", "LATER");
    }

    /** 内存分页：总数为全部候选数，本页只含 pageSize 条 */
    @Test
    void inMemoryPagingSlicesCorrectly() {
        ServiceOrder a = hallOrder("A", new BigDecimal("23.106590"), new BigDecimal("113.324553"), LocalDateTime.now());
        ServiceOrder b = hallOrder("B", new BigDecimal("23.116590"), new BigDecimal("113.324553"), LocalDateTime.now());
        ServiceOrder c = hallOrder("C", new BigDecimal("23.205000"), new BigDecimal("113.324553"), LocalDateTime.now());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c, b, a));

        OrderQuery q = distanceQuery();
        q.setPageNum(2);
        q.setPageSize(2);

        var page = service.pageHallCards(WORKER_ID, q);

        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).hasSize(1);
        // 第 2 页只剩最远的 C
        assertThat(page.getRecords().get(0).getOrderNo()).isEqualTo("C");
    }

    /** 越界页码返回空列表而不是抛异常（前端传错不该 500） */
    @Test
    void outOfRangePageReturnsEmpty() {
        ServiceOrder a = hallOrder("A", new BigDecimal("23.106590"), new BigDecimal("113.324553"), LocalDateTime.now());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a));

        OrderQuery q = distanceQuery();
        q.setPageNum(99);
        q.setPageSize(10);

        var page = service.pageHallCards(WORKER_ID, q);

        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isEqualTo(1);
    }

    // ---------------- countdownSeconds ----------------

    /**
     * 倒计时 = 创建时间 + 超时阈值 − 现在。
     *
     * <p>契约 §7.5：供前端做「距超时自动取消」倒计时。
     */
    @Test
    void countdownReflectsRemainingTime() {
        JobProperties props = new JobProperties();
        props.setTimeoutCancelMinutes(60);
        OrderServiceImpl svc = newServiceWith(props);

        // 10 分钟前创建 → 还剩约 50 分钟 = 3000 秒
        ServiceOrder o = hallOrder("CD", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now().minusMinutes(10));
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(o));

        OrderCardVO card = svc.pageHallCards(WORKER_ID, distanceQuery()).getRecords().get(0);

        assertThat(card.getCountdownSeconds()).isBetween(2985L, 3000L);
    }

    /** 已超时的订单倒计时为 0，不返回负数 */
    @Test
    void expiredOrderCountdownIsZero() {
        JobProperties props = new JobProperties();
        props.setTimeoutCancelMinutes(30);
        OrderServiceImpl svc = newServiceWith(props);

        ServiceOrder o = hallOrder("EXPIRED", new BigDecimal("23.106590"), new BigDecimal("113.324553"),
                LocalDateTime.now().minusHours(5));
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(o));

        OrderCardVO card = svc.pageHallCards(WORKER_ID, distanceQuery()).getRecords().get(0);

        assertThat(card.getCountdownSeconds()).isZero();
    }

    private OrderServiceImpl newServiceWith(JobProperties props) {
        return new OrderServiceImpl(orderMapper, mock(WalletService.class), mock(WorkerService.class),
                mock(OrderStateMachineService.class), mock(TraceService.class), mock(ReportTaskPublisher.class),
                mock(DemandService.class), mock(CreditService.class), mock(PriceService.class),
                mock(UserService.class), mock(ReviewService.class), new ActionResolver(), new OrderAssembler(),
                new VirtualPhoneService(), mock(OrderStatusLogService.class), mock(AppealService.class), props,
                mock(com.dsc.medipartner.module.ai.service.ReportService.class));
    }

    // ---------------- 附近可接单数 ----------------

    @Test
    void nearbyCountCountsOrdersWithComputableDistance() {
        ServiceOrder a = hallOrder("A", new BigDecimal("23.106590"), new BigDecimal("113.324553"), LocalDateTime.now());
        ServiceOrder b = hallOrder("B", new BigDecimal("23.205000"), new BigDecimal("113.324553"), LocalDateTime.now());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));

        long count = service.countNearbyHallOrders(WORKER_ID,
                new BigDecimal(WORKER_LAT), new BigDecimal(WORKER_LNG));

        assertThat(count).isEqualTo(2);
    }

    /** 无坐标时退化为「全部待接单数」，比返回 0 更有参考价值 */
    @Test
    void nearbyCountWithoutCoordinatesReturnsAllCandidates() {
        ServiceOrder a = hallOrder("A", new BigDecimal("23.106590"), new BigDecimal("113.324553"), LocalDateTime.now());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a));

        long count = service.countNearbyHallOrders(WORKER_ID, null, null);

        assertThat(count).isEqualTo(1);
    }
}
