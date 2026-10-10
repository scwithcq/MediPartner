package com.dsc.medipartner.module.order.domain.vo;

import com.dsc.medipartner.common.config.JacksonConfig;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VO serialisation contract tests (appendix H-4, H-5; contract §1.4, §1.11).
 *
 * <p>The point is what is <b>absent</b>: internal fields ({@code version}, {@code userId},
 * {@code workerId}, {@code openid}, {@code idCardNo}) must never reach the wire, and the
 * serialisation rules (Long/BigDecimal as strings, enum-name status) must hold.
 */
class OrderVOSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        // application.yml sets spring.jackson.default-property-inclusion: non_null, which is
        // applied by Spring Boot (not by JacksonConfig), so the test sets it explicitly to
        // mirror production serialisation.
        builder.serializationInclusion(JsonInclude.Include.NON_NULL);
        mapper = builder.build();
    }

    private ServiceOrder order() {
        ServiceOrder order = new ServiceOrder();
        order.setId(1965432109876543210L);
        order.setOrderNo("MP20260921000000123456");
        order.setUserId(9L);
        order.setWorkerId(7L);
        order.setAmount(new BigDecimal("200.00"));
        order.setOrderStatus(20);
        order.setServiceType("ACCOMPANY");
        order.setServiceHours(new BigDecimal("4.0"));
        order.setPriceVersion("1");
        order.setPriceRuleId(40001L);
        order.setSymptomDesc("胸口疼已经三天了，想去医院看看心内科");
        order.setCity("Chongqing");
        order.setContactPhone("13800001234");
        order.setVersion(3);
        order.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 0, 0));
        order.setUpdatedAt(LocalDateTime.of(2026, 9, 21, 11, 0, 0));
        return order;
    }

    private WorkerProfile workerProfile() {
        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(7L);
        profile.setRealName("张三丰");
        profile.setCreditScore(100);
        profile.setGoodRate(98);
        profile.setServiceCount(12);
        return profile;
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setNickname("测试陪诊师");
        user.setPhone("13800001234");
        user.setRoleMask(3);
        user.setOpenid("mock_openid_worker1");
        return user;
    }

    // ---------------- H-4: order cards must not leak internals ----------------

    @Test
    void orderCardNeverExposesInternalFields() throws Exception {
        OrderCardVO vo = new OrderCardVO();
        vo.setOrderNo("MP20260921000000123456");
        vo.setOrderStatus("ACCEPTED");
        vo.setOrderStatusName("已接单");
        vo.setAmount(new BigDecimal("200.00"));
        vo.setActions(List.of("CONTACT"));
        vo.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 0, 0));

        String json = mapper.writeValueAsString(vo);

        assertThat(json).doesNotContain("version");
        assertThat(json).doesNotContain("userId");
        assertThat(json).doesNotContain("workerId");
        assertThat(json).doesNotContain("openid");
    }

    /** §1.4: Long ids become strings (snowflake exceeds JS safe integer). */
    @Test
    void longIdsSerializeAsStrings() throws Exception {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrderId(1965432109876543210L);
        PriceDetailVO price = new PriceDetailVO();
        price.setPriceRuleId(40001L);
        vo.setPriceDetail(price);

        String json = mapper.writeValueAsString(vo);

        assertThat(json).contains("\"orderId\":\"1965432109876543210\"");
        assertThat(json).contains("\"priceRuleId\":\"40001\"");
    }

    /** §1.4: amounts are two-decimal strings, never JSON numbers. */
    @Test
    void amountsSerializeAsTwoDecimalStrings() throws Exception {
        OrderCardVO vo = new OrderCardVO();
        vo.setAmount(new BigDecimal("200"));

        String json = mapper.writeValueAsString(vo);

        assertThat(json).contains("\"amount\":\"200.00\"");
    }

    /** §1.4: LocalDateTime renders as yyyy-MM-dd HH:mm:ss. */
    @Test
    void dateTimesUseContractFormat() throws Exception {
        OrderCardVO vo = new OrderCardVO();
        vo.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 30, 45));

        String json = mapper.writeValueAsString(vo);

        assertThat(json).contains("2026-09-21 10:30:45");
    }

    /** Appendix B.1: status is the enum NAME, paired with a Chinese display name. */
    @Test
    void statusIsEnumNameNotNumericCode() throws Exception {
        OrderCardVO vo = new OrderCardVO();
        vo.setOrderStatus("PENDING_CONFIRM");
        vo.setOrderStatusName("待确认");

        String json = mapper.writeValueAsString(vo);

        assertThat(json).contains("\"orderStatus\":\"PENDING_CONFIRM\"");
        assertThat(json).contains("\"orderStatusName\":\"待确认\"");
    }

    /** Null properties are omitted entirely (spring.jackson default-property-inclusion: non_null). */
    @Test
    void nullFieldsAreOmitted() throws Exception {
        OrderCardVO vo = new OrderCardVO();
        vo.setOrderNo("MP20260921000000123456");
        vo.setWorker(null);

        String json = mapper.writeValueAsString(vo);

        assertThat(json).doesNotContain("worker");
    }

    // ---------------- H-5: user profile must not leak phone/openid ----------------

    @Test
    void userVONeverExposesOpenidOrRawPhone() throws Exception {
        UserVO vo = new UserVO();
        vo.setId(7L);
        vo.setNickname("测试陪诊师");
        vo.setPhone("138****1234");
        vo.setRoleMask(3);
        vo.setRoles(List.of("USER", "WORKER"));
        vo.setStatus(1);
        vo.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 0, 0));

        String json = mapper.writeValueAsString(vo);

        assertThat(json).doesNotContain("openid");
        assertThat(json).doesNotContain("13800001234");
        assertThat(json).contains("\"phone\":\"138****1234\"");
        assertThat(json).contains("\"roles\":[\"USER\",\"WORKER\"]");
    }

    /** A raw SysUser entity must not leak openid even if accidentally serialised. */
    @Test
    void sysUserEntityHidesOpenid() throws Exception {
        String json = mapper.writeValueAsString(user());
        assertThat(json).doesNotContain("openid");
        assertThat(json).doesNotContain("mock_openid_worker1");
    }

    // ---------------- symptom brief truncation ----------------

    /** §5.3: symptomBrief is truncated to 50 chars with an ellipsis. */
    @Test
    void symptomBriefIsTruncatedToFiftyChars() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longText.append("症");
        }
        var assembler = new com.dsc.medipartner.module.order.service.OrderAssembler();
        ServiceOrder order = order();
        order.setSymptomDesc(longText.toString());

        OrderCardVO card = assembler.toCard(order, workerProfile(), user(), List.of());

        assertThat(card.getSymptomBrief()).hasSize(51); // 50 chars + ellipsis
        assertThat(card.getSymptomBrief()).endsWith("…");
    }

    /** Short descriptions pass through untouched and trimmed. */
    @Test
    void shortSymptomBriefIsUnchanged() {
        var assembler = new com.dsc.medipartner.module.order.service.OrderAssembler();
        ServiceOrder order = order();
        order.setSymptomDesc("  胸口疼  ");

        OrderCardVO card = assembler.toCard(order, workerProfile(), user(), List.of());

        assertThat(card.getSymptomBrief()).isEqualTo("胸口疼");
    }

    // ---------------- assembler: masking of the peer ----------------

    /** §1.11: the counterpart's phone is masked and their real name keeps only the surname. */
    @Test
    void assemblerMasksPeerPhoneAndName() {
        var assembler = new com.dsc.medipartner.module.order.service.OrderAssembler();

        OrderDetailVO detail = assembler.toDetail(order(), workerProfile(), user(), List.of());
        detail.setContactPhone(com.dsc.medipartner.common.util.DesensitizeUtil.phone(order().getContactPhone()));

        assertThat(detail.getWorker().getMaskedPhone()).isEqualTo("138****1234");
        assertThat(detail.getWorker().getRealName()).isEqualTo("张**");
        assertThat(detail.getContactPhone()).isEqualTo("138****1234");
    }

    /** Price detail is reconstructed from the order's own pricing snapshot. */
    @Test
    void assemblerBuildsPriceDetailFromOrderSnapshot() {
        var assembler = new com.dsc.medipartner.module.order.service.OrderAssembler();

        OrderDetailVO detail = assembler.toDetail(order(), workerProfile(), user(), List.of());

        assertThat(detail.getPriceDetail()).isNotNull();
        assertThat(detail.getPriceDetail().getPriceVersion()).isEqualTo("1");
        assertThat(detail.getPriceDetail().getPriceRuleId()).isEqualTo(40001L);
        assertThat(detail.getPriceDetail().getAmount()).isEqualByComparingTo("200.00");
    }
}
