package com.dsc.medipartner.module.order.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.order.domain.dto.PriceResult;
import com.dsc.medipartner.module.order.domain.entity.ServicePriceRule;
import com.dsc.medipartner.module.order.mapper.ServicePriceRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PriceServiceImpl 纯单测（契约 §5.1、附录 B.6、附录 H-1）。
 * 覆盖：起步价命中、加时 0.5h 进位边界、城市规则回落、未配置/未启用抛 3009、时长裁剪。
 */
class PriceServiceImplTest {

    private ServicePriceRuleMapper ruleMapper;
    private PriceServiceImpl service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(ServicePriceRuleMapper.class);
        service = new PriceServiceImpl(ruleMapper);
    }

    /** 构造定价规则：起步价含 baseHours，加时 extraHourPrice */
    private ServicePriceRule rule(String cityCode, String serviceType, String basePrice,
                                  String baseHours, String extraHourPrice) {
        ServicePriceRule r = new ServicePriceRule();
        r.setId(40001L);
        r.setCityCode(cityCode);
        r.setServiceType(serviceType);
        r.setBasePrice(new BigDecimal(basePrice));
        r.setBaseHours(new BigDecimal(baseHours));
        r.setExtraHourPrice(new BigDecimal(extraHourPrice));
        r.setMaxHours(new BigDecimal("24.0"));
        r.setMinHours(new BigDecimal("0.5"));
        r.setPriceVersion("1");
        r.setEnabled(1);
        return r;
    }

    /** 所有 selectOne 调用都返回给定规则 */
    private void stubRule(ServicePriceRule r) {
        when(ruleMapper.selectOne(any())).thenReturn(r);
    }

    @Test
    void calcHitsDefaultRuleWhenCityAbsent() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, new BigDecimal("2.0"));

        assertThat(result.getCity()).isEqualTo("DEFAULT");
        assertThat(result.getAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getExtraHours()).isEqualByComparingTo("0.0");
        assertThat(result.getServiceTypeName()).isEqualTo("全程陪诊");
        assertThat(result.getPriceVersion()).isEqualTo("1");
    }

    /** 未传 hours 时用起步时长，不计加时 */
    @Test
    void calcWithoutHoursUsesBaseHours() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, null);

        assertThat(result.getHours()).isEqualByComparingTo("4.0");
        assertThat(result.getAmount()).isEqualByComparingTo("200.00");
    }

    /** 恰好等于起步时长：extraHours = 0 */
    @Test
    void calcAtExactlyBaseHoursHasNoExtra() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, new BigDecimal("4.0"));

        assertThat(result.getExtraHours()).isEqualByComparingTo("0.0");
        assertThat(result.getAmount()).isEqualByComparingTo("200.00");
    }

    /** 4.1h -> 加时进位到 0.5h，费用 200 + 0.5*30 = 215.00 */
    @Test
    void calcRoundsExtraHoursUpToHalfHour() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, new BigDecimal("4.1"));

        assertThat(result.getExtraHours()).isEqualByComparingTo("0.5");
        assertThat(result.getAmount()).isEqualByComparingTo("215.00");
    }

    /** 4.5h 恰好 0.5 粒度，不进位 */
    @Test
    void calcKeepsExactHalfHourStep() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, new BigDecimal("4.5"));

        assertThat(result.getExtraHours()).isEqualByComparingTo("0.5");
        assertThat(result.getAmount()).isEqualByComparingTo("215.00");
    }

    /** 4.6h -> 进位到 1.0h，费用 200 + 30 = 230.00 */
    @Test
    void calcRoundsUpPastHalfHour() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, new BigDecimal("4.6"));

        assertThat(result.getExtraHours()).isEqualByComparingTo("1.0");
        assertThat(result.getAmount()).isEqualByComparingTo("230.00");
    }

    /** 一口价服务（extraHourPrice=0）：超时也不加价 */
    @Test
    void calcFlatPriceServiceNeverChargesExtra() {
        stubRule(rule("DEFAULT", "PICKUP_REPORT", "60.00", "1.0", "0.00"));

        PriceResult result = service.calc("PICKUP_REPORT", null, new BigDecimal("5.0"));

        assertThat(result.getExtraHours()).isEqualByComparingTo("4.0");
        assertThat(result.getAmount()).isEqualByComparingTo("60.00");
    }

    /** 城市专属规则优先于 DEFAULT */
    @Test
    void calcPrefersCitySpecificRule() {
        when(ruleMapper.selectOne(any()))
                .thenReturn(rule("500100", "ACCOMPANY", "260.00", "4.0", "40.00"));

        PriceResult result = service.calc("ACCOMPANY", "500100", new BigDecimal("4.0"));

        assertThat(result.getCity()).isEqualTo("500100");
        assertThat(result.getAmount()).isEqualByComparingTo("260.00");
    }

    /** 城市未命中专属规则 -> 回落 DEFAULT */
    @Test
    void calcFallsBackToDefaultWhenCityRuleMissing() {
        when(ruleMapper.selectOne(any()))
                .thenReturn(null)                                  // 城市专属查不到
                .thenReturn(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", "999999", new BigDecimal("4.0"));

        assertThat(result.getCity()).isEqualTo("DEFAULT");
        assertThat(result.getAmount()).isEqualByComparingTo("200.00");
    }

    /** 规则完全未配置 -> 3009 */
    @Test
    void calcThrows3009WhenNoRuleConfigured() {
        when(ruleMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.calc("ACCOMPANY", null, new BigDecimal("4.0")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRICE_RULE_NOT_FOUND));
    }

    /** 不支持的服务类型 -> 1001 */
    @Test
    void calcThrows1001ForUnknownServiceType() {
        assertThatThrownBy(() -> service.calc("NOT_EXIST", null, new BigDecimal("4.0")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /** 服务类型为空 -> 1001 */
    @Test
    void calcThrows1001ForBlankServiceType() {
        assertThatThrownBy(() -> service.calc("  ", null, new BigDecimal("4.0")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /** 时长超过 maxHours 时裁剪到上限（24h）：extraHours = 20h */
    @Test
    void calcClampsHoursToMax() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", null, new BigDecimal("99.0"));

        assertThat(result.getHours()).isEqualByComparingTo("24.0");
        assertThat(result.getExtraHours()).isEqualByComparingTo("20.0");
        assertThat(result.getAmount()).isEqualByComparingTo("800.00");
    }

    /** 时长为 0 或负数 -> 1001 */
    @Test
    void calcThrows1001ForNonPositiveHours() {
        stubRule(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        assertThatThrownBy(() -> service.calc("ACCOMPANY", null, BigDecimal.ZERO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /** 金额恒等于 basePrice + extraHours × extraHourPrice（计价公式防回归） */
    @Test
    void calcAmountAlwaysMatchesFormula() {
        stubRule(rule("DEFAULT", "HALF_DAY", "150.00", "3.0", "25.00"));

        PriceResult result = service.calc("HALF_DAY", null, new BigDecimal("5.2"));

        BigDecimal expected = result.getBasePrice()
                .add(result.getExtraHours().multiply(result.getExtraHourPrice()));
        assertThat(result.getAmount()).isEqualByComparingTo(expected);
        // 5.2 - 3.0 = 2.2 -> 进位 2.5；150 + 2.5*25 = 212.50
        assertThat(result.getExtraHours()).isEqualByComparingTo("2.5");
        assertThat(result.getAmount()).isEqualByComparingTo("212.50");
    }

    // ---------------- 事项 1B：开通城市后，城市专属规则真正被数据命中 ----------------

    /**
     * 广州（440100）命中专属规则，价格高于全国默认——这是 V16 开通城市后才存在的真实数据路径。
     */
    @Test
    void calcHitsGuangzhouCityRule() {
        when(ruleMapper.selectOne(any()))
                .thenReturn(rule("440100", "ACCOMPANY", "220.00", "4.0", "35.00"));

        PriceResult result = service.calc("ACCOMPANY", "440100", new BigDecimal("4.0"));

        assertThat(result.getCity()).isEqualTo("440100");
        assertThat(result.getAmount()).isEqualByComparingTo("220.00");
        // 广州加时 35/h：4h -> 5h 加价 35
        assertThat(service.calc("ACCOMPANY", "440100", new BigDecimal("5.0")).getAmount())
                .isEqualByComparingTo("255.00");
    }

    /** 深圳（440300）命中专属规则，且广州与深圳同类型价格不同，证明按 city_code 区分。 */
    @Test
    void calcDistinguishesShenzhenFromGuangzhou() {
        when(ruleMapper.selectOne(any()))
                .thenReturn(rule("440300", "ACCOMPANY", "230.00", "4.0", "35.00"));

        PriceResult shenzhen = service.calc("ACCOMPANY", "440300", new BigDecimal("4.0"));

        assertThat(shenzhen.getCity()).isEqualTo("440300");
        assertThat(shenzhen.getAmount()).isEqualByComparingTo("230.00");
    }

    /** 未开通城市回落全国默认规则（受控回落，不是报错）。 */
    @Test
    void calcFallsBackToDefaultForUnopenedCity() {
        when(ruleMapper.selectOne(any()))
                .thenReturn(null)   // 该城市无专属规则
                .thenReturn(rule("DEFAULT", "ACCOMPANY", "200.00", "4.0", "30.00"));

        PriceResult result = service.calc("ACCOMPANY", "999999", new BigDecimal("4.0"));

        assertThat(result.getCity()).isEqualTo("DEFAULT");
        assertThat(result.getAmount()).isEqualByComparingTo("200.00");
    }
}
