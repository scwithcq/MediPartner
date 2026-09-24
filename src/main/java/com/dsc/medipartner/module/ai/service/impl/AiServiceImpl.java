package com.dsc.medipartner.module.ai.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.ai.ChatModelFactory;
import com.dsc.medipartner.module.ai.config.AiProperties;
import com.dsc.medipartner.module.ai.domain.dto.AiAssistRequest;
import com.dsc.medipartner.module.ai.domain.dto.AssistRegenerateRequest;
import com.dsc.medipartner.module.ai.domain.vo.AiAssistVO;
import com.dsc.medipartner.module.ai.service.AiService;
import com.dsc.medipartner.module.ai.service.AssistInput;
import com.dsc.medipartner.module.ai.service.AssistPromptBuilder;
import com.dsc.medipartner.module.ai.service.DegradedAssistGenerator;
import com.dsc.medipartner.module.demand.domain.entity.DemandOrder;
import com.dsc.medipartner.module.demand.domain.enums.DemandStatus;
import com.dsc.medipartner.module.demand.domain.vo.RecommendSnapshot;
import com.dsc.medipartner.module.demand.service.DemandService;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import com.dsc.medipartner.module.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 能力实现（接口文档 §4.1/§4.2）。
 * 顺序保证：先生成推荐再建工单，模型连续失败时不会留下无快照的孤儿工单；
 * 未配置 API Key 时走 DegradedAssistGenerator（degraded=true）；
 * 模型调用失败重试 1 次，网络超时直接 9002，其余最终 6002。
 */
@Slf4j
@Service
public class AiServiceImpl implements AiService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ATTEMPTS = 2;

    private final KnowledgeService knowledgeService;
    private final DemandService demandService;
    private final ChatModelFactory chatModelFactory;
    private final AssistPromptBuilder promptBuilder;
    private final DegradedAssistGenerator degradedAssistGenerator;
    private final AiProperties props;

    public AiServiceImpl(KnowledgeService knowledgeService,
                         DemandService demandService,
                         ChatModelFactory chatModelFactory,
                         AssistPromptBuilder promptBuilder,
                         DegradedAssistGenerator degradedAssistGenerator,
                         AiProperties props) {
        this.knowledgeService = knowledgeService;
        this.demandService = demandService;
        this.chatModelFactory = chatModelFactory;
        this.promptBuilder = promptBuilder;
        this.degradedAssistGenerator = degradedAssistGenerator;
        this.props = props;
    }

    @Override
    @Transactional
    public AiAssistVO assist(Long userId, AiAssistRequest request) {
        long start = System.nanoTime();
        AssistInput input = new AssistInput(request.getSymptomDesc(), request.getCity(),
                request.getHospitalPref(), request.getServiceTime(), request.getServiceType(), null);
        List<KnowledgeSearchHit> hits = retrieveOrThrow(input.symptomDesc());
        RecommendSnapshot snapshot = generate(input, hits);
        DemandOrder order = demandService.createOpen(userId, input.symptomDesc(), input.city(),
                input.hospitalPref(), input.serviceTime());
        demandService.applyRecommendation(order.getId(), snapshot);
        return toVO(order, snapshot, elapsedMs(start));
    }

    @Override
    @Transactional
    public AiAssistVO regenerate(Long userId, AssistRegenerateRequest request) {
        long start = System.nanoTime();
        DemandOrder order = demandService.findOwned(userId, parseDemandId(request.getDemandId()));
        if (!DemandStatus.OPEN.name().equals(order.getStatus())) {
            throw new BizException(ErrorCode.DEMAND_CLOSED);
        }
        int used = order.getRegenerateCount() == null ? 0 : order.getRegenerateCount();
        if (used >= props.getMaxRegenerate()) {
            throw new BizException(ErrorCode.REPEAT_REQUEST,
                    "重新生成次数已达上限（" + props.getMaxRegenerate() + "次）");
        }
        AssistInput input = new AssistInput(order.getSymptomDesc(), order.getCity(),
                order.getHospitalPref(), order.getServiceTime(), null, request.getHint());
        List<KnowledgeSearchHit> hits = retrieveOrThrow(input.symptomDesc());
        RecommendSnapshot snapshot = generate(input, hits);
        demandService.applyRecommendation(order.getId(), snapshot);
        demandService.incrementRegenerate(order.getId());
        return toVO(order, snapshot, elapsedMs(start));
    }

    @Override
    @Async("parseExecutor")
    public void generateReport(Long orderId) {
        // 阶段 3：订单完成后汇总轨迹/费用/评价自动生成服务报告，当前仅记录任务
        log.info("[ai] 收到服务报告生成任务 orderId={}（阶段3实装）", orderId);
    }

    /** 检索为空且知识库无 ACTIVE 切片 -> 6001；检索为空但有切片（无命中）继续，Prompt 提示无资料 */
    private List<KnowledgeSearchHit> retrieveOrThrow(String query) {
        List<KnowledgeSearchHit> hits = knowledgeService.search(query, props.getTopK());
        if (hits.isEmpty() && knowledgeService.countActiveSlices() == 0) {
            throw new BizException(ErrorCode.KNOWLEDGE_NOT_PUBLISHED);
        }
        return hits;
    }

    /** 无 Key -> 降级模板；有 Key -> 调模型（最多 2 次），超时 9002 立抛，其余失败 6002 */
    private RecommendSnapshot generate(AssistInput input, List<KnowledgeSearchHit> hits) {
        String provider = chatModelFactory.providerName();
        String generatedAt = LocalDateTime.now().format(TS);
        if (!chatModelFactory.hasKey()) {
            log.info("[ai] 未配置 API Key，使用降级模板生成推荐");
            return new RecommendSnapshot(provider, true, generatedAt,
                    degradedAssistGenerator.generate(input, hits));
        }
        ChatModel model = chatModelFactory.chatModel();
        Prompt prompt = promptBuilder.build(input, hits);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ChatResponse response = model.call(prompt);
                String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                        ? null : response.getResult().getOutput().getText();
                return new RecommendSnapshot(provider, false, generatedAt, promptBuilder.parse(text, hits));
            } catch (ResourceAccessException e) {
                log.warn("[ai] 模型调用网络超时 attempt={}: {}", attempt, e.getMessage());
                throw new BizException(ErrorCode.EXTERNAL_TIMEOUT);
            } catch (Exception e) {
                last = e;
                log.warn("[ai] 模型调用/解析失败 attempt={}/{}: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        log.error("[ai] 模型调用连续失败，返回 6002", last);
        throw new BizException(ErrorCode.LLM_CALL_FAILED);
    }

    private AiAssistVO toVO(DemandOrder order, RecommendSnapshot snapshot, int elapsedMs) {
        AiAssistVO vo = new AiAssistVO();
        vo.setDemandId(String.valueOf(order.getId()));
        vo.setDemandNo(order.getDemandNo());
        vo.setStatus(order.getStatus());
        vo.setDepartments(snapshot.getDepartments());
        vo.setDisclaimer(props.getDisclaimer());
        vo.setProvider(snapshot.getProvider());
        vo.setElapsedMs(elapsedMs);
        vo.setGeneratedAt(snapshot.getGeneratedAt());
        vo.setDegraded(snapshot.getDegraded());
        return vo;
    }

    private Long parseDemandId(String demandId) {
        try {
            return Long.parseLong(demandId.trim());
        } catch (Exception e) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
    }

    private int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }
}
