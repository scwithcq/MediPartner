package com.dsc.medipartner.module.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/worker/order")
public class WorkerOrderController {

    private final OrderService orderService;

    public WorkerOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/hall")
    public R<PageResult<ServiceOrder>> hall(PageQuery query) {
        IPage<ServiceOrder> page = orderService.pageHall(query.getPageNum(), query.getPageSize());
        return R.ok(PageResult.of(page));
    }

    @PostMapping("/{orderNo}/accept")
    public R<Void> accept(@PathVariable String orderNo) {
        orderService.accept(UserContext.userId(), orderNo);
        return R.ok();
    }

    @GetMapping("/list")
    public R<PageResult<ServiceOrder>> list(@RequestParam(required = false) Integer orderStatus, PageQuery query) {
        IPage<ServiceOrder> page = orderService.pageWorkerOrders(
                UserContext.userId(), orderStatus, query.getPageNum(), query.getPageSize());
        return R.ok(PageResult.of(page));
    }

    @PostMapping("/{orderNo}/start")
    public R<Void> start(@PathVariable String orderNo) {
        orderService.start(UserContext.userId(), orderNo);
        return R.ok();
    }

    @PostMapping("/{orderNo}/finish")
    public R<Void> finish(@PathVariable String orderNo) {
        orderService.finish(UserContext.userId(), orderNo);
        return R.ok();
    }
}
