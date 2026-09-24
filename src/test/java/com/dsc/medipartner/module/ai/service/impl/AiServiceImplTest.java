package com.dsc.medipartner.module.ai.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.ai.ChatModelFactory;
import com.dsc.medipartner.module.ai.config.AiProperties;
import com.dsc.medipartner.module.ai.domain.dto.AiAssistRequest;
import com.dsc.medipartner.module.ai.domain.dto.AssistRegenerateRequest;
import com.dsc.medipartner.module.ai.domain.vo.AiAssistVO;
import com.dsc.medipartner.module.ai.service.AssistPromptBuilder;
import com.dsc.medipartner.module.ai.service.DegradedAssistGenerator;
import com.dsc.medipartner.module.demand.domain.entity.DemandOrder;
import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.demand.domain.vo.RecommendSnapshot;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import com.dsc.medipartner.module.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiServiceImpl 纯单测：降级模板、6001/6002/9002 错误码、重试与重新生成守卫。
 * 不启动 Spring 上下文，模型与依赖全部 mock。
 */
class AiServiceImplTest {

    private static final long USER_ID = 9L;
    private static final long DEMAND_ID = 100L;

    private static final String VALID_JSON = """
            {"departments":[
              {"departmentName":"心血管内科","hospitalSuggest":"重医附一院","reason":"胸痛心悸优先排查心源性疾病","confidence":0.9,"citationIndexes":[1],"suggestServiceType":"ACCOMPANY"},
              {"departmentName":"呼吸内科","hospitalSuggest":"重医附一院","reason":"气短需排除呼吸系统问题","confidence":0.72,"citationIndexes":[],"suggestServiceType":"ACCOMPANY"},
              {"departmentName":"全科医学科","hospitalSuggest":"重医附一院","reason":"多症状可先全科分诊","confidence":0.55,"citationIndexes":[],"suggestServiceType":"ACCOMPANY"}
            ]}""";

    private final List<KnowledgeSearchHit> hits = List.of(
            new KnowledgeSearchHit("s1", "d1", "陪诊服务规范", "胸痛应优先前往心血管内科就诊。", 0.9));

    private KnowledgeService knowledgeService;
    private DemandService demandService;
    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private AiServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeService = mock(KnowledgeService.class);
        demandService = mock(DemandService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        service = new AiServiceImpl(knowledgeService, demandService, chatModelFactory,
                new AssistPromptBuilder(), new DegradedAssistGenerator(), new AiProperties());
        when(chatModelFactory.providerName()).thenReturn("qwen");
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(hits);
        when(demandService.createOpen(eq(USER_ID), any(), any(), any(), any())).thenReturn(openOrder());
    }

    private DemandOrder openOrder() {
        DemandOrder order = new DemandOrder();
        order.setId(DEMAND_ID);
        order.setDemandNo("DM20260921093000123456");
        order.setUserId(USER_ID);
        order.setSymptomDesc("最近胸口疼，有时候心悸气短");
        order.setCity("重庆");
        order.setStatus("OPEN");
        order.setRegenerateCount(0);
        return order;
    }

    private AiAssistRequest assistRequest() {
        AiAssistRequest request = new AiAssistRequest();
        request.setSymptomDesc("最近胸口疼，有时候心悸气短");
        request.setCity("重庆");
        return request;
    }

    private ChatResponse chatResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(VALID_JSON))));
    }

    @Test
    void assistDegradedWithoutApiKey() {
        when(chatModelFactory.hasKey()).thenReturn(false);

        AiAssistVO vo = service.assist(USER_ID, assistRequest());

        assertThat(vo.getDegraded()).isTrue();
        assertThat(vo.getProvider()).isEqualTo("qwen");
        assertThat(vo.getDemandId()).isEqualTo(String.valueOf(DEMAND_ID));
        assertThat(vo.getDemandNo()).startsWith("DM");
        assertThat(vo.getStatus()).isEqualTo("OPEN");
        assertThat(vo.getDepartments()).hasSize(3);
        assertThat(vo.getDepartments().get(0).getDepartmentName()).isEqualTo("心血管内科");
        assertThat(vo.getDepartments().get(0).getCitations()).hasSize(1);
        assertThat(vo.getDepartments().get(0).getCitations().get(0).getDocId()).isEqualTo("d1");
        assertThat(vo.getDisclaimer()).contains("AI 生成");
        assertThat(vo.getElapsedMs()).isNotNull();
        assertThat(vo.getElapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(vo.getGeneratedAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

        ArgumentCaptor<RecommendSnapshot> captor = ArgumentCaptor.forClass(RecommendSnapshot.class);
        verify(demandService).applyRecommendation(eq(DEMAND_ID), captor.capture());
        assertThat(captor.getValue().getDegraded()).isTrue();
        assertThat(captor.getValue().getProvider()).isEqualTo("qwen");
        verify(chatModelFactory, never()).chatModel();
    }

    @Test
    void assistThrows6001WhenKnowledgeNotPublished() {
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeService.countActiveSlices()).thenReturn(0L);

        assertThatThrownBy(() -> service.assist(USER_ID, assistRequest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KNOWLEDGE_NOT_PUBLISHED));
        verify(demandService, never()).createOpen(any(), any(), any(), any(), any());
    }

    @Test
    void assistProceedsWithEmptyHitsWhenSlicesExist() {
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeService.countActiveSlices()).thenReturn(12L);
        when(chatModelFactory.hasKey()).thenReturn(false);
        AiAssistRequest request = new AiAssistRequest();
        request.setSymptomDesc("想去医院做个检查");
        request.setCity("重庆");

        AiAssistVO vo = service.assist(USER_ID, request);

        assertThat(vo.getDegraded()).isTrue();
        assertThat(vo.getDepartments()).hasSize(3);
        assertThat(vo.getDepartments()).allSatisfy(d -> assertThat(d.getCitations()).isEmpty());
    }

    @Test
    void assistWithModelSuccess() {
        when(chatModelFactory.hasKey()).thenReturn(true);
        when(chatModelFactory.chatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse());

        AiAssistVO vo = service.assist(USER_ID, assistRequest());

        assertThat(vo.getDegraded()).isFalse();
        assertThat(vo.getProvider()).isEqualTo("qwen");
        assertThat(vo.getDepartments()).extracting(DepartmentVO::getDepartmentName)
                .containsExactly("心血管内科", "呼吸内科", "全科医学科");
        assertThat(vo.getDepartments().get(0).getConfidence()).isEqualTo(0.9);
        assertThat(vo.getDepartments().get(0).getCitations()).hasSize(1);
        assertThat(vo.getDepartments().get(0).getCitations().get(0).getDocId()).isEqualTo("d1");
        assertThat(vo.getDepartments().get(1).getCitations()).isEmpty();
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(demandService).createOpen(eq(USER_ID), any(), any(), any(), any());
    }

    @Test
    void assistRetriesOnceThenSucceeds() {
        when(chatModelFactory.hasKey()).thenReturn(true);
        when(chatModelFactory.chatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("输出格式错误"))
                .thenReturn(chatResponse());

        AiAssistVO vo = service.assist(USER_ID, assistRequest());

        assertThat(vo.getDegraded()).isFalse();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void assistThrows6002AfterTwoFailures() {
        when(chatModelFactory.hasKey()).thenReturn(true);
        when(chatModelFactory.chatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("第一次失败"))
                .thenThrow(new IllegalStateException("第二次失败"));

        assertThatThrownBy(() -> service.assist(USER_ID, assistRequest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CALL_FAILED));
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(demandService, never()).createOpen(any(), any(), any(), any(), any());
    }

    @Test
    void assistThrows9002OnTimeoutWithoutRetry() {
        when(chatModelFactory.hasKey()).thenReturn(true);
        when(chatModelFactory.chatModel()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.assist(USER_ID, assistRequest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_TIMEOUT));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void regenerateHappyPathDegraded() {
        when(chatModelFactory.hasKey()).thenReturn(false);
        when(demandService.findOwned(USER_ID, DEMAND_ID)).thenReturn(openOrder());
        AssistRegenerateRequest request = new AssistRegenerateRequest();
        request.setDemandId(String.valueOf(DEMAND_ID));
        request.setHint("希望安排女陪诊师");

        AiAssistVO vo = service.regenerate(USER_ID, request);

        assertThat(vo.getDegraded()).isTrue();
        assertThat(vo.getStatus()).isEqualTo("OPEN");
        assertThat(vo.getDepartments()).hasSize(3);
        verify(demandService).applyRecommendation(eq(DEMAND_ID), any(RecommendSnapshot.class));
        verify(demandService).incrementRegenerate(DEMAND_ID);
        verify(demandService, never()).createOpen(any(), any(), any(), any(), any());
    }

    @Test
    void regenerateRejectsNonOpenWith6005() {
        DemandOrder closed = openOrder();
        closed.setStatus("CLOSED");
        when(demandService.findOwned(USER_ID, DEMAND_ID)).thenReturn(closed);
        AssistRegenerateRequest request = new AssistRegenerateRequest();
        request.setDemandId(String.valueOf(DEMAND_ID));

        assertThatThrownBy(() -> service.regenerate(USER_ID, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DEMAND_CLOSED));
        verify(demandService, never()).applyRecommendation(any(), any());
    }

    @Test
    void regenerateRejectsOverLimitWith1005() {
        DemandOrder order = openOrder();
        order.setRegenerateCount(5);
        when(demandService.findOwned(USER_ID, DEMAND_ID)).thenReturn(order);
        AssistRegenerateRequest request = new AssistRegenerateRequest();
        request.setDemandId(String.valueOf(DEMAND_ID));

        assertThatThrownBy(() -> service.regenerate(USER_ID, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REPEAT_REQUEST));
        verify(demandService, never()).applyRecommendation(any(), any());
    }

    @Test
    void regenerateWithBadIdThrows5001() {
        AssistRegenerateRequest request = new AssistRegenerateRequest();
        request.setDemandId("not-a-number");

        assertThatThrownBy(() -> service.regenerate(USER_ID, request))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TICKET_NOT_FOUND));
        verify(demandService, never()).findOwned(any(), any());
    }
}
