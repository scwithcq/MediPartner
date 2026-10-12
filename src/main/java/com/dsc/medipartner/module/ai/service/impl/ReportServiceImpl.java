package com.dsc.medipartner.module.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.mq.ReportGenerateHandler;
import com.dsc.medipartner.infra.mq.ReportTaskPublisher;
import com.dsc.medipartner.module.ai.ChatModelFactory;
import com.dsc.medipartner.module.ai.config.AiProperties;
import com.dsc.medipartner.module.ai.domain.entity.AiReport;
import com.dsc.medipartner.module.ai.domain.enums.ReportStatus;
import com.dsc.medipartner.module.ai.domain.vo.ReportVO;
import com.dsc.medipartner.module.ai.mapper.AiReportMapper;
import com.dsc.medipartner.module.ai.service.DegradedReportGenerator;
import com.dsc.medipartner.module.ai.service.ReportInput;
import com.dsc.medipartner.module.ai.service.ReportPromptBuilder;
import com.dsc.medipartner.module.ai.service.ReportService;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import com.dsc.medipartner.module.order.domain.enums.OrderState;
import com.dsc.medipartner.module.order.domain.vo.ReportBriefVO;
import com.dsc.medipartner.module.order.mapper.ServiceOrderMapper;
import com.dsc.medipartner.module.order.service.TraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 陪诊报告实现（契约 §5.10 读取 + §7.14 结束后异步生成）。
 * 同时实现 ReportService（用户轮询）与 ReportGenerateHandler（MQ 消费入口）。
 * 关键口径：
 * 1. 直接依赖 ServiceOrderMapper 而非 OrderService——OrderServiceImpl 会注入 ReportTaskPublisher，
 *    走接口会构成构造器循环（与 TraceServiceImpl 同一处理）；
 * 2. handle() 不加 @Transactional：生成含慢速模型调用，逐条 mapper 操作各自 autocommit，避免长事务；
 * 3. visitInfo 在读取时由订单实时派生（hospital=order.hospitalPref），不落 ai_report 表；
 * 4. 失败重试上限 3 次，由 getReport 在 FAILED 且 retry_count<3 时重投，handle 每次失败累加 retry_count。
 */
@Slf4j
@Service
public class ReportServiceImpl implements ReportService, ReportGenerateHandler {

    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_RETRY = 3;
    private static final String FAIL_REASON = "AI 服务暂时不可用，报告生成失败，请稍后重试";
    private static final TypeReference<List<ReportVO.ReportSection>> SECTION_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> ADVICE_TYPE = new TypeReference<>() {
    };

    private final AiReportMapper reportMapper;
    private final ServiceOrderMapper orderMapper;
    private final TraceService traceService;
    private final ChatModelFactory chatModelFactory;
    private final ReportPromptBuilder promptBuilder;
    private final DegradedReportGenerator degradedReportGenerator;
    private final ReportTaskPublisher reportTaskPublisher;
    private final ObjectMapper objectMapper;
    private final AiProperties props;

    public ReportServiceImpl(AiReportMapper reportMapper, ServiceOrderMapper orderMapper, TraceService traceService,
                             ChatModelFactory chatModelFactory, ReportPromptBuilder promptBuilder,
                             DegradedReportGenerator degradedReportGenerator, ReportTaskPublisher reportTaskPublisher,
                             ObjectMapper objectMapper, AiProperties props) {
        this.reportMapper = reportMapper;
        this.orderMapper = orderMapper;
        this.traceService = traceService;
        this.chatModelFactory = chatModelFactory;
        this.promptBuilder = promptBuilder;
        this.degradedReportGenerator = degradedReportGenerator;
        this.reportTaskPublisher = reportTaskPublisher;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public ReportVO getReport(Long userId, String orderNo) {
        ServiceOrder order = requireOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }
        if (order.getOrderStatus() == null || order.getOrderStatus() < OrderState.PENDING_CONFIRM.getCode()) {
            throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL, "服务尚未结束，暂无报告");
        }

        AiReport report = findByOrderNo(orderNo);
        if (report == null) {
            // 首次访问且结束服务时未建行（兜底）：建 PROCESSING 并投递生成任务
            report = createProcessing(orderNo);
            reportTaskPublisher.publishReportTask(orderNo);
            return processingVO(orderNo, order);
        }

        ReportStatus status = parseStatus(report.getStatus());
        switch (status) {
            case DONE:
                return toDoneVO(report, order);
            case FAILED:
                int retry = report.getRetryCount() == null ? 0 : report.getRetryCount();
                if (retry < MAX_RETRY) {
                    // 重投前先翻回 PROCESSING，避免用户轮询期间重复投递
                    reportMapper.update(null, new LambdaUpdateWrapper<AiReport>()
                            .eq(AiReport::getId, report.getId())
                            .set(AiReport::getStatus, ReportStatus.PROCESSING.name()));
                    reportTaskPublisher.publishReportTask(orderNo);
                    return processingVO(orderNo, order);
                }
                return toFailedVO(report, order);
            case PROCESSING:
            default:
                return processingVO(orderNo, order);
        }
    }

    @Override
    public void handle(String orderNo) {
        ServiceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderNo, orderNo));
        if (order == null) {
            log.warn("[report] 订单不存在，跳过报告生成 orderNo={}", orderNo);
            return;
        }
        AiReport report = findOrCreateProcessing(orderNo);
        ReportInput input = buildInput(order);
        try {
            ReportPromptBuilder.ReportDraft draft = generate(input);
            markDone(report, draft, input);
            log.info("[report] 服务报告生成完成 orderNo={} degraded={}", orderNo,
                    chatModelFactory.hasKey() ? 0 : 1);
        } catch (Exception e) {
            markFailed(report);
            log.error("[report] 服务报告生成失败 orderNo={} retryCount={}", orderNo,
                    report.getRetryCount(), e);
        }
    }

    /** 无 Key 走降级模板；有 Key 调模型（最多 2 次），任意异常都向上抛由 handle 落 FAILED */
    private ReportPromptBuilder.ReportDraft generate(ReportInput input) {
        if (!chatModelFactory.hasKey()) {
            log.info("[report] 未配置 API Key，使用降级模板生成报告 orderNo={}", input.orderNo());
            return degradedReportGenerator.generate(input);
        }
        ChatModel model = chatModelFactory.chatModel();
        Prompt prompt = promptBuilder.build(input);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ChatResponse response = model.call(prompt);
                String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                        ? null : response.getResult().getOutput().getText();
                return promptBuilder.parse(text);
            } catch (Exception e) {
                last = e;
                log.warn("[report] 模型调用/解析失败 attempt={}/{} orderNo={}: {}",
                        attempt, MAX_ATTEMPTS, input.orderNo(), e.getMessage());
            }
        }
        throw new IllegalStateException("模型连续 " + MAX_ATTEMPTS + " 次生成失败", last);
    }

    private void markDone(AiReport report, ReportPromptBuilder.ReportDraft draft, ReportInput input) {
        boolean wasDone = ReportStatus.DONE.name().equals(report.getStatus());
        int version = (report.getVersion() == null ? 1 : report.getVersion()) + (wasDone ? 1 : 0);
        reportMapper.update(null, new LambdaUpdateWrapper<AiReport>()
                .eq(AiReport::getId, report.getId())
                .set(AiReport::getStatus, ReportStatus.DONE.name())
                .set(AiReport::getTitle, draft.title())
                .set(AiReport::getSummary, draft.summary())
                .set(AiReport::getSections, writeJson(draft.sections()))
                .set(AiReport::getAdvice, writeJson(draft.advice()))
                .set(AiReport::getProvider, chatModelFactory.providerName())
                .set(AiReport::getDegraded, chatModelFactory.hasKey() ? 0 : 1)
                .set(AiReport::getFailReason, null)
                .set(AiReport::getGeneratedAt, LocalDateTime.now())
                .set(AiReport::getVersion, version));
        report.setStatus(ReportStatus.DONE.name());
        report.setTitle(draft.title());
        report.setSummary(draft.summary());
        report.setSections(writeJson(draft.sections()));
        report.setAdvice(writeJson(draft.advice()));
        report.setProvider(chatModelFactory.providerName());
        report.setDegraded(chatModelFactory.hasKey() ? 0 : 1);
        report.setGeneratedAt(LocalDateTime.now());
        report.setVersion(version);
    }

    private void markFailed(AiReport report) {
        int retry = (report.getRetryCount() == null ? 0 : report.getRetryCount()) + 1;
        reportMapper.update(null, new LambdaUpdateWrapper<AiReport>()
                .eq(AiReport::getId, report.getId())
                .set(AiReport::getStatus, ReportStatus.FAILED.name())
                .set(AiReport::getProvider, chatModelFactory.providerName())
                .set(AiReport::getFailReason, FAIL_REASON)
                .set(AiReport::getRetryCount, retry));
        report.setStatus(ReportStatus.FAILED.name());
        report.setRetryCount(retry);
        report.setFailReason(FAIL_REASON);
    }

    private AiReport findOrCreateProcessing(String orderNo) {
        AiReport report = findByOrderNo(orderNo);
        if (report != null) {
            return report;
        }
        try {
            return createProcessing(orderNo);
        } catch (DuplicateKeyException e) {
            // 并发下另一线程已建行，回读即可
            AiReport existing = findByOrderNo(orderNo);
            if (existing == null) {
                throw e;
            }
            return existing;
        }
    }

    private AiReport createProcessing(String orderNo) {
        AiReport report = new AiReport();
        report.setOrderNo(orderNo);
        report.setStatus(ReportStatus.PROCESSING.name());
        report.setDegraded(0);
        report.setVersion(1);
        report.setRetryCount(0);
        reportMapper.insert(report);
        return report;
    }

    private AiReport findByOrderNo(String orderNo) {
        return reportMapper.selectOne(new LambdaQueryWrapper<AiReport>()
                .eq(AiReport::getOrderNo, orderNo)
                .last("LIMIT 1"));
    }

    /**
     * 只读查询报告状态摘要（阶段 6，供 §5.4 订单详情使用）。
     *
     * <p><b>严格只读</b>：不建行、不投递任务、不改状态。
     * 订单详情是被高频轮询的接口，任何写副作用都会造成
     * 「每次打开详情都触发一次 AI 生成」的资损与状态污染。
     */
    @Override
    public ReportBriefVO getReportBrief(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            return null;
        }
        AiReport report = findByOrderNo(orderNo);
        if (report == null) {
            // 从未生成过：返回 null，调用方据此不下发 report 键（契约 §5.4）
            return null;
        }
        ReportBriefVO brief = new ReportBriefVO();
        brief.setStatus(report.getStatus());
        brief.setGeneratedAt(report.getGeneratedAt());
        return brief;
    }

    private ReportInput buildInput(ServiceOrder order) {
        List<OrderTrace> traces = traceService.listByOrder(order.getOrderNo());
        List<ReportInput.TraceLine> lines = new ArrayList<>(traces.size());
        for (OrderTrace trace : traces) {
            lines.add(new ReportInput.TraceLine(trace.getType(), trace.getNode(), trace.getWatermarkTime(),
                    trace.getAddress(), trace.getRemark()));
        }
        return new ReportInput(order.getOrderNo(), order.getSymptomDesc(), order.getCity(),
                order.getHospitalPref(), order.getServiceType(), order.getServiceTime(),
                order.getServiceSummary(), order.getActualHours(), order.getAmount(), lines);
    }

    private ReportVO processingVO(String orderNo, ServiceOrder order) {
        ReportVO vo = baseVO(orderNo, order);
        vo.setStatus(ReportStatus.PROCESSING.name());
        return vo;
    }

    private ReportVO toFailedVO(AiReport report, ServiceOrder order) {
        ReportVO vo = baseVO(order.getOrderNo(), order);
        vo.setStatus(ReportStatus.FAILED.name());
        vo.setProvider(report.getProvider());
        vo.setDegraded(isDegraded(report.getDegraded()));
        vo.setFailReason(StringUtils.hasText(report.getFailReason()) ? report.getFailReason() : FAIL_REASON);
        return vo;
    }

    private ReportVO toDoneVO(AiReport report, ServiceOrder order) {
        ReportVO vo = baseVO(order.getOrderNo(), order);
        vo.setStatus(ReportStatus.DONE.name());
        vo.setTitle(report.getTitle());
        vo.setSummary(report.getSummary());
        vo.setSections(readSections(report.getSections()));
        vo.setAdvice(readAdvice(report.getAdvice()));
        vo.setProvider(report.getProvider());
        vo.setDegraded(isDegraded(report.getDegraded()));
        vo.setGeneratedAt(report.getGeneratedAt());
        return vo;
    }

    /** 所有状态共有的字段：orderNo / visitInfo（读取时派生）/ disclaimer */
    private ReportVO baseVO(String orderNo, ServiceOrder order) {
        ReportVO vo = new ReportVO();
        vo.setOrderNo(orderNo);
        vo.setVisitInfo(new ReportVO.VisitInfo(order.getHospitalPref()));
        vo.setDisclaimer(props.getDisclaimer());
        return vo;
    }

    private boolean isDegraded(Integer degraded) {
        return degraded != null && degraded == 1;
    }

    private ReportStatus parseStatus(String status) {
        return ReportStatus.isValid(status) ? ReportStatus.valueOf(status) : ReportStatus.PROCESSING;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            log.warn("[report] 报告字段序列化失败，置空: {}", e.getMessage());
            return "[]";
        }
    }

    private List<ReportVO.ReportSection> readSections(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, SECTION_TYPE);
        } catch (Exception e) {
            log.warn("[report] sections 反序列化失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> readAdvice(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ADVICE_TYPE);
        } catch (Exception e) {
            log.warn("[report] advice 反序列化失败: {}", e.getMessage());
            return List.of();
        }
    }

    private ServiceOrder requireOrder(String orderNo) {
        ServiceOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ServiceOrder>()
                .eq(ServiceOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
