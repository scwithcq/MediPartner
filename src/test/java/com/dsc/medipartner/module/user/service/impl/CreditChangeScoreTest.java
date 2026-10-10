package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.module.user.domain.entity.CreditChangeLog;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.CreditChangeType;
import com.dsc.medipartner.module.user.mapper.CreditChangeLogMapper;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CreditService 变动记录测试（事项 5B）。
 *
 * <p>信用分钳制在 0~100。契约要求 `records[].reason` 与变动可被前端直接展示，
 * 因此 {@code change_score} 必须记<b>实际差值</b>：满级时 +2 的名义分值实际加 0 分，
 * 若记 +2 会让信用明细出现「加 2 分但总分没变」的误导。
 * 但日志行本身仍要保留——「这一单触发过加分事件」是审计信息。
 */
class CreditChangeScoreTest {

    private static final long WORKER_ID = 10003L;

    private WorkerProfileMapper profileMapper;
    private CreditChangeLogMapper changeLogMapper;
    private CreditServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkerProfile.class);
        TableInfoHelper.initTableInfo(assistant, CreditChangeLog.class);
    }

    @BeforeEach
    void setUp() {
        profileMapper = mock(WorkerProfileMapper.class);
        changeLogMapper = mock(CreditChangeLogMapper.class);
        service = new CreditServiceImpl(profileMapper, changeLogMapper);
    }

    private WorkerProfile profile(int creditScore) {
        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(WORKER_ID);
        profile.setCreditScore(creditScore);
        profile.setAuditStatus("PASS");
        return profile;
    }

    private CreditChangeLog captureLog() {
        ArgumentCaptor<CreditChangeLog> captor = ArgumentCaptor.forClass(CreditChangeLog.class);
        verify(changeLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 正常加分：100 分满分时 +2 实际为 0，日志必须记 0 而不是 +2。 */
    @Test
    void changeScoreRecordsActualDeltaWhenClampedAtMax() {
        when(profileMapper.selectOne(any())).thenReturn(profile(100));
        when(profileMapper.update(any(), any())).thenReturn(1);

        service.change(WORKER_ID, CreditChangeType.ORDER_COMPLETED, "MP2026TEST", null);

        CreditChangeLog log = captureLog();
        assertThat(log.getChangeScore()).isZero();
        assertThat(log.getBeforeScore()).isEqualTo(100);
        assertThat(log.getAfterScore()).isEqualTo(100);
        // 事件痕迹保留：类型与中文原因照常落库
        assertThat(log.getChangeType()).isEqualTo("ORDER_COMPLETED");
        assertThat(log.getReason()).isEqualTo("按时完成订单");
    }

    /** 未到上限时正常累加：95 + 2 = 97，日志记 +2。 */
    @Test
    void changeScoreRecordsFullDeltaWhenNotClamped() {
        when(profileMapper.selectOne(any())).thenReturn(profile(95));
        when(profileMapper.update(any(), any())).thenReturn(1);

        service.change(WORKER_ID, CreditChangeType.ORDER_COMPLETED, "MP2026TEST", null);

        CreditChangeLog log = captureLog();
        assertThat(log.getChangeScore()).isEqualTo(2);
        assertThat(log.getBeforeScore()).isEqualTo(95);
        assertThat(log.getAfterScore()).isEqualTo(97);
    }

    /** 扣分到底：5 分时 -20 实际只扣 5，日志记 -5。 */
    @Test
    void changeScoreRecordsActualDeltaWhenClampedAtMin() {
        when(profileMapper.selectOne(any())).thenReturn(profile(5));
        when(profileMapper.update(any(), any())).thenReturn(1);

        service.change(WORKER_ID, CreditChangeType.NO_SHOW, "MP2026TEST", null);

        CreditChangeLog log = captureLog();
        assertThat(log.getChangeScore()).isEqualTo(-5);
        assertThat(log.getBeforeScore()).isEqualTo(5);
        assertThat(log.getAfterScore()).isZero();
    }

    /** 已在 0 分时再扣分，实际差值为 0，日志记 0。 */
    @Test
    void changeScoreIsZeroWhenAlreadyAtFloor() {
        when(profileMapper.selectOne(any())).thenReturn(profile(0));
        when(profileMapper.update(any(), any())).thenReturn(1);

        service.change(WORKER_ID, CreditChangeType.COMPLAINT_UPHELD, "MP2026TEST", null);

        CreditChangeLog log = captureLog();
        assertThat(log.getChangeScore()).isZero();
        assertThat(log.getAfterScore()).isZero();
    }

    /** 档案不存在时跳过，不写日志。 */
    @Test
    void missingProfileSkipsChangeWithoutLog() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        service.change(WORKER_ID, CreditChangeType.ORDER_COMPLETED, "MP2026TEST", null);

        verify(changeLogMapper, never()).insert(any(CreditChangeLog.class));
        verify(profileMapper, never()).update(any(), any());
    }

    /** creditScore 为 null（历史脏数据）时按默认 100 处理，仍不越界。 */
    @Test
    void nullCreditScoreIsTreatedAsDefaultAndStillClamped() {
        WorkerProfile legacy = new WorkerProfile();
        legacy.setUserId(WORKER_ID);
        legacy.setCreditScore(null);
        when(profileMapper.selectOne(any())).thenReturn(legacy);
        when(profileMapper.update(any(), any())).thenReturn(1);

        service.change(WORKER_ID, CreditChangeType.ORDER_COMPLETED, "MP2026TEST", null);

        CreditChangeLog log = captureLog();
        assertThat(log.getBeforeScore()).isEqualTo(100);
        assertThat(log.getAfterScore()).isEqualTo(100);
        assertThat(log.getChangeScore()).isZero();
    }
}
