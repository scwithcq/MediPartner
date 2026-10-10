package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.ai.domain.vo.ReportVO;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.order.domain.enums.TraceNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务报告 Prompt 构建与模型输出解析（契约 §5.10）。
 * 输出强约束 JSON：{title,summary,sections:[{heading,content}],advice:[...]}，
 * 解析端做防御：围栏剥离、首尾花括号截取、缺失字段兜底、sections/advice 数量上限裁剪、超长截断。
 * 模型只产出 title/summary/sections/advice；visitInfo 由服务层读取时从订单派生，不进 Prompt。
 */
@Component
public class ReportPromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TITLE_MAX = 60;
    private static final int SUMMARY_MAX = 300;
    private static final int SECTION_MAX = 8;
    private static final int SECTION_CONTENT_MAX = 500;
    private static final int ADVICE_MAX = 5;
    private static final int ADVICE_ITEM_MAX = 100;

    private static final String SYSTEM_PROMPT = """
            你是「医护相伴」陪诊平台的服务报告生成助手。根据一次陪诊服务的订单信息、服务小结与过程留痕时间轴，生成一份结构化的陪诊服务报告。
            只输出一个 JSON 对象，不要输出 markdown 代码块、注释或任何额外文字。JSON 结构如下：
            {"title":"报告标题","summary":"整体摘要，不超过300字","sections":[{"heading":"小节标题","content":"小节正文"}],"advice":["后续建议1","后续建议2"]}
            约束：
            1. title 简洁概括本次服务，不超过 30 字；
            2. summary 客观复述服务过程与结果，不超过 300 字，不得编造未提供的诊断结论；
            3. sections 建议 3~6 节，可包含「服务概览」「就诊过程」「陪诊师服务」「费用与时长」等小节，heading 不超过 12 字，content 不超过 200 字；
            4. advice 给出 2~5 条温和的后续就医或休养建议，每条不超过 50 字，不得替代医嘱；
            5. 报告仅供就医参考，不构成医疗诊断。""";

    /** 模型输出不符合契约时抛出，由调用方决定重试或落 FAILED */
    public static class PromptParseException extends RuntimeException {
        public PromptParseException(String message) {
            super(message);
        }
    }

    /** 解析后的报告草稿，服务层据此落库并组装 ReportVO */
    public record ReportDraft(String title,
                              String summary,
                              List<ReportVO.ReportSection> sections,
                              List<String> advice) {
    }

    public Prompt build(ReportInput input) {
        StringBuilder user = new StringBuilder();
        user.append("【订单信息】\n");
        user.append("订单号：").append(nullToEmpty(input.orderNo())).append('\n');
        if (notBlank(input.symptomDesc())) {
            user.append("症状描述：").append(input.symptomDesc().trim()).append('\n');
        }
        if (notBlank(input.city())) {
            user.append("就医城市：").append(input.city().trim()).append('\n');
        }
        if (notBlank(input.hospitalPref())) {
            user.append("意向医院：").append(input.hospitalPref().trim()).append('\n');
        }
        user.append("服务类型：").append(serviceTypeName(input.serviceType())).append('\n');
        if (input.serviceTime() != null) {
            user.append("预约服务时间：").append(input.serviceTime().format(TS)).append('\n');
        }
        if (input.actualHours() != null) {
            user.append("实际服务时长：").append(trimDecimal(input.actualHours())).append(" 小时\n");
        }
        if (input.amount() != null) {
            user.append("服务费用：").append(trimDecimal(input.amount())).append(" 元\n");
        }
        if (notBlank(input.serviceSummary())) {
            user.append("\n【陪诊师服务小结】\n").append(input.serviceSummary().trim()).append('\n');
        }
        user.append("\n【服务过程留痕时间轴】\n");
        if (input.traces() == null || input.traces().isEmpty()) {
            user.append("（本次服务未上传过程留痕，请基于订单信息与服务小结生成报告）\n");
        } else {
            int seq = 1;
            for (ReportInput.TraceLine line : input.traces()) {
                user.append(seq++).append(". ");
                if (line.watermarkTime() != null) {
                    user.append('[').append(line.watermarkTime().format(TS)).append("] ");
                }
                user.append(nodeLabel(line.node()));
                if (notBlank(line.address())) {
                    user.append("（").append(line.address().trim()).append("）");
                }
                if (notBlank(line.remark())) {
                    user.append("：").append(line.remark().trim());
                }
                user.append('\n');
            }
        }
        user.append("\n请输出 JSON 结果。");
        List<Message> messages = List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(user.toString()));
        return new Prompt(messages);
    }

    public ReportDraft parse(String text) {
        if (!StringUtils.hasText(text)) {
            throw new PromptParseException("模型输出为空");
        }
        String cleaned = stripFence(text);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new PromptParseException("模型输出中未找到 JSON");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            throw new PromptParseException("模型输出 JSON 解析失败: " + e.getMessage());
        }
        String title = truncate(root.path("title").asText("").trim(), TITLE_MAX);
        String summary = truncate(root.path("summary").asText("").trim(), SUMMARY_MAX);
        if (title.isEmpty() && summary.isEmpty()) {
            throw new PromptParseException("title 与 summary 均为空");
        }
        return new ReportDraft(title.isEmpty() ? "陪诊服务报告" : title,
                summary, parseSections(root.path("sections")), parseAdvice(root.path("advice")));
    }

    private List<ReportVO.ReportSection> parseSections(JsonNode node) {
        List<ReportVO.ReportSection> sections = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return sections;
        }
        for (JsonNode item : node) {
            if (sections.size() >= SECTION_MAX) {
                break;
            }
            String heading = truncate(item.path("heading").asText("").trim(), 20);
            String content = truncate(item.path("content").asText("").trim(), SECTION_CONTENT_MAX);
            if (heading.isEmpty() && content.isEmpty()) {
                continue;
            }
            sections.add(new ReportVO.ReportSection(heading.isEmpty() ? "服务说明" : heading, content));
        }
        return sections;
    }

    private List<String> parseAdvice(JsonNode node) {
        List<String> advice = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return advice;
        }
        for (JsonNode item : node) {
            if (advice.size() >= ADVICE_MAX) {
                break;
            }
            String text = truncate(item.asText("").trim(), ADVICE_ITEM_MAX);
            if (!text.isEmpty()) {
                advice.add(text);
            }
        }
        return advice;
    }

    /** 剥离 markdown 围栏，取首尾花括号之间的内容 */
    private String stripFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstLineEnd = t.indexOf('\n');
            if (firstLineEnd > 0) {
                t = t.substring(firstLineEnd + 1);
            }
            int fenceEnd = t.lastIndexOf("```");
            if (fenceEnd >= 0) {
                t = t.substring(0, fenceEnd);
            }
        }
        return t;
    }

    private String nodeLabel(String node) {
        if (!StringUtils.hasText(node)) {
            return "服务留痕";
        }
        for (TraceNode value : TraceNode.values()) {
            if (value.name().equals(node)) {
                return value.getLabel();
            }
        }
        return node;
    }

    private String serviceTypeName(String code) {
        if (!StringUtils.hasText(code)) {
            return "陪诊服务";
        }
        for (ServiceType type : ServiceType.values()) {
            if (type.name().equals(code)) {
                return type.getLabel();
            }
        }
        return code;
    }

    private String trimDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean notBlank(String text) {
        return StringUtils.hasText(text);
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
