package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结束服务（契约 §7.14，阶段 3 起 voucherUrls 必填）：
 * 凭证照片先走 §8.1 上传（bizType=ORDER_VOUCHER）；serviceSummary 是 AI 报告的输入素材。
 */
@Data
public class FinishOrderRequest {

    @NotEmpty(message = "voucherUrls 不能为空")
    @Size(min = 1, max = 3, message = "voucherUrls 须为 1~3 张")
    private List<@NotBlank(message = "voucherUrls 项不能为空")
            @Size(max = 255, message = "voucherUrls 项长度不能超过 255") String> voucherUrls;

    @NotBlank(message = "gpsLat 不能为空")
    private String gpsLat;

    @NotBlank(message = "gpsLng 不能为空")
    private String gpsLng;

    @NotBlank(message = "watermarkTime 不能为空")
    private String watermarkTime;

    @Size(max = 500, message = "serviceSummary 长度不能超过 500")
    private String serviceSummary;

    /** 实际服务时长（小时），一位小数 0.5~24 */
    @DecimalMin(value = "0.5", message = "actualHours 不能小于 0.5")
    @DecimalMax(value = "24", message = "actualHours 不能大于 24")
    @Digits(integer = 2, fraction = 1, message = "actualHours 最多一位小数")
    private BigDecimal actualHours;

    @Size(max = 200, message = "remark 长度不能超过 200")
    private String remark;
}
