package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下单请求（契约 §5.2）。
 *
 * <p>⚠ 附录 H-1 整改：**金额字段已彻底删除**，订单金额只能由服务端 {@code PriceService.calc(...)} 计算。
 * 前端不再传 {@code amount}；契约标注为「废弃」的该字段即便被传入也会被 Jackson 忽略
 * （无对应属性，反序列化不报错），绝不影响落库金额。
 */
@Data
public class CreateOrderRequest {

    /** 服务类型，阶段 1.5 起必填（附录 B.6 ServiceType） */
    @NotBlank(message = "服务类型不能为空")
    private String serviceType;

    @NotBlank(message = "症状描述不能为空")
    @Size(min = 5, max = 500, message = "症状描述长度需在 5~500 字之间")
    private String symptomDesc;

    @NotBlank(message = "就医城市不能为空")
    @Size(max = 64, message = "就医城市长度不能超过 64")
    private String city;

    @Size(max = 255, message = "医院偏好长度不能超过 255")
    private String hospitalPref;

    @NotNull(message = "服务时间不能为空")
    private LocalDateTime serviceTime;

    /** 预计服务时长（小时），参与计价；不传则取该服务类型的默认起步时长 */
    private BigDecimal hours;

    @Size(max = 32, message = "GeoHash 长度不能超过 32")
    private String geoHash;

    /** 联系电话；不传则用账号绑定手机号 */
    @Pattern(regexp = "^$|^1\\d{10}$", message = "联系电话格式不正确")
    private String contactPhone;

    /** 来自 §5.1 试算的定价版本号，用于价格变更校验（不一致抛 3010） */
    private String priceVersion;

    /** 前端展示的金额；仅用于与后端重算结果比对，不一致抛 3010，**不落库** */
    private BigDecimal expectAmount;

    @Size(max = 255, message = "补充说明长度不能超过 255")
    private String remark;

    /** 关联需求工单 ID（可选）：AI 发单助手转下单时携带，用于把工单置为 ORDERED */
    private Long demandId;
}
