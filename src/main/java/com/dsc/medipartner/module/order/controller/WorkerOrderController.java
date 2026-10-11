package com.dsc.medipartner.module.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.order.domain.dto.AcceptOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.MarkAbnormalRequest;
import com.dsc.medipartner.module.order.domain.dto.OrderQuery;
import com.dsc.medipartner.module.order.domain.dto.StartServiceRequest;
import com.dsc.medipartner.module.order.domain.dto.UploadTraceRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.ContactVO;
import com.dsc.medipartner.module.order.domain.vo.FinishResultVO;
import com.dsc.medipartner.module.order.domain.vo.OrderCardVO;
import com.dsc.medipartner.module.order.domain.vo.OrderDetailVO;
import com.dsc.medipartner.module.order.domain.vo.OrderTimelineVO;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * §7.6 抢单（阶段 5 加固）：距离超限 3006、服务时间不可达 3007。
     *
     * <p>返回完整详情，前端可直接进入服务页，省掉一次 §7.8 详情请求。
     */
    @PostMapping("/{orderNo}/accept")
    public R<OrderDetailVO> accept(@PathVariable String orderNo,
                                   @RequestBody(required = false) AcceptOrderRequest request) {
        return R.ok(orderService.accept(UserContext.userId(), orderNo, request));
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

    /** §7.9 陪诊师侧流转时间轴：结构与 §5.5 完全一致，只有权限方向不同 */
    @GetMapping("/{orderNo}/timeline")
    public R<OrderTimelineVO> timeline(@PathVariable String orderNo) {
        return R.ok(orderService.workerTimeline(UserContext.userId(), orderNo));
    }

    @PostMapping("/{orderNo}/start")
    public R<Void> start(@PathVariable String orderNo,
                         @Valid @RequestBody StartServiceRequest request) {
        orderService.start(UserContext.userId(), orderNo, request);
        return R.ok();
    }

    /** §7.10 联系求助者：脱敏 + 稳定虚拟号（与用户侧查到的是同一个号） */
    @GetMapping("/{orderNo}/contact")
    public R<ContactVO> contact(@PathVariable String orderNo) {
        return R.ok(orderService.workerContact(UserContext.userId(), orderNo));
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

    /** §7.15 标记订单异常：30 -> 70，资金保持冻结，自动建仲裁工单 */
    @PostMapping("/{orderNo}/abnormal")
    public R<Map<String, Object>> markAbnormal(@PathVariable String orderNo,
                                               @Valid @RequestBody MarkAbnormalRequest request) {
        orderService.markAbnormal(UserContext.userId(), orderNo, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("orderStatus", OrderState.ABNORMAL.getCode());
        result.put("markedAt", LocalDateTime.now());
        result.put("tip", "已转平台处理，预计 24 小时内给出结论");
        return R.ok(result);
    }

    /** §7.14 结束服务：落 1~3 张凭证 + 服务小结，状态 30->40，提交后异步触发 AI 报告 */
    @PostMapping("/{orderNo}/finish")
    public R<FinishResultVO> finish(@PathVariable String orderNo, @Valid @RequestBody FinishOrderRequest request) {
        return R.ok(orderService.finish(UserContext.userId(), orderNo, request));
    }
}
