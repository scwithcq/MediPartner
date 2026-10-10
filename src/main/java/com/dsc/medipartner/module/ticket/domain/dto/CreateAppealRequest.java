package com.dsc.medipartner.module.ticket.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发起申诉（契约 §5.14）。
 */
@Data
public class CreateAppealRequest {

    /** 申诉类型（附录 B.7），必须是用户可发起的类型 */
    @NotBlank(message = "申诉类型不能为空")
    private String type;

    @NotBlank(message = "申诉说明不能为空")
    @Size(min = 10, max = 500, message = "申诉说明长度需在 10~500 字之间")
    private String reason;

    /** 证据图片相对路径，最多 6 张 */
    @Size(max = 6, message = "证据图片最多 6 张")
    private List<String> evidenceUrls;

    /** 期望退款金额，仅作仲裁参考，不得超过订单金额 */
    private BigDecimal expectRefundAmount;

    @Pattern(regexp = "^$|^1\\d{10}$", message = "回访电话格式不正确")
    private String contactPhone;
}
