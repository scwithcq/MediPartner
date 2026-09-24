package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级模板生成器纯单测：关键词命中、补足、服务类型推断、引用挂载。
 */
class DegradedAssistGeneratorTest {

    private final DegradedAssistGenerator generator = new DegradedAssistGenerator();

    @Test
    void matchesKeywordsInOrderAndFillsToThree() {
        AssistInput input = new AssistInput("最近胸口疼，有时候心悸气短，还有点咳嗽", "重庆", null, null, null, null);
        List<DepartmentVO> result = generator.generate(input, List.of());
        assertThat(result).hasSize(3);
        assertThat(result).extracting(DepartmentVO::getDepartmentName)
                .containsExactly("心血管内科", "呼吸内科", "全科医学科");
        assertThat(result).extracting(DepartmentVO::getConfidence).containsExactly(0.75, 0.60, 0.50);
        assertThat(result).allSatisfy(vo -> {
            assertThat(vo.getHospitalSuggest()).isEqualTo("重庆三甲综合医院");
            assertThat(vo.getSuggestServiceType()).isEqualTo("ACCOMPANY");
            assertThat(vo.getReason()).contains("降级");
        });
        // 「胸口疼」不是关键词表里的「胸痛」，实际命中的是「心悸」
        assertThat(result.get(0).getReason()).contains("心悸");
    }

    @Test
    void duplicateKeywordMapsToOneDepartment() {
        AssistInput input = new AssistInput("胸痛并且心悸", "重庆", null, null, null, null);
        List<DepartmentVO> result = generator.generate(input, List.of());
        assertThat(result).extracting(DepartmentVO::getDepartmentName)
                .containsExactly("心血管内科", "全科医学科", "健康管理科");
    }

    @Test
    void fallbackDepartmentsWhenNoKeyword() {
        AssistInput input = new AssistInput("感觉不太舒服想找人陪着去医院", "重庆", null, null, null, null);
        List<DepartmentVO> result = generator.generate(input, List.of());
        assertThat(result).extracting(DepartmentVO::getDepartmentName)
                .containsExactly("全科医学科", "健康管理科", "便民门诊");
    }

    @Test
    void hospitalPrefWinsOverCity() {
        AssistInput input = new AssistInput("头痛头晕", "重庆", "重医附一院", null, null, null);
        List<DepartmentVO> result = generator.generate(input, List.of());
        assertThat(result).allSatisfy(vo -> assertThat(vo.getHospitalSuggest()).isEqualTo("重医附一院"));
        assertThat(result.get(0).getDepartmentName()).isEqualTo("神经内科");
    }

    @Test
    void serviceTypeInferredFromText() {
        DepartmentVO report = generator.generate(
                new AssistInput("帮我去医院取报告", "重庆", null, null, null, null), List.of()).get(0);
        assertThat(report.getSuggestServiceType()).isEqualTo("PICKUP_REPORT");
        DepartmentVO consult = generator.generate(
                new AssistInput("需要代问诊开药", "重庆", null, null, null, null), List.of()).get(0);
        assertThat(consult.getSuggestServiceType()).isEqualTo("CONSULT_AGENT");
    }

    @Test
    void explicitServiceTypeWins() {
        AssistInput input = new AssistInput("帮我去医院取报告", "重庆", null, null, "HALF_DAY", null);
        assertThat(generator.generate(input, List.of()).get(0).getSuggestServiceType()).isEqualTo("HALF_DAY");
    }

    @Test
    void firstDepartmentCarriesTopCitationTruncated() {
        List<KnowledgeSearchHit> hits = List.of(
                new KnowledgeSearchHit("s1", "d1", "陪诊服务规范", "长".repeat(300), 0.9),
                new KnowledgeSearchHit("s2", "d2", "就医流程常见问题", "第二条资料", 0.8));
        List<DepartmentVO> result = generator.generate(new AssistInput("胸痛", "重庆", null, null, null, null), hits);
        assertThat(result.get(0).getCitations()).hasSize(1);
        assertThat(result.get(0).getCitations().get(0).getDocId()).isEqualTo("d1");
        assertThat(result.get(0).getCitations().get(0).getDocTitle()).isEqualTo("陪诊服务规范");
        assertThat(result.get(0).getCitations().get(0).getSliceText()).hasSize(200);
        assertThat(result.get(1).getCitations()).isEmpty();
        assertThat(result.get(2).getCitations()).isEmpty();
    }
}
