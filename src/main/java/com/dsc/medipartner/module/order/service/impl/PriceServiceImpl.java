package com.dsc.medipartner.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.entity.ServicePriceRule;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.order.mapper.ServicePriceRuleMapper;
import com.dsc.medipartner.module.order.service.PriceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 服务端计价实现（契约 §5.1）。金额由本类算出，前端传入的金额不参与任何计算（H-1）。
 */
@Service
public class PriceServiceImpl implements PriceService {

    /** 全国默认规则的城市码 */
    private static final String DEFAULT_CITY_CODE = "DEFAULT";
    /** 加时计费最小粒度：0.5 小时，不足进位（附录 B.6 / §11 Q2） */
    private static final BigDecimal HOUR_STEP = new BigDecimal("0.5");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final ServicePriceRuleMapper ruleMapper;

    public PriceServiceImpl(ServicePriceRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @Override
    public PriceResult calc(String serviceType, String city, BigDecimal hours) {
        if (!StringUtils.hasText(serviceType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "服务类型不能为空");
        }
        if (!ServiceType.isValid(serviceType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的服务类型：" + serviceType);
        }

        ServicePriceRule rule = resolveRule(serviceType, city);
        BigDecimal billHours = resolveHours(rule, hours);

        // extraHours = 向上取整到 0.5h 的 max(0, hours - baseHours)
        BigDecimal rawExtra = billHours.subtract(rule.getBaseHours());
        BigDecimal extraHours = rawExtra.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO.setScale(1, RoundingMode.UNNECESSARY)
                : ceilToStep(rawExtra);

        BigDecimal extraHourPrice = rule.getExtraHourPrice() == null ? ZERO : rule.getExtraHourPrice();
        BigDecimal extraCost = extraHours.multiply(extraHourPrice);
        BigDecimal amount = rule.getBasePrice().add(extraCost).setScale(2, RoundingMode.HALF_UP);

        PriceResult result = new PriceResult();
        result.setServiceType(rule.getServiceType());
        result.setServiceTypeName(serviceTypeLabel(rule.getServiceType()));
        result.setCity(rule.getCityCode());
        result.setHours(billHours);
        result.setBasePrice(rule.getBasePrice().setScale(2, RoundingMode.HALF_UP));
        result.setExtraHours(extraHours);
        result.setExtraHourPrice(extraHourPrice.setScale(2, RoundingMode.HALF_UP));
        result.setAmount(amount);
        result.setPriceRuleId(rule.getId());
        result.setPriceVersion(rule.getPriceVersion());
        result.setRemark(rule.getRemark());
        return result;
    }

    /** 城市专属规则优先，未命中回落 DEFAULT；均未配置或未启用抛 3009 */
    private ServicePriceRule resolveRule(String serviceType, String city) {
        if (StringUtils.hasText(city)) {
            ServicePriceRule cityRule = selectEnabled(city.trim(), serviceType);
            if (cityRule != null) {
                return cityRule;
            }
        }
        ServicePriceRule defaultRule = selectEnabled(DEFAULT_CITY_CODE, serviceType);
        if (defaultRule == null) {
            throw new BizException(ErrorCode.PRICE_RULE_NOT_FOUND);
        }
        return defaultRule;
    }

    private ServicePriceRule selectEnabled(String cityCode, String serviceType) {
        return ruleMapper.selectOne(new LambdaQueryWrapper<ServicePriceRule>()
                .eq(ServicePriceRule::getCityCode, cityCode)
                .eq(ServicePriceRule::getServiceType, serviceType)
                .eq(ServicePriceRule::getEnabled, 1)
                .last("LIMIT 1"));
    }

    /** 未传时长取起步时长；传入值裁剪到 [minHours, maxHours] */
    private BigDecimal resolveHours(ServicePriceRule rule, BigDecimal hours) {
        if (hours == null) {
            return rule.getBaseHours();
        }
        if (hours.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "服务时长必须大于 0");
        }
        BigDecimal min = rule.getMinHours() == null ? HOUR_STEP : rule.getMinHours();
        BigDecimal max = rule.getMaxHours();
        if (hours.compareTo(min) < 0) {
            return min;
        }
        if (max != null && hours.compareTo(max) > 0) {
            return max;
        }
        return hours;
    }

    /** 向上取整到 0.5 的整数倍：2.1 -> 2.5；2.5 -> 2.5；2.6 -> 3.0 */
    private BigDecimal ceilToStep(BigDecimal value) {
        return value.divide(HOUR_STEP, 0, RoundingMode.CEILING)
                .multiply(HOUR_STEP)
                .setScale(1, RoundingMode.UNNECESSARY);
    }

    private String serviceTypeLabel(String serviceType) {
        try {
            return ServiceType.valueOf(serviceType).getLabel();
        } catch (IllegalArgumentException e) {
            return serviceType;
        }
    }
}
