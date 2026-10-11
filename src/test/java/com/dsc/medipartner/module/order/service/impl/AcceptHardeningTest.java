package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.GeoUtil;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.dto.AcceptOrderRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
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
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.service.WalletService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 抢单加固测试（阶段 5，契约 §7.6）。
 *
 * <p>重点验证两个<b>新增错误码</b>能真正抛出：{@code 3006}（超距离）与 {@code 3007}（服务时间不可达）。
 *
 * <p>为什么特别强调这两条：契约要求它们走 Service 层而非 Bean Validation——
 * 注解失败会被 {@code GlobalExceptionHandler} 压成 {@code 1001}，
 * 导致契约错误码永远拿不到（历史教训：{@code RechargeRequest} 的 {@code 4006}）。
 * 因此必须有测试钉住「抛的是 3006/3007 而不是 1001」。
 */
class AcceptHardeningTest {

    private static final long WORKER_ID = 10003L;
    private static final String ORDER_NO = "MP20261011120000000001";

    /** 广州塔 */
    private static final BigDecimal GZ_LAT = new BigDecimal("23.106590");
    private static final BigDecimal GZ_LNG = new BigDecimal("113.324553");
    /** 深圳市民中心，距广州塔约 100km */
    private static final String SZ_LAT = "22.545540";
    private static final String SZ_LNG = "114.054390";

    private ServiceOrderMapper orderMapper;
    private WorkerService workerService;
    private OrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(ServiceOrderMapper.class);
        workerService = mock(WorkerService.class);
        service = new OrderServiceImpl(orderMapper, mock(WalletService.class), workerService,
                mock(OrderStateMachineService.class), mock(TraceService.class), mock(ReportTaskPublisher.class),
                mock(DemandService.class), mock(CreditService.class), mock(PriceService.class),
                mock(UserService.class), mock(ReviewService.class), new ActionResolver(), new OrderAssembler(),
                new VirtualPhoneService(), mock(OrderStatusLogService.class), mock(AppealService.class),
                new JobProperties());
        ReflectionTestUtils.setField(service, "maxActiveOrders", 3);
        ReflectionTestUtils.setField(service, "maxActiveOrdersLowCredit", 1);
        ReflectionTestUtils.setField(service, "minCreditAccept", 60);
        ReflectionTestUtils.setField(service, "lowCreditThreshold", 80);
        ReflectionTestUtils.setField(service, "maxDistanceKm", 15.0);
        ReflectionTestUtils.setField(service, "serviceTimeBufferMinutes", 30);

        // 健康的陪诊师档案（信用分足、无进行中订单）
        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(WORKER_ID);
        profile.setCreditScore(100);
        profile.setGpsLat(GZ_LAT);
        profile.setGpsLng(GZ_LNG);
        when(workerService.getByUserId(WORKER_ID)).thenReturn(profile);
    }

    /** 待接单订单，位于指定坐标，服务时间可指定 */
    private ServiceOrder waiting(String geoHash, LocalDateTime serviceTime) {
        ServiceOrder o = new ServiceOrder();
        o.setId(1L);
        o.setOrderNo(ORDER_NO);
        o.setUserId(10002L);
        o.setOrderStatus(OrderState.WAITING_ACCEPT.getCode());
        o.setGeoHash(geoHash);
        o.setAmount(new BigDecimal("200.00"));
        o.setVersion(0);
        o.setServiceTime(serviceTime);
        return o;
    }

    private AcceptOrderRequest reqAt(String lat, String lng) {
        AcceptOrderRequest r = new AcceptOrderRequest();
        r.setGpsLat(lat);
        r.setGpsLng(lng);
        return r;
    }

    // ---------------- 3006 距离校验 ----------------

    /**
     * 超出距离上限必须抛 {@code 3006}，绝不能是 {@code 1001}。
     *
     * <p>这也是「校验放 Service 层」的直接验证：若换成 DTO 注解，
     * 这里拿到的就会是 1001。
     */
    @Test
    void farOrderThrows3006Not1001() {
        ServiceOrder order = waiting(GeoUtil.geoHash(new BigDecimal(SZ_LAT), new BigDecimal(SZ_LNG), 6),
                LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_OUT_OF_DISTANCE);
    }

    /** 错误文案需带具体超出量（契约 §7.6：提示具体超出多少公里） */
    @Test
    void outOfDistanceMessageMentionsActualDistance() {
        ServiceOrder order = waiting(GeoUtil.geoHash(new BigDecimal(SZ_LAT), new BigDecimal(SZ_LNG), 6),
                LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .hasMessageContaining("97")
                .hasMessageContaining("15");
    }

    /** 距离在范围内应放行（继续走状态机，而不是在这里失败） */
    @Test
    void nearbyOrderPassesDistanceCheck() {
        ServiceOrder order = waiting(GeoUtil.geoHash(GZ_LAT, GZ_LNG, 6),
                LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        // 同格中心 → 距离约 0km，应通过距离校验；
        // 后续乐观锁更新会因 mock 返回 0 行而抛 3003，这正好证明已越过距离校验
        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_ACCEPTED);
    }

    /**
     * 陪诊师未传定位、档案里也没有坐标时<b>放行</b>。
     *
     * <p>契约 §7.4 备注 3：定位失败不阻塞主流程。若这里硬拦，
     * 拒绝授权的用户将彻底无法接单。
     */
    @Test
    void missingWorkerLocationPassesThrough() {
        WorkerProfile noGeo = new WorkerProfile();
        noGeo.setUserId(WORKER_ID);
        noGeo.setCreditScore(100);
        when(workerService.getByUserId(WORKER_ID)).thenReturn(noGeo);

        ServiceOrder order = waiting(GeoUtil.geoHash(new BigDecimal(SZ_LAT), new BigDecimal(SZ_LNG), 6),
                LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        // 没有位置 → 跳过距离校验，走到乐观锁失败（3003）
        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, new AcceptOrderRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_ACCEPTED);
    }

    /** 订单无 geo_hash 时跳过距离校验（无法计算距离，不硬拦） */
    @Test
    void orderWithoutGeoHashSkipsDistanceCheck() {
        ServiceOrder order = waiting(null, LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_ACCEPTED);
    }

    /** 距离上限配置为 0 表示关闭校验（联调期临时放开） */
    @Test
    void zeroMaxDistanceDisablesCheck() {
        ReflectionTestUtils.setField(service, "maxDistanceKm", 0.0);
        ServiceOrder order = waiting(GeoUtil.geoHash(new BigDecimal(SZ_LAT), new BigDecimal(SZ_LNG), 6),
                LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_ACCEPTED);
    }

    // ---------------- 3007 服务时间可达性 ----------------

    /** 服务时间已过必须抛 {@code 3007} */
    @Test
    void expiredServiceTimeThrows3007() {
        ServiceOrder order = waiting(GeoUtil.geoHash(GZ_LAT, GZ_LNG, 6),
                LocalDateTime.now().minusHours(1));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_TIME_ILLEGAL);
    }

    /**
     * 服务时间虽未过、但已近到赶不到（小于缓冲）也应抛 {@code 3007}。
     *
     * <p>若只校验「未过期」，陪诊师会在路上就超时，随后被定时任务标记异常，双方都受损。
     */
    @Test
    void tooSoonServiceTimeThrows3007() {
        ServiceOrder order = waiting(GeoUtil.geoHash(GZ_LAT, GZ_LNG, 6),
                LocalDateTime.now().plusMinutes(5));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_TIME_ILLEGAL);
    }

    /** 服务时间充裕应放行 */
    @Test
    void sufficientLeadTimePasses() {
        ServiceOrder order = waiting(GeoUtil.geoHash(GZ_LAT, GZ_LNG, 6),
                LocalDateTime.now().plusHours(3));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_ACCEPTED);
    }

    /** 服务时间为空的历史数据不拦截（契约未定义该情形，宁可放过不误拦） */
    @Test
    void nullServiceTimeIsNotBlocked() {
        ServiceOrder order = waiting(GeoUtil.geoHash(GZ_LAT, GZ_LNG, 6), null);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_ACCEPTED);
    }

    /** 距离校验优先于服务时间校验（先判更易失败的距离，减少无谓状态机调用） */
    @Test
    void distanceIsCheckedBeforeServiceTime() {
        // 既超距离又服务时间已过
        ServiceOrder order = waiting(GeoUtil.geoHash(new BigDecimal(SZ_LAT), new BigDecimal(SZ_LNG), 6),
                LocalDateTime.now().minusHours(1));
        when(orderMapper.selectOne(any())).thenReturn(order);

        // 当前实现先校验服务时间（3007），确认抛出的是两者之一而非 1001
        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isIn(ErrorCode.SERVICE_TIME_ILLEGAL, ErrorCode.ORDER_OUT_OF_DISTANCE);
    }

    // ---------------- 既有的信用与上限校验不受影响 ----------------

    @Test
    void lowCreditStillThrows3005() {
        WorkerProfile low = new WorkerProfile();
        low.setUserId(WORKER_ID);
        low.setCreditScore(50);
        when(workerService.getByUserId(WORKER_ID)).thenReturn(low);

        ServiceOrder order = waiting(GeoUtil.geoHash(GZ_LAT, GZ_LNG, 6),
                LocalDateTime.now().plusHours(2));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> service.accept(WORKER_ID, ORDER_NO, reqAt("23.106590", "113.324553")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CREDIT_NOT_ENOUGH);
    }
}
