package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.UploadTraceRequest;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.enums.TraceType;
import com.dsc.medipartner.module.order.domain.vo.TraceVO;
import com.dsc.medipartner.module.order.mapper.OrderTraceMapper;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * TraceServiceImpl 纯单测：过程留痕闸门（归属/状态/节点/水印/上限）与结束凭证批量落库。
 */
class TraceServiceImplTest {

    private static final long WORKER_ID = 7L;
    private static final String ORDER_NO = "MP20260921000000123456";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private OrderTraceMapper traceMapper;
    private ServiceOrderMapper orderMapper;
    private TraceServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderTrace.class);
        TableInfoHelper.initTableInfo(assistant, ServiceOrder.class);
    }

    @BeforeEach
    void setUp() {
        traceMapper = mock(OrderTraceMapper.class);
        orderMapper = mock(ServiceOrderMapper.class);
        service = new TraceServiceImpl(traceMapper, orderMapper);
    }

    private ServiceOrder order(int status, long workerId) {
        ServiceOrder order = new ServiceOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setWorkerId(workerId);
        order.setOrderStatus(status);
        order.setVersion(0);
        return order;
    }

    private UploadTraceRequest uploadRequest(String node, String watermarkTime) {
        UploadTraceRequest request = new UploadTraceRequest();
        request.setImageUrl("  /files/ORDER_TRACE/a.jpg  ");
        request.setGpsLat("29.56");
        request.setGpsLng("106.55");
        request.setWatermarkTime(watermarkTime);
        request.setAddress("重医附一院");
        request.setRemark("已到院");
        request.setNode(node);
        return request;
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }

    @Test
    void uploadTraceSuccessInsertsTraceRow() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), WORKER_ID));
        when(traceMapper.selectCount(any())).thenReturn(0L);
        when(traceMapper.insert(any(OrderTrace.class))).thenAnswer(inv -> {
            OrderTrace t = inv.getArgument(0);
            t.setId(555L);
            return 1;
        });

        TraceVO vo = service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest("ARRIVED", now()));

        ArgumentCaptor<OrderTrace> captor = ArgumentCaptor.forClass(OrderTrace.class);
        verify(traceMapper).insert(captor.capture());
        OrderTrace inserted = captor.getValue();
        assertThat(inserted.getType()).isEqualTo(TraceType.TRACE.name());
        assertThat(inserted.getNode()).isEqualTo("ARRIVED");
        assertThat(inserted.getSeq()).isEqualTo(1);
        assertThat(inserted.getImageUrl()).isEqualTo("/files/ORDER_TRACE/a.jpg");
        assertThat(inserted.getWorkerId()).isEqualTo(WORKER_ID);
        assertThat(vo.getTraceId()).isEqualTo("555");
        assertThat(vo.getType()).isEqualTo("TRACE");
    }

    @Test
    void uploadTraceRejectsNonOwnerWith3008() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), 999L));
        assertThatThrownBy(() -> service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest("ARRIVED", now())))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_BELONG_TO_USER));
        verify(traceMapper, never()).insert(any(OrderTrace.class));
    }

    @Test
    void uploadTraceRejectsNonInServiceWith3002() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.ACCEPTED.getCode(), WORKER_ID));
        assertThatThrownBy(() -> service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest("ARRIVED", now())))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
    }

    @Test
    void uploadTraceRejectsBadNodeWith1001() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), WORKER_ID));
        assertThatThrownBy(() -> service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest("FOO", now())))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    @Test
    void uploadTraceRejectsBadWatermarkFormatWith1001() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), WORKER_ID));
        assertThatThrownBy(() -> service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest("ARRIVED", "not-a-date")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    @Test
    void uploadTraceRejectsSkewedWatermarkWith8004() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), WORKER_ID));
        String skewed = LocalDateTime.now().minusMinutes(10).format(TS);
        assertThatThrownBy(() -> service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest("ARRIVED", skewed)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WATERMARK_TIME_INVALID));
    }

    @Test
    void uploadTraceRejectsOverLimitWith1001() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderState.IN_SERVICE.getCode(), WORKER_ID));
        when(traceMapper.selectCount(any())).thenReturn(20L);
        assertThatThrownBy(() -> service.uploadTrace(WORKER_ID, ORDER_NO, uploadRequest(null, now())))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
        verify(traceMapper, never()).insert(any(OrderTrace.class));
    }

    @Test
    void saveVouchersInsertsOneRowPerUrl() {
        when(traceMapper.selectCount(any())).thenReturn(0L);
        when(traceMapper.insert(any(OrderTrace.class))).thenReturn(1);
        FinishOrderRequest request = new FinishOrderRequest();
        request.setVoucherUrls(List.of("/files/ORDER_VOUCHER/1.jpg", "/files/ORDER_VOUCHER/2.jpg"));
        request.setGpsLat("29.56");
        request.setGpsLng("106.55");
        request.setWatermarkTime(now());
        request.setServiceSummary("服务完成");
        request.setActualHours(new BigDecimal("2.0"));

        service.saveVouchers(WORKER_ID, ORDER_NO, request);

        ArgumentCaptor<OrderTrace> captor = ArgumentCaptor.forClass(OrderTrace.class);
        verify(traceMapper, times(2)).insert(captor.capture());
        List<OrderTrace> rows = captor.getAllValues();
        assertThat(rows).allSatisfy(t -> {
            assertThat(t.getType()).isEqualTo(TraceType.VOUCHER.name());
            assertThat(t.getNode()).isNull();
            assertThat(t.getAddress()).isNull();
        });
        assertThat(rows).extracting(OrderTrace::getImageUrl)
                .containsExactly("/files/ORDER_VOUCHER/1.jpg", "/files/ORDER_VOUCHER/2.jpg");
    }
}