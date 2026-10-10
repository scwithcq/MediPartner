package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.WatermarkTimes;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.UploadTraceRequest;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.enums.TraceNode;
import com.dsc.medipartner.module.order.domain.enums.TraceType;
import com.dsc.medipartner.module.order.domain.vo.TraceVO;
import com.dsc.medipartner.module.order.mapper.OrderTraceMapper;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 服务留痕实现（契约 §7.12 / §7.13 / §5.9）。
 * 直接依赖 ServiceOrderMapper 而非 OrderService：OrderServiceImpl 会注入本服务做凭证落库，
 * 走接口会构成构造器循环。水印时间统一按 yyyy-MM-dd HH:mm:ss 解析，格式错 1001、偏差超 5 分钟 8004。
 * 契约 §7.12 备注 3 的 sha256 秒传去重依赖 sys_file 表（阶段 1.5 V7），当前留痕直接引用 §8.1 返回的路径。
 */
@Slf4j
@Service
public class TraceServiceImpl implements TraceService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 每单过程留痕上限（契约 §7.12 备注 1），超出返回 1001 */
    private static final int MAX_TRACE_PER_ORDER = 20;

    private final OrderTraceMapper traceMapper;
    private final ServiceOrderMapper orderMapper;

    public TraceServiceImpl(OrderTraceMapper traceMapper, ServiceOrderMapper orderMapper) {
        this.traceMapper = traceMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public TraceVO uploadTrace(Long workerId, String orderNo, UploadTraceRequest request) {
        ServiceOrder order = requireWorkerOrder(workerId, orderNo);
        if (order.getOrderStatus() == null || order.getOrderStatus() != OrderState.IN_SERVICE.getCode()) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL, "仅服务中的订单可上传留痕");
        }
        String node = trimToNull(request.getNode());
        if (node != null && !TraceNode.isValid(node)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "node 含非法服务节点：" + request.getNode());
        }
        LocalDateTime watermarkTime = parseWatermark(request.getWatermarkTime());
        WatermarkTimes.validate(watermarkTime);

        long traceCount = traceMapper.selectCount(new LambdaQueryWrapper<OrderTrace>()
                .eq(OrderTrace::getOrderNo, orderNo)
                .eq(OrderTrace::getType, TraceType.TRACE.name()));
        if (traceCount >= MAX_TRACE_PER_ORDER) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "本单留痕已达上限（" + MAX_TRACE_PER_ORDER + "条）");
        }

        OrderTrace trace = newTrace(orderNo, workerId, TraceType.TRACE, request.getImageUrl().trim(),
                request.getGpsLat(), request.getGpsLng(), request.getAddress(), watermarkTime, node,
                trimToNull(request.getRemark()));
        traceMapper.insert(trace);
        log.info("[trace] 过程留痕已上传 orderNo={} seq={} node={}", orderNo, trace.getSeq(), node);
        return toVO(trace);
    }

    @Override
    @Transactional
    public void saveVouchers(Long workerId, String orderNo, FinishOrderRequest request) {
        LocalDateTime watermarkTime = parseWatermark(request.getWatermarkTime());
        WatermarkTimes.validate(watermarkTime);
        for (String url : request.getVoucherUrls()) {
            OrderTrace voucher = newTrace(orderNo, workerId, TraceType.VOUCHER, url.trim(),
                    request.getGpsLat(), request.getGpsLng(), null, watermarkTime, null,
                    trimToNull(request.getRemark()));
            traceMapper.insert(voucher);
        }
        log.info("[trace] 结束凭证已落库 orderNo={} count={}", orderNo, request.getVoucherUrls().size());
    }

    @Override
    public IPage<TraceVO> pageTraces(String orderNo, long pageNum, long pageSize) {
        Page<OrderTrace> page = new Page<>(pageNum, pageSize);
        IPage<OrderTrace> rows = traceMapper.selectPage(page, new LambdaQueryWrapper<OrderTrace>()
                .eq(OrderTrace::getOrderNo, orderNo)
                .orderByDesc(OrderTrace::getCreatedAt)
                .orderByDesc(OrderTrace::getId));
        Page<TraceVO> result = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        result.setRecords(rows.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    @Override
    public List<OrderTrace> listByOrder(String orderNo) {
        return traceMapper.selectList(new LambdaQueryWrapper<OrderTrace>()
                .eq(OrderTrace::getOrderNo, orderNo)
                .orderByAsc(OrderTrace::getSeq));
    }

    private OrderTrace newTrace(String orderNo, Long workerId, TraceType type, String imageUrl,
                                String gpsLat, String gpsLng, String address,
                                LocalDateTime watermarkTime, String node, String remark) {
        OrderTrace trace = new OrderTrace();
        trace.setOrderNo(orderNo);
        trace.setWorkerId(workerId);
        trace.setType(type.name());
        trace.setSeq(nextSeq(orderNo));
        trace.setImageUrl(imageUrl);
        trace.setGpsLat(trimToNull(gpsLat));
        trace.setGpsLng(trimToNull(gpsLng));
        trace.setAddress(trimToNull(address));
        trace.setWatermarkTime(watermarkTime);
        trace.setNode(node);
        trace.setRemark(remark);
        return trace;
    }

    /** 单内序号：现有条数 + 1（TRACE 与 VOUCHER 共用一条序号线） */
    private int nextSeq(String orderNo) {
        Long count = traceMapper.selectCount(new LambdaQueryWrapper<OrderTrace>()
                .eq(OrderTrace::getOrderNo, orderNo));
        return (count == null ? 0 : count.intValue()) + 1;
    }

    private ServiceOrder requireWorkerOrder(Long workerId, String orderNo) {
        ServiceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!workerId.equals(order.getWorkerId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        return order;
    }

    private LocalDateTime parseWatermark(String text) {
        try {
            return LocalDateTime.parse(text.trim(), TS);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "watermarkTime 格式须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private TraceVO toVO(OrderTrace trace) {
        TraceVO vo = new TraceVO();
        vo.setTraceId(String.valueOf(trace.getId()));
        vo.setType(trace.getType());
        vo.setImageUrl(trace.getImageUrl());
        vo.setGpsLat(trace.getGpsLat());
        vo.setGpsLng(trace.getGpsLng());
        vo.setAddress(trace.getAddress());
        vo.setWatermarkTime(trace.getWatermarkTime());
        vo.setNode(trace.getNode());
        vo.setRemark(trace.getRemark());
        vo.setCreatedAt(trace.getCreatedAt());
        return vo;
    }

    private String trimToNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }
}
