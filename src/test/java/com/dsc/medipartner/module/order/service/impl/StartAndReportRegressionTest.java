package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.ai.service.ReportService;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.job.config.JobProperties;
import com.dsc.medipartner.module.order.domain.dto.StartServiceRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.ReportBriefVO;
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
import com.dsc.medipartner.module.user.domain.entity.SysUser;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 6 联调缺陷回归测试。
 *
 * <p>本类覆盖前端真实联调报告中发现的两个后端缺陷：
 * <ol>
 *   <li><b>{@code start()} 未校验 watermarkTime</b>——陪诊师可传伪造时间开始服务，
 *       绕过防伪造水印风控。而 {@code /trace} 与 {@code /finish} 都校验了，唯独 start 遗漏。</li>
 *   <li><b>{@code OrderDetailVO.report} 从未被赋值</b>——详情页的 AI 报告入口卡片永不渲染。</li>
 * </ol>
 *
 * <p><b>为什么这两个缺陷此前没被测出</b>：单测只覆盖各自方法的主逻辑，
 * 不比较「同类入口是否行为一致」；而 {@code report} 是 VO 字段，
 * 没有赋值也不会抛异常，只是静默缺失。
 * 因此本类专门锁死「三处留痕入口行为一致」与「report 键按条件下发」。
 */
class StartAndReportRegressionTest {

    private static final long WORKER_ID = 10003L;
    private static final long USER_ID = 10002L;
    private static final String ORDER_NO = "MP20261012000000000001";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ServiceOrderMapper orderMapper;
    private TraceService traceService;
    private ReportService reportService;
    private OrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(ServiceOrderMapper.class);
        traceService = mock(TraceService.class);
        reportService = mock(ReportService.class);
        service = new OrderServiceImpl(orderMapper, mock(WalletService.class), mock(WorkerService.class),
                mock(OrderStateMachineService.class), traceService, mock(ReportTaskPublisher.class),
                mock(DemandService.class), mock(CreditService.class), mock(PriceService.class),
                mock(UserService.class), mock(ReviewService.class), new ActionResolver(), new OrderAssembler(),
                new VirtualPhoneService(), mock(OrderStatusLogService.class), mock(AppealService.class),
                new JobProperties(), reportService);
    }

    /** 处于「已接单」状态的订单 */
    private ServiceOrder acceptedOrder() {
        ServiceOrder o = new ServiceOrder();
        o.setId(1L);
        o.setOrderNo(ORDER_NO);
        o.setUserId(USER_ID);
        o.setWorkerId(WORKER_ID);
        o.setOrderStatus(OrderState.ACCEPTED.getCode());
        o.setVersion(0);
        return o;
    }

    private StartServiceRequest startRequest(String watermarkTime, String imageUrl) {
        StartServiceRequest r = new StartServiceRequest();
        r.setGpsLat("23.106590");
        r.setGpsLng("113.324553");
        r.setWatermarkTime(watermarkTime);
        r.setImageUrl(imageUrl);
        return r;
    }

    // ---------------- 缺陷 1：start 的水印校验 ----------------

    /**
     * <b>核心回归</b>：水印时间偏差过大必须抛 {@code 8004}。
     *
     * <p>修复前此处返回成功，是真实的越权风险：陪诊师可传任意时间伪造到场凭证。
     */
    @Test
    void startRejectsWatermarkTooFarFromServerTime() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());

        // 偏差 10 小时（联调报告实测的正是这个值）
        String forged = LocalDateTime.now().minusHours(10).format(TS);

        assertThatThrownBy(() -> service.start(WORKER_ID, ORDER_NO, startRequest(forged, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.WATERMARK_TIME_INVALID);
    }

    /** 未来时间同样要拦（伪造「提前到场」比伪造过去时间更常见） */
    @Test
    void startRejectsFutureWatermark() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());

        String future = LocalDateTime.now().plusHours(3).format(TS);

        assertThatThrownBy(() -> service.start(WORKER_ID, ORDER_NO, startRequest(future, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.WATERMARK_TIME_INVALID);
    }

    /** 校验必须在状态流转之前：不合格请求不得产生任何状态副作用 */
    @Test
    void invalidWatermarkDoesNotTouchStateOrTrace() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());

        String forged = LocalDateTime.now().minusHours(10).format(TS);
        assertThatThrownBy(() -> service.start(WORKER_ID, ORDER_NO, startRequest(forged, "/files/x.jpg")))
                .isInstanceOf(BizException.class);

        // 状态与留痕都不能被写入
        verify(orderMapper, never()).update(any(), any());
        verify(traceService, never()).saveArrivalTrace(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 格式非法抛 1001（与 /trace、/finish 口径一致） */
    @Test
    void startRejectsMalformedWatermark() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());

        assertThatThrownBy(() -> service.start(WORKER_ID, ORDER_NO, startRequest("2026/10/12 10:00", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    /** 时间合法时正常通过校验并落状态（用固定「现在」保证不失稳） */
    @Test
    void startAcceptsWatermarkWithinWindow() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        String now = LocalDateTime.now().format(TS);

        // 不抛异常即为通过（后续 mock 的 update 返回 1）
        service.start(WORKER_ID, ORDER_NO, startRequest(now, null));
    }

    /** 空水印不校验（保持 WatermarkTimes 的既有语义：null 跳过） */
    @Test
    void nullWatermarkSkipsValidation() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.start(WORKER_ID, ORDER_NO, startRequest(null, null));
    }

    // ---------------- 缺陷 1 附带：到场留痕落库 ----------------

    /**
     * 带 imageUrl 时应落「到场」首条留痕（契约 §7.11「事务与联动」）。
     *
     * <p>此前 {@code start()} 完全不写 {@code order_trace}，
     * 导致「到场」这一关键节点在留痕列表与 AI 报告输入中永久缺失。
     */
    @Test
    void startWithImageSavesArrivalTrace() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        String now = LocalDateTime.now().format(TS);
        service.start(WORKER_ID, ORDER_NO, startRequest(now, "/files/ORDER_TRACE/20261012/a.jpg"));

        verify(traceService).saveArrivalTrace(eq(ORDER_NO), eq(WORKER_ID),
                eq("/files/ORDER_TRACE/20261012/a.jpg"),
                eq("23.106590"), eq("113.324553"), isNull(), any(LocalDateTime.class), isNull());
    }

    /**
     * 无 imageUrl 时传入空图片路径。
     *
     * <p>「是否落库」的判定在 {@code TraceServiceImpl.saveArrivalTrace} 内部
     * （它有 {@code hasText(imageUrl)} 前置判断），{@code start()} 一律调用，
     * 因此这里断言的是<b>传入了空值</b>，而不是「没调用」。
     * 真正的「空值不落库」由 {@code TraceServiceImplTest} 覆盖。
     */
    @Test
    void startWithoutImagePassesNullImageUrl() {
        when(orderMapper.selectOne(any())).thenReturn(acceptedOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        String now = LocalDateTime.now().format(TS);
        service.start(WORKER_ID, ORDER_NO, startRequest(now, null));

        verify(traceService).saveArrivalTrace(eq(ORDER_NO), eq(WORKER_ID), isNull(),
                any(), any(), any(), any(LocalDateTime.class), any());
    }

    // ---------------- 缺陷 2：详情页 report 摘要 ----------------

    /**
     * <b>核心回归</b>：报告存在时详情必须下发 {@code report}。
     *
     * <p>修复前该字段永远为 null（从未被赋值），前端入口卡片永不渲染。
     */
    @Test
    void userDetailIncludesReportBriefWhenReportExists() {
        ServiceOrder completed = acceptedOrder();
        completed.setOrderStatus(OrderState.COMPLETED.getCode());
        when(orderMapper.selectOne(any())).thenReturn(completed);
        when(reportService.getReportBrief(ORDER_NO)).thenReturn(brief("DONE"));

        var vo = service.getUserOrderDetail(USER_ID, ORDER_NO);

        assertThat(vo.getReport()).isNotNull();
        assertThat(vo.getReport().getStatus()).isEqualTo("DONE");
    }

    /** 无报告时不下发该键（契约 §5.4「无报告时键缺失」） */
    @Test
    void userDetailOmitsReportWhenAbsent() {
        ServiceOrder completed = acceptedOrder();
        completed.setOrderStatus(OrderState.COMPLETED.getCode());
        when(orderMapper.selectOne(any())).thenReturn(completed);
        when(reportService.getReportBrief(ORDER_NO)).thenReturn(null);

        var vo = service.getUserOrderDetail(USER_ID, ORDER_NO);

        assertThat(vo.getReport()).isNull();
    }

    /** 陪诊师侧同样下发报告摘要（契约 §7.8：只看状态不看正文） */
    @Test
    void workerDetailIncludesReportBrief() {
        ServiceOrder completed = acceptedOrder();
        completed.setOrderStatus(OrderState.COMPLETED.getCode());
        when(orderMapper.selectOne(any())).thenReturn(completed);
        when(reportService.getReportBrief(ORDER_NO)).thenReturn(brief("PROCESSING"));

        var vo = service.getWorkerOrderDetail(WORKER_ID, ORDER_NO);

        assertThat(vo.getReport()).isNotNull();
        assertThat(vo.getReport().getStatus()).isEqualTo("PROCESSING");
    }

    /**
     * 报告查询失败不能拖垮整个详情页。
     *
     * <p>报告摘要只是入口角标，不该因为它查不到就让用户连订单详情都看不了。
     */
    @Test
    void reportQueryFailureDoesNotBreakDetail() {
        ServiceOrder completed = acceptedOrder();
        completed.setOrderStatus(OrderState.COMPLETED.getCode());
        when(orderMapper.selectOne(any())).thenReturn(completed);
        when(reportService.getReportBrief(ORDER_NO)).thenThrow(new RuntimeException("db down"));

        var vo = service.getUserOrderDetail(USER_ID, ORDER_NO);

        assertThat(vo).isNotNull();
        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getReport()).isNull();
    }

    private ReportBriefVO brief(String status) {
        ReportBriefVO b = new ReportBriefVO();
        b.setStatus(status);
        if (!"PROCESSING".equals(status)) {
            b.setGeneratedAt(LocalDateTime.now());
        }
        return b;
    }
}
