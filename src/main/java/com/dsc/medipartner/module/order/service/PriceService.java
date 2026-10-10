package com.dsc.medipartner.module.order.service;

import com.dsc.medipartner.module.order.domain.dto.PriceResult;

import java.math.BigDecimal;

/**
 * 服务端计价唯一入口（契约 §5.1、附录 B.6、附录 H-1）。
 *
 * <p>铁律：订单金额只能由本服务计算，前端传入的金额一律忽略（H-1 整改核心）。
 * 计价公式全局统一为 {@code amount = basePrice + extraHours × extraHourPrice}，
 * 其中 {@code extraHours = 向上取整到 0.5h 的 max(0, hours - baseHours)}。
 */
public interface PriceService {

    /**
     * 计算服务费。
     *
     * @param serviceType 服务类型码值（ServiceType 枚举名），必填
     * @param city        城市码（国标 6 位）；命中专属规则优先，否则回落 DEFAULT；可空
     * @param hours       预计服务时长（小时）；为空时取该服务类型的 baseHours
     * @return 计价结果（含 amount 与 priceVersion 快照）
     * @throws com.dsc.medipartner.common.exception.BizException 3009 该服务类型未配置或未开放；3001/1001 参数非法
     */
    PriceResult calc(String serviceType, String city, BigDecimal hours);
}
