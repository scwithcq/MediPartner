package com.dsc.medipartner.module.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.ai.domain.vo.ReportVO;
import com.dsc.medipartner.module.ai.service.ReportService;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.TraceVO;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.TraceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;
    private final TraceService traceService;
    private final ReportService reportService;

    public OrderController(OrderService orderService, TraceService traceService, ReportService reportService) {
        this.orderService = orderService;
        this.traceService = traceService;
        this.reportService = reportService;
    }

    @PostMapping
    public R<Map<String, String>> create(@Valid @RequestBody CreateOrderRequest request) {
        String orderNo = orderService.create(request);
        return R.ok(Map.of("orderNo", orderNo));
    }

    @GetMapping("/{orderNo}")
    public R<ServiceOrder> detail(@PathVariable String orderNo) {
        return R.ok(orderService.getByOrderNo(orderNo));
    }

    @GetMapping("/list")
    public R<PageResult<ServiceOrder>> list(OrderQuery query) {
        IPage<ServiceOrder> page = orderService.pageUserOrders(UserContext.userId(), query);
        return R.ok(PageResult.of(page));
    }

    @PostMapping("/{orderNo}/cancel")
    public R<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(UserContext.userId(), orderNo);
        return R.ok();
    }

    @PostMapping("/{orderNo}/confirm")
    public R<Void> confirm(@PathVariable String orderNo) {
        orderService.confirm(UserContext.userId(), orderNo);
        return R.ok();
    }

    /** §5.9 查看本单服务留痕（TRACE + VOUCHER），createdAt 倒序；归属校验后放行 */
    @GetMapping("/{orderNo}/traces")
    public R<PageResult<TraceVO>> traces(@PathVariable String orderNo, PageQuery query) {
        requireOwned(orderNo);
        return R.ok(PageResult.of(traceService.pageTraces(orderNo, query.getPageNum(), query.getPageSize())));
    }

    /** §5.10 轮询 AI 陪诊报告：PROCESSING/DONE/FAILED，归属与状态校验在服务内完成 */
    @GetMapping("/{orderNo}/report")
    public R<ReportVO> report(@PathVariable String orderNo) {
        return R.ok(reportService.getReport(UserContext.userId(), orderNo));
    }

    private void requireOwned(String orderNo) {
        ServiceOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!UserContext.userId().equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
    }
}
