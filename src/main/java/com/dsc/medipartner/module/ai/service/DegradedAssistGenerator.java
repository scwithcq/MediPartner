package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.demand.domain.vo.CitationVO;
import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 降级模板生成器：未配置模型 API Key 时按症状关键词规则输出 3 个科室（degraded=true）。
 * 保证课程演示与联调在无 Key 环境下依然可用，输出结构与真实模型完全一致。
 */
@Component
public class DegradedAssistGenerator {

    private static final int DEPARTMENT_COUNT = 3;
    private static final int CITATION_TEXT_MAX = 200;
    private static final double[] CONFIDENCE = {0.75, 0.60, 0.50};

    /** 有序关键词 -> 科室映射，命中越靠前优先级越高 */
    private static final Map<String, String> KEYWORDS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("胸痛", "心血管内科");
        m.put("心悸", "心血管内科");
        m.put("胸闷", "心血管内科");
        m.put("咳嗽", "呼吸内科");
        m.put("发热", "呼吸内科");
        m.put("发烧", "呼吸内科");
        m.put("腹痛", "消化内科");
        m.put("腹泻", "消化内科");
        m.put("胃痛", "消化内科");
        m.put("头痛", "神经内科");
        m.put("头晕", "神经内科");
        m.put("骨折", "骨科");
        m.put("腰痛", "骨科");
        m.put("腰", "骨科");
        m.put("皮疹", "皮肤科");
        m.put("过敏", "皮肤科");
        m.put("月经", "妇产科");
        m.put("孕", "妇产科");
        m.put("儿童", "儿科");
        m.put("孩子", "儿科");
        KEYWORDS = Collections.unmodifiableMap(m);
    }

    /** 关键词未命中或不足 3 个时的补足科室 */
    private static final List<String> FALLBACK_DEPARTMENTS = List.of("全科医学科", "健康管理科", "便民门诊");

    public List<DepartmentVO> generate(AssistInput input, List<KnowledgeSearchHit> hits) {
        String text = input == null || input.symptomDesc() == null ? "" : input.symptomDesc();
        String serviceType = resolveServiceType(input, text);
        String hospital = resolveHospital(input);

        LinkedHashSet<String> names = new LinkedHashSet<>();
        Map<String, String> matchedKeyword = new HashMap<>();
        for (Map.Entry<String, String> entry : KEYWORDS.entrySet()) {
            if (names.size() >= DEPARTMENT_COUNT) {
                break;
            }
            if (text.contains(entry.getKey()) && names.add(entry.getValue())) {
                matchedKeyword.put(entry.getValue(), entry.getKey());
            }
        }
        for (String fallback : FALLBACK_DEPARTMENTS) {
            if (names.size() >= DEPARTMENT_COUNT) {
                break;
            }
            names.add(fallback);
        }

        List<DepartmentVO> result = new ArrayList<>();
        int index = 0;
        for (String name : names) {
            DepartmentVO vo = new DepartmentVO();
            vo.setDepartmentName(name);
            vo.setHospitalSuggest(hospital);
            String keyword = matchedKeyword.get(name);
            vo.setReason(keyword != null
                    ? "症状描述中提到「" + keyword + "」，建议优先前往" + name + "就诊检查。本结果由本地规则降级生成，仅供参考，请以医生诊断为准。"
                    : "症状描述未命中明确科室关键词，推荐" + name + "作为通用就诊选项。本结果由本地规则降级生成，仅供参考，请以医生诊断为准。");
            vo.setConfidence(CONFIDENCE[Math.min(index, CONFIDENCE.length - 1)]);
            vo.setCitations(index == 0 ? topCitation(hits) : List.of());
            vo.setSuggestServiceType(serviceType);
            result.add(vo);
            index++;
        }
        return result;
    }

    private String resolveServiceType(AssistInput input, String text) {
        if (input != null && ServiceType.isValid(input.serviceType())) {
            return input.serviceType();
        }
        if (text.contains("报告")) {
            return ServiceType.PICKUP_REPORT.name();
        }
        if (text.contains("代问诊")) {
            return ServiceType.CONSULT_AGENT.name();
        }
        return ServiceType.ACCOMPANY.name();
    }

    private String resolveHospital(AssistInput input) {
        if (input == null) {
            return "当地三甲综合医院";
        }
        if (input.hospitalPref() != null && !input.hospitalPref().isBlank()) {
            return input.hospitalPref().trim();
        }
        String city = input.city() == null || input.city().isBlank() ? "当地" : input.city().trim();
        return city + "三甲综合医院";
    }

    /** 首个科室挂检索得分最高的资料引用，保持与真实模型输出一致的结构 */
    private List<CitationVO> topCitation(List<KnowledgeSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        KnowledgeSearchHit hit = hits.get(0);
        CitationVO citation = new CitationVO();
        citation.setDocId(hit.docId());
        citation.setDocTitle(hit.docTitle());
        citation.setSliceText(truncate(hit.sliceText(), CITATION_TEXT_MAX));
        return List.of(citation);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
