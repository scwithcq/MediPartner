package com.dsc.medipartner.module.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.dto.UploadTraceRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;
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

@RestController
@RequestMapping("/api/worker/order")
public class WorkerOrderController {

    private final OrderService orderService;
    private final TraceService traceService;

    public WorkerOrderController(OrderService orderService, TraceService traceService) {
        this.orderService = orderService;
        this.traceService = traceService;
    }

    @GetMapping("/hall")
    public R<PageResult<OrderCardVO>> hall(OrderQuery query) {
        IPage<OrderCardVO> page = orderService.pageHallCards(UserContext.userId(), query);
        return R.ok(PageResult.of(page));
    }

    @PostMapping("/{orderNo}/accept")
    public R<Void> accept(@PathVariable String orderNo) {
        orderService.accept(UserContext.userId(), orderNo);
        return R.ok();
    }

    @GetMapping("/list")
    public R<PageResult<OrderCardVO>> list(OrderQuery query) {
        IPage<OrderCardVO> page = orderService.pageWorkerOrderCards(UserContext.userId(), query);
        return R.ok(PageResult.of(page));
    }

    /** §7.8 接单详情：此前完全缺失（附录 H-4 / §7.8 备注 1），本批补齐 */
    @GetMapping("/{orderNo}")
    public R<OrderDetailVO> detail(@PathVariable String orderNo) {
        return R.ok(orderService.getWorkerOrderDetail(UserContext.userId(), orderNo));
    }

    @PostMapping("/{orderNo}/start")
    public R<Void> start(@PathVariable String orderNo) {
        orderService.start(UserContext.userId(), orderNo);
        return R.ok();
    }

    /** §7.12 上传过程留痕：仅服务中订单，每单 TRACE 上限 20 条 */
    @PostMapping("/{orderNo}/trace")
    public R<TraceVO> uploadTrace(@PathVariable String orderNo, @Valid @RequestBody UploadTraceRequest request) {
        return R.ok(traceService.uploadTrace(UserContext.userId(), orderNo, request));
    }

    /** §7.13 查看本单留痕（TRACE + VOUCHER），createdAt 倒序 */
    @GetMapping("/{orderNo}/traces")
    public R<PageResult<TraceVO>> traces(@PathVariable String orderNo, PageQuery query) {
        return R.ok(PageResult.of(traceService.pageTraces(orderNo, query.getPageNum(), query.getPageSize())));
    }

    /** §7.14 结束服务：落 1~3 张凭证 + 服务小结，状态 30->40，提交后异步触发 AI 报告 */
    @PostMapping("/{orderNo}/finish")
    public R<FinishResultVO> finish(@PathVariable String orderNo, @Valid @RequestBody FinishOrderRequest request) {
        return R.ok(orderService.finish(UserContext.userId(), orderNo, request));
    }
}
