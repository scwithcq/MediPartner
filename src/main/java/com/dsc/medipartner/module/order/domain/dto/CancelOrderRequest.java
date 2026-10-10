package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消订单（契约 §5.7 阶段 1.5 新增）：前端提供预设原因 + 自定义输入，可空。
 */
@Data
public class CancelOrderRequest {

    @Size(max = 100, message = "取消原因长度不能超过 100")
    private String reason;
}
