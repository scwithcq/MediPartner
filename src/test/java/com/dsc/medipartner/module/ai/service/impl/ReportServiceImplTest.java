package com.dsc.medipartner.module.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.ai.ChatModelFactory;
import com.dsc.medipartner.module.ai.config.AiProperties;
import com.dsc.medipartner.module.ai.domain.entity.AiReport;
import com.dsc.medipartner.module.ai.domain.vo.ReportVO;
import com.dsc.medipartner.module.ai.mapper.AiReportMapper;
import com.dsc.medipartner.module.ai.service.DegradedReportGenerator;
import com.dsc.medipartner.module.ai.service.ReportPromptBuilder;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReportServiceImpl 纯单测：读取归属/状态闸门、PROCESSING/DONE/FAILED 分支与重试，
 * 以及 MQ 消费入口 handle 的降级生成与失败落库。
 */
class ReportServiceImplTest {

    private static final long USER_ID = 9L;
    private static final String ORDER_NO = "MP20260921000000123456";
    private static final String VALID_JSON = """
            {"title":"陪诊服务报告","summary":"本次服务顺利完成。","sections":[{"heading":"服务概览","content":"全程陪诊。"}],"advice":["按时复诊","保留票据"]}""";

    private AiReportMapper reportMapper;
    private ServiceOrderMapper orderMapper;
    private TraceService traceService;
    private ChatModelFactory chatModelFactory;
    private ReportTaskPublisher reportTaskPublisher;
    private ChatModel chatModel;
    private ReportServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiReport.class);
        TableInfoHelper.initTableInfo(assistant, ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        reportMapper = mock(AiReportMapper.class);
        orderMapper = mock(ServiceOrderMapper.class);
        traceService = mock(TraceService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        reportTaskPublisher = mock(ReportTaskPublisher.class);
        chatModel = mock(ChatModel.class);
        service = new ReportServiceImpl(reportMapper, orderMapper, traceService, chatModelFactory,
                new ReportPromptBuilder(), new DegradedReportGenerator(), reportTaskPublisher,
                new ObjectMapper(), new AiProperties());
        when(chatModelFactory.providerName()).thenReturn("deepseek");
    }

    private ServiceOrder order(int status, long ownerUserId) {
        ServiceOrder order = new ServiceOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(ownerUserId);
        order.setWorkerId(7L);
        order.setOrderStatus(status);
        order.setHospitalPref("重医附一院");
        order.setSymptomDesc("胸口疼");
        order.setCity("重庆");
        order.setServiceType("ACCOMPANY");
        order.setServiceSummary("服务完成");
        order.setActualHours(new BigDecimal("2.0"));
        order.setAmount(new BigDecimal("200.00"));
        return order;
    }

    private AiReport report(String status, Integer retryCount) {
        AiReport report = new AiReport();
        report.setId(50L);
        report.setOrderNo(ORDER_NO);
        report.setStatus(status);
        report.setRetryCount(retryCount);
        report.setVersion(1);
        return report;
    }

    private OrderTrace trace() {
        OrderTrace trace = new OrderTrace();
        trace.setOrderNo(ORDER_NO);
        trace.setType("TRACE");
        trace.setNode("ARRIVED");
        trace.setWatermarkTime(LocalDateTime.now());
        trace.setAddress("重医附一院");
        return trace;
    }

    @Test
    void getReportRejectsNonOwnerWith3008() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), 123L));
        assertThatThrownBy(() -> service.getReport(USER_ID, ORDER_NO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
    }

    @Test
    void getReportRejectsUnfinishedWith3002() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), USER_ID));
        assertThatThrownBy(() -> service.getReport(USER_ID, ORDER_NO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
    }

    @Test
    void getReportCreatesProcessingWhenNoRow() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        when(reportMapper.selectOne(any())).thenReturn(null);

        ReportVO vo = service.getReport(USER_ID, ORDER_NO);

        assertThat(vo.getStatus()).isEqualTo("PROCESSING");
        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getVisitInfo().getHospital()).isEqualTo("重医附一院");
        verify(reportMapper).insert(any(AiReport.class));
        verify(reportTaskPublisher).publishReportTask(ORDER_NO);
    }

    @Test
    void getReportReturnsDoneBody() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        AiReport report = report("DONE", 0);
        report.setTitle("陪诊服务报告");
        report.setSummary("本次服务顺利完成。");
        report.setSections("[{\"heading\":\"服务概览\",\"content\":\"全程陪诊。\"}]");
        report.setAdvice("[\"按时复诊\",\"保留票据\"]");
        report.setProvider("deepseek");
        report.setDegraded(0);
        report.setGeneratedAt(LocalDateTime.now());
        when(reportMapper.selectOne(any())).thenReturn(report);

        ReportVO vo = service.getReport(USER_ID, ORDER_NO);

        assertThat(vo.getStatus()).isEqualTo("DONE");
        assertThat(vo.getTitle()).isEqualTo("陪诊服务报告");
        assertThat(vo.getSections()).hasSize(1);
        assertThat(vo.getSections().get(0).getHeading()).isEqualTo("服务概览");
        assertThat(vo.getAdvice()).containsExactly("按时复诊", "保留票据");
        assertThat(vo.getDegraded()).isFalse();
        verify(reportTaskPublisher, never()).publishReportTask(any());
    }

    @Test
    void getReportRetriesFailedUnderLimit() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        when(reportMapper.selectOne(any())).thenReturn(report("FAILED", 1));

        ReportVO vo = service.getReport(USER_ID, ORDER_NO);

        assertThat(vo.getStatus()).isEqualTo("PROCESSING");
        verify(reportMapper).update(any(), any());
        verify(reportTaskPublisher).publishReportTask(ORDER_NO);
    }

    @Test
    void getReportReturnsFailedWhenRetryExhausted() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        AiReport report = report("FAILED", 3);
        report.setFailReason("AI 服务暂时不可用，报告生成失败，请稍后重试");
        when(reportMapper.selectOne(any())).thenReturn(report);

        ReportVO vo = service.getReport(USER_ID, ORDER_NO);

        assertThat(vo.getStatus()).isEqualTo("FAILED");
        assertThat(vo.getFailReason()).isNotBlank();
        verify(reportTaskPublisher, never()).publishReportTask(any());
    }

    @Test
    void handleUsesDegradedGeneratorWithoutKey() {
        when(chatModelFactory.hasKey()).thenReturn(false);
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        when(reportMapper.selectOne(any())).thenReturn(null);
        when(traceService.listByOrder(ORDER_NO)).thenReturn(List.of(trace()));

        service.handle(ORDER_NO);

        verify(reportMapper).insert(any(AiReport.class));
        verify(reportMapper).update(any(), any());
        verify(chatModelFactory, never()).chatModel();
    }

    @Test
    void handleSucceedsWithModelAndMarksDone() {
        when(chatModelFactory.hasKey()).thenReturn(true);
        when(chatModelFactory.chatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(VALID_JSON)))));
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        when(reportMapper.selectOne(any())).thenReturn(null);
        when(traceService.listByOrder(ORDER_NO)).thenReturn(List.of());

        service.handle(ORDER_NO);

        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(reportMapper).update(any(), any());
    }

    @Test
    void handleMarksFailedWhenModelThrows() {
        when(chatModelFactory.hasKey()).thenReturn(true);
        when(chatModelFactory.chatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.PENDING_CONFIRM.getCode(), USER_ID));
        when(reportMapper.selectOne(any())).thenReturn(null);
        when(traceService.listByOrder(ORDER_NO)).thenReturn(List.of());

        service.handle(ORDER_NO);

        verify(reportMapper).update(any(), any());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }
}