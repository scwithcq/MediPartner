package com.dsc.medipartner.module.demand.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.demand.domain.entity.DemandOrder;
import com.dsc.medipartner.module.demand.domain.vo.CitationVO;
import com.dsc.medipartner.module.demand.domain.vo.DemandVO;
import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.demand.domain.vo.RecommendSnapshot;
import com.dsc.medipartner.module.demand.mapper.DemandOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DemandServiceImpl 纯单测：工单状态机（OPEN/ORDERED/CLOSED）、归属校验、快照序列化与列表转换。
 */
class DemandServiceImplTest {

    private static final long USER_ID = 9L;
    private static final long DEMAND_ID = 100L;

    private DemandOrderMapper mapper;
    private ObjectMapper objectMapper;
    private DemandServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 解析列名依赖实体 TableInfo 缓存（pageMine 用到）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DemandOrder.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(DemandOrderMapper.class);
        objectMapper = new ObjectMapper();
        service = new DemandServiceImpl(mapper, objectMapper);
    }

    private DemandOrder order(long id, long userId, String status) {
        DemandOrder order = new DemandOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setStatus(status);
        order.setDemandNo("DM20260921093000123456");
        order.setSymptomDesc("最近胸口疼");
        order.setCity("重庆");
        order.setRegenerateCount(0);
        return order;
    }

    private String snapshotJson() {
        DepartmentVO dept = new DepartmentVO();
        dept.setDepartmentName("心血管内科");
        dept.setHospitalSuggest("重医附一院");
        dept.setReason("胸痛心悸优先心血管内科");
        dept.setConfidence(0.9);
        CitationVO citation = new CitationVO();
        citation.setDocId("d1");
        citation.setDocTitle("陪诊服务规范");
        citation.setSliceText("胸痛应优先前往心血管内科就诊。");
        dept.setCitations(List.of(citation));
        dept.setSuggestServiceType("ACCOMPANY");
        RecommendSnapshot snapshot = new RecommendSnapshot("qwen", true, "2026-09-21 10:00:00", List.of(dept));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void findOwnedThrows5001WhenMissing() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.findOwned(USER_ID, DEMAND_ID))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TICKET_NOT_FOUND));
    }

    @Test
    void findOwnedThrows1003WhenNotOwner() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, 2L, "OPEN"));
        assertThatThrownBy(() -> service.findOwned(USER_ID, DEMAND_ID))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void findOwnedReturnsOrderForOwner() {
        DemandOrder order = order(DEMAND_ID, USER_ID, "OPEN");
        when(mapper.selectById(DEMAND_ID)).thenReturn(order);
        assertThat(service.findOwned(USER_ID, DEMAND_ID)).isSameAs(order);
    }

    @Test
    void createOpenBuildsOpenOrderWithGeneratedNo() {
        DemandOrder created = service.createOpen(USER_ID, "最近胸口疼", "重庆", null, null);

        ArgumentCaptor<DemandOrder> captor = ArgumentCaptor.forClass(DemandOrder.class);
        verify(mapper).insert(captor.capture());
        DemandOrder inserted = captor.getValue();
        assertThat(inserted).isSameAs(created);
        assertThat(inserted.getDemandNo()).startsWith("DM").hasSize(22);
        assertThat(inserted.getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getStatus()).isEqualTo("OPEN");
        assertThat(inserted.getRegenerateCount()).isZero();
        assertThat(inserted.getSymptomDesc()).isEqualTo("最近胸口疼");
        assertThat(inserted.getCity()).isEqualTo("重庆");
    }

    @Test
    void closeIsIdempotentWhenAlreadyClosed() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, USER_ID, "CLOSED"));
        service.close(USER_ID, DEMAND_ID, "重复关闭");
        verify(mapper, never()).updateById(any(DemandOrder.class));
    }

    @Test
    void closeRejectsOrderedWith3002() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, USER_ID, "ORDERED"));
        assertThatThrownBy(() -> service.close(USER_ID, DEMAND_ID, "想关闭"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
        verify(mapper, never()).updateById(any(DemandOrder.class));
    }

    @Test
    void closeUpdatesOpenOrder() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, USER_ID, "OPEN"));
        service.close(USER_ID, DEMAND_ID, "已自行就医");

        ArgumentCaptor<DemandOrder> captor = ArgumentCaptor.forClass(DemandOrder.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(DEMAND_ID);
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(captor.getValue().getCloseReason()).isEqualTo("已自行就医");
    }

    @Test
    void markOrderedFromOpen() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, USER_ID, "OPEN"));
        service.markOrdered(DEMAND_ID, "MO20260921000000123456");

        ArgumentCaptor<DemandOrder> captor = ArgumentCaptor.forClass(DemandOrder.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ORDERED");
        assertThat(captor.getValue().getOrderNo()).isEqualTo("MO20260921000000123456");
    }

    @Test
    void markOrderedIsIdempotentWhenOrdered() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, USER_ID, "ORDERED"));
        service.markOrdered(DEMAND_ID, "MO1");
        verify(mapper, never()).updateById(any(DemandOrder.class));
    }

    @Test
    void markOrderedRejectsClosedWith3002() {
        when(mapper.selectById(DEMAND_ID)).thenReturn(order(DEMAND_ID, USER_ID, "CLOSED"));
        assertThatThrownBy(() -> service.markOrdered(DEMAND_ID, "MO1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
    }

    @Test
    void applyRecommendationSerializesSnapshot() {
        RecommendSnapshot snapshot = new RecommendSnapshot("qwen", true, "2026-09-21 10:00:00", List.of());
        service.applyRecommendation(DEMAND_ID, snapshot);

        ArgumentCaptor<DemandOrder> captor = ArgumentCaptor.forClass(DemandOrder.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(DEMAND_ID);
        assertThat(captor.getValue().getAiRecommendJson())
                .contains("\"provider\":\"qwen\"")
                .contains("\"degraded\":true");
    }

    @Test
    void incrementRegenerateAddsOne() {
        DemandOrder order = order(DEMAND_ID, USER_ID, "OPEN");
        order.setRegenerateCount(2);
        when(mapper.selectById(DEMAND_ID)).thenReturn(order);
        service.incrementRegenerate(DEMAND_ID);

        ArgumentCaptor<DemandOrder> captor = ArgumentCaptor.forClass(DemandOrder.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getRegenerateCount()).isEqualTo(3);
    }

    @Test
    void incrementRegenerateTreatsNullAsOne() {
        DemandOrder order = order(DEMAND_ID, USER_ID, "OPEN");
        order.setRegenerateCount(null);
        when(mapper.selectById(DEMAND_ID)).thenReturn(order);
        service.incrementRegenerate(DEMAND_ID);

        ArgumentCaptor<DemandOrder> captor = ArgumentCaptor.forClass(DemandOrder.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getRegenerateCount()).isEqualTo(1);
    }

    @Test
    void detailReturnsFullDepartmentsFromSnapshot() {
        DemandOrder order = order(DEMAND_ID, USER_ID, "OPEN");
        order.setAiRecommendJson(snapshotJson());
        when(mapper.selectById(DEMAND_ID)).thenReturn(order);

        DemandVO vo = service.detail(USER_ID, DEMAND_ID);

        assertThat(vo.getDemandId()).isEqualTo(String.valueOf(DEMAND_ID));
        assertThat(vo.getTopDepartment()).isEqualTo("心血管内科");
        assertThat(vo.getDepartments()).hasSize(1);
        assertThat(vo.getDepartments().get(0).getCitations()).hasSize(1);
        assertThat(vo.getDepartments().get(0).getCitations().get(0).getDocId()).isEqualTo("d1");
    }

    @Test
    void detailToleratesBrokenSnapshot() {
        DemandOrder order = order(DEMAND_ID, USER_ID, "OPEN");
        order.setAiRecommendJson("{broken json");
        when(mapper.selectById(DEMAND_ID)).thenReturn(order);

        DemandVO vo = service.detail(USER_ID, DEMAND_ID);

        assertThat(vo.getTopDepartment()).isNull();
        assertThat(vo.getDepartments()).isNull();
    }

    @Test
    void pageMineRejectsIllegalStatus() {
        assertThatThrownBy(() -> service.pageMine(USER_ID, "FOO", null, 1, 10))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    @Test
    void pageMineConvertsRecordsToListVo() {
        DemandOrder order = order(DEMAND_ID, USER_ID, "OPEN");
        order.setAiRecommendJson(snapshotJson());
        Page<DemandOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(order));
        page.setTotal(1);
        doReturn(page).when(mapper).selectPage(any(), any());

        IPage<DemandVO> result = service.pageMine(USER_ID, "OPEN", null, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        DemandVO vo = result.getRecords().get(0);
        assertThat(vo.getDemandId()).isEqualTo(String.valueOf(DEMAND_ID));
        assertThat(vo.getTopDepartment()).isEqualTo("心血管内科");
        assertThat(vo.getDepartments()).isNull();
    }
}
