package com.dsc.medipartner.module.dict.service.impl;

import com.dsc.medipartner.module.dict.domain.entity.SysConfig;
import com.dsc.medipartner.module.dict.domain.vo.ClientConfigVO;
import com.dsc.medipartner.module.dict.mapper.SysConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ClientConfigService unit tests (contract §8.3).
 *
 * <p>The contract's hard requirement is that the mini-program can <b>always</b> boot:
 * "读不到配置时返回代码内置默认值" and "绝不能因为配置接口失败而白屏". So every failure mode
 * — no rows, malformed JSON, DB exception — must still yield a complete config object.
 */
class ClientConfigServiceImplTest {

    private SysConfigMapper configMapper;
    private ClientConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        configMapper = mock(SysConfigMapper.class);
        service = new ClientConfigServiceImpl(configMapper, new ObjectMapper());
    }

    private SysConfig row(String key, String value) {
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }

    /** Even with an empty table the payload must be complete and usable. */
    @Test
    void emptyTableFallsBackToBuiltInDefaults() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        ClientConfigVO vo = service.getClientConfig();

        assertThat(vo.getPollInterval())
                .containsEntry("hall", 10).containsEntry("report", 3)
                .containsEntry("orderDetail", 15).containsEntry("workerOrderList", 15);
        assertThat(vo.getFeatureFlags()).containsKeys("aiAssist", "demandOrder", "workerApply",
                "review", "withdraw", "virtualPhone", "appendReview");
        assertThat(vo.getUploadLimit())
                .containsEntry("imageMaxMb", 10).containsEntry("pdfMaxMb", 20)
                .containsEntry("traceMaxCount", 20);
        assertThat(vo.getServicePhone()).isNotBlank();
        assertThat(vo.getAgreements()).containsKeys("user", "worker", "privacy");
        assertThat(vo.getServerTime()).isNotNull();
    }

    /** DB values win over the built-in defaults when present. */
    @Test
    void storedValuesOverrideDefaults() {
        when(configMapper.selectList(any())).thenReturn(List.of(
                row("pollInterval", "{\"hall\":30,\"report\":5,\"orderDetail\":60,\"workerOrderList\":60}"),
                row("servicePhone", "400-111-2222")));

        ClientConfigVO vo = service.getClientConfig();

        assertThat(vo.getPollInterval()).containsEntry("hall", 30);
        assertThat(vo.getServicePhone()).isEqualTo("400-111-2222");
        // untouched keys still come from defaults
        assertThat(vo.getUploadLimit()).containsEntry("imageMaxMb", 10);
    }

    /** A malformed stored value only degrades that one key, not the whole payload. */
    @Test
    void malformedStoredJsonFallsBackForThatKeyOnly() {
        when(configMapper.selectList(any())).thenReturn(List.of(
                row("pollInterval", "{not-json"),
                row("uploadLimit", "{\"imageMaxMb\":99,\"pdfMaxMb\":20,\"traceMaxCount\":20,"
                        + "\"reviewImageMaxCount\":3,\"avatarMaxMb\":5}")));

        ClientConfigVO vo = service.getClientConfig();

        assertThat(vo.getPollInterval()).containsEntry("hall", 10);
        assertThat(vo.getUploadLimit()).containsEntry("imageMaxMb", 99);
    }

    /** A DB failure must not propagate - the mini-program still gets a full config. */
    @Test
    void databaseFailureStillYieldsFullConfig() {
        when(configMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        ClientConfigVO vo = service.getClientConfig();

        assertThat(vo.getPollInterval()).isNotEmpty();
        assertThat(vo.getFeatureFlags()).isNotEmpty();
        assertThat(vo.getUploadLimit()).isNotEmpty();
        assertThat(vo.getServicePhone()).isNotBlank();
        assertThat(vo.getServerTime()).isNotNull();
    }

    /** Subscribe-message template ids are empty placeholders before phase 5. */
    @Test
    void subscribeTemplateIdsAreEmptyPlaceholders() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        ClientConfigVO vo = service.getClientConfig();

        assertThat(vo.getSubscribeTemplateIds()).containsKeys("ORDER_ACCEPTED", "SERVICE_STARTED",
                "PENDING_CONFIRM", "AUDIT_RESULT", "APPEAL_RESULT");
        assertThat(vo.getSubscribeTemplateIds().values()).allMatch(""::equals);
    }

    /** Feature flags for not-yet-shipped capabilities stay false so the client hides entry points. */
    @Test
    void notYetShippedFeaturesAreDisabled() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        ClientConfigVO vo = service.getClientConfig();

        assertThat(vo.getFeatureFlags()).containsEntry("workerApply", false);
        assertThat(vo.getFeatureFlags()).containsEntry("withdraw", false);
        assertThat(vo.getFeatureFlags()).containsEntry("aiAssist", true);
    }

    /** Blank stored values are treated as absent. */
    @Test
    void blankStoredValueFallsBackToDefault() {
        when(configMapper.selectList(any())).thenReturn(List.of(row("servicePhone", "   ")));

        assertThat(service.getClientConfig().getServicePhone()).isNotBlank();
    }
}
