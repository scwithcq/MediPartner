package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.demand.domain.vo.CitationVO;
import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发单助手 Prompt 构建与模型输出解析。
 * 输出强约束 JSON（departments 恰好 3 项、citationIndexes 1 基且只能引用给出的资料），
 * 解析端做防御：围栏剥离、越界引用忽略、confidence clamp、非法服务类型回落 ACCOMPANY。
 */
@Component
public class AssistPromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEPARTMENT_COUNT = 3;
    private static final int REASON_MAX = 200;
    private static final int SLICE_TEXT_MAX = 200;

    private static final String SYSTEM_PROMPT = """
            你是「医护相伴」陪诊平台的智能发单助手。根据用户的症状描述和参考资料，推荐恰好 3 个就诊科室，按置信度从高到低排列。
            只输出一个 JSON 对象，不要输出 markdown 代码块、注释或任何额外文字。JSON 结构如下：
            {"departments":[{"departmentName":"科室名","hospitalSuggest":"建议医院","reason":"推荐理由，不超过200字","confidence":0.85,"citationIndexes":[1],"suggestServiceType":"ACCOMPANY"}]}
            约束：
            1. departments 数组长度必须恰好为 3，confidence 为 0~1 之间的小数并保留两位；
            2. citationIndexes 只能引用【参考资料】中给出的 1 基序号，没有可引用资料时必须为空数组，禁止编造；
            3. suggestServiceType 只能取 ACCOMPANY、HALF_DAY、FULL_DAY、PICKUP_REPORT、CONSULT_AGENT 之一；
            4. hospitalSuggest 优先使用用户意向医院，否则给出所在城市知名三甲医院建议；
            5. 推荐仅供就医参考，不构成医疗诊断。""";

    /** 模型输出不符合契约时抛出，由调用方决定重试或报 6002 */
    public static class PromptParseException extends RuntimeException {
        public PromptParseException(String message) {
            super(message);
        }
    }

    public Prompt build(AssistInput input, List<KnowledgeSearchHit> hits) {
        StringBuilder user = new StringBuilder();
        user.append("【参考资料】\n");
        if (hits == null || hits.isEmpty()) {
            user.append("（暂无资料，请基于通用医学常识推荐，citationIndexes 全部为空数组）\n");
        } else {
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeSearchHit hit = hits.get(i);
                user.append('[').append(i + 1).append("] 《").append(hit.docTitle()).append("》：")
                        .append(hit.sliceText()).append('\n');
            }
        }
        user.append("\n【用户症状】").append(nullToEmpty(input.symptomDesc())).append('\n');
        user.append("【所在城市】").append(nullToEmpty(input.city())).append('\n');
        if (notBlank(input.hospitalPref())) {
            user.append("【意向医院】").append(input.hospitalPref().trim()).append('\n');
        }
        if (input.serviceTime() != null) {
            user.append("【期望服务时间】").append(input.serviceTime().format(TS)).append('\n');
        }
        if (notBlank(input.serviceType())) {
            user.append("【期望服务类型】").append(input.serviceType().trim()).append('\n');
        }
        if (notBlank(input.hint())) {
            user.append("【用户补充要求】").append(input.hint().trim()).append('\n');
        }
        user.append("\n请输出 JSON 结果。");
        List<Message> messages = List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(user.toString()));
        return new Prompt(messages);
    }

    public List<DepartmentVO> parse(String text, List<KnowledgeSearchHit> hits) {
        if (text == null || text.isBlank()) {
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
        JsonNode array = root.path("departments");
        if (!array.isArray() || array.size() < DEPARTMENT_COUNT) {
            throw new PromptParseException("departments 不足 3 项");
        }
        List<DepartmentVO> result = new ArrayList<>();
        for (int i = 0; i < DEPARTMENT_COUNT; i++) {
            JsonNode node = array.get(i);
            String name = node.path("departmentName").asText("").trim();
            if (name.isEmpty()) {
                throw new PromptParseException("第 " + (i + 1) + " 个科室名为空");
            }
            DepartmentVO vo = new DepartmentVO();
            vo.setDepartmentName(name);
            String hospital = node.path("hospitalSuggest").asText("").trim();
            vo.setHospitalSuggest(hospital.isEmpty() ? null : hospital);
            vo.setReason(truncate(node.path("reason").asText(""), REASON_MAX));
            vo.setConfidence(clampConfidence(node.path("confidence").asDouble(0.5)));
            vo.setCitations(resolveCitations(node.path("citationIndexes"), hits));
            String type = node.path("suggestServiceType").asText("");
            vo.setSuggestServiceType(ServiceType.isValid(type) ? type : ServiceType.ACCOMPANY.name());
            result.add(vo);
        }
        return result;
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

    /** citationIndexes -> 引用来源；越界序号忽略，同一资料去重 */
    private List<CitationVO> resolveCitations(JsonNode indexes, List<KnowledgeSearchHit> hits) {
        List<CitationVO> citations = new ArrayList<>();
        if (indexes == null || !indexes.isArray() || hits == null || hits.isEmpty()) {
            return citations;
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonNode idx : indexes) {
            int i = idx.asInt(0);
            if (i < 1 || i > hits.size() || !seen.add(i)) {
                continue;
            }
            KnowledgeSearchHit hit = hits.get(i - 1);
            CitationVO citation = new CitationVO();
            citation.setDocId(hit.docId());
            citation.setDocTitle(hit.docTitle());
            citation.setSliceText(truncate(hit.sliceText(), SLICE_TEXT_MAX));
            citations.add(citation);
        }
        return citations;
    }

    private Double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return Math.round(clamped * 100) / 100.0;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
