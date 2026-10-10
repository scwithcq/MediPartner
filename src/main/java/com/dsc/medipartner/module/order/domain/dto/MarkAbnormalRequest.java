package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 标记订单异常（契约 §7.15）。
 * 异常单资金保持冻结不动（既不结算也不退款），只能由管理端处置——这是刻意的资金安全设计（D9）。
 */
@Data
public class MarkAbnormalRequest {

    /** USER_ABORT 用户中止 / HOSPITAL_ISSUE 院方原因 / SAFETY 安全风险 / OTHER 其它 */
    @NotBlank(message = "异常类型不能为空")
    private String abnormalType;

    @NotBlank(message = "异常说明不能为空")
    @Size(max = 200, message = "异常说明长度不能超过 200")
    private String reason;

    /** 现场佐证照片，最多 3 张 */
    @Size(max = 3, message = "佐证照片最多 3 张")
    private List<String> imageUrls;
}
