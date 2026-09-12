package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateOrderRequest {

    @NotBlank(message = "症状描述不能为空")
    private String symptomDesc;

    @NotBlank(message = "就医城市不能为空")
    private String city;

    private String hospitalPref;

    @NotNull(message = "服务时间不能为空")
    private LocalDateTime serviceTime;

    @NotNull(message = "服务费不能为空")
    @DecimalMin(value = "0.01", message = "服务费必须大于0")
    private BigDecimal amount;

    private String geoHash;
    private String serviceType;
}
