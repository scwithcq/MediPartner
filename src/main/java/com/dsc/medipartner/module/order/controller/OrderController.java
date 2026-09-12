package com.dsc.medipartner.module.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.order.domain.dto.CreateOrderRequest;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.service.OrderService;
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

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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
    public R<PageResult<ServiceOrder>> list(@RequestParam(required = false) Integer orderStatus, PageQuery query) {
        IPage<ServiceOrder> page = orderService.pageUserOrders(
                UserContext.userId(), orderStatus, query.getPageNum(), query.getPageSize());
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
}
