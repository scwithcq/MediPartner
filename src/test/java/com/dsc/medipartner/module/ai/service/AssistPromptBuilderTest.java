package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prompt 构建与模型输出解析防御逻辑纯单测。
 */
class AssistPromptBuilderTest {

    private final AssistPromptBuilder builder = new AssistPromptBuilder();

    private final List<KnowledgeSearchHit> hits = List.of(
            new KnowledgeSearchHit("s1", "d1", "陪诊服务规范", "胸痛患者应优先前往心血管内科就诊。", 0.91),
            new KnowledgeSearchHit("s2", "d2", "就医流程常见问题", "挂号可通过小程序提前完成。", 0.80));

    private static final String VALID_JSON = """
            {"departments":[
              {"departmentName":"心血管内科","hospitalSuggest":"重医附一院","reason":"胸痛心悸优先排查心源性疾病","confidence":0.9,"citationIndexes":[1],"suggestServiceType":"ACCOMPANY"},
              {"departmentName":"呼吸内科","hospitalSuggest":"重医附一院","reason":"气短需排除呼吸系统问题","confidence":0.72,"citationIndexes":[2],"suggestServiceType":"ACCOMPANY"},
              {"departmentName":"全科医学科","hospitalSuggest":"重医附一院","reason":"多症状可先全科分诊","confidence":0.55,"citationIndexes":[],"suggestServiceType":"INVALID_TYPE"}
            ]}""";

    @Test
    void buildIncludesNumberedMaterialsAndUserInput() {
        AssistInput input = new AssistInput("胸口疼心悸", "重庆", "重医附一院",
                LocalDateTime.of(2026, 9, 25, 9, 0), "ACCOMPANY", "希望安排女陪诊师");
        Prompt prompt = builder.build(input, hits);
        String system = prompt.getInstructions().get(0).getText();
        String user = prompt.getInstructions().get(1).getText();
        assertThat(system).contains("departments").contains("JSON");
        assertThat(user)
                .contains("[1] 《陪诊服务规范》：胸痛患者应优先前往心血管内科就诊。")
                .contains("[2] 《就医流程常见问题》：挂号可通过小程序提前完成。")
                .contains("【用户症状】胸口疼心悸")
                .contains("【所在城市】重庆")
                .contains("【意向医院】重医附一院")
                .contains("【期望服务时间】2026-09-25 09:00:00")
                .contains("【用户补充要求】希望安排女陪诊师");
    }

    @Test
    void buildWithoutHitsTellsModelToUseEmptyCitations() {
        AssistInput input = new AssistInput("胸口疼", "重庆", null, null, null, null);
        Prompt prompt = builder.build(input, List.of());
        String user = prompt.getInstructions().get(1).getText();
        assertThat(user).contains("暂无资料");
        assertThat(user).doesNotContain("【意向医院】").doesNotContain("【用户补充要求】");
    }

    @Test
    void parseHappyPath() {
        List<DepartmentVO> result = builder.parse(VALID_JSON, hits);
        assertThat(result).hasSize(3);
        DepartmentVO first = result.get(0);
        assertThat(first.getDepartmentName()).isEqualTo("心血管内科");
        assertThat(first.getHospitalSuggest()).isEqualTo("重医附一院");
        assertThat(first.getConfidence()).isEqualTo(0.9);
        assertThat(first.getCitations()).hasSize(1);
        assertThat(first.getCitations().get(0).getDocId()).isEqualTo("d1");
        assertThat(first.getCitations().get(0).getSliceText()).isEqualTo("胸痛患者应优先前往心血管内科就诊。");
        assertThat(first.getSuggestServiceType()).isEqualTo("ACCOMPANY");
        // 非法服务类型回落 ACCOMPANY
        assertThat(result.get(2).getSuggestServiceType()).isEqualTo("ACCOMPANY");
        assertThat(result.get(2).getCitations()).isEmpty();
    }

    @Test
    void parseStripsMarkdownFence() {
        String fenced = "```json\n" + VALID_JSON + "\n```";
        assertThat(builder.parse(fenced, hits)).hasSize(3);
    }

    @Test
    void parseTakesFirstThreeWhenMoreThanThree() {
        String item = "{\"departmentName\":\"科室X\",\"hospitalSuggest\":\"H\",\"reason\":\"r\",\"confidence\":0.5,\"citationIndexes\":[],\"suggestServiceType\":\"ACCOMPANY\"}";
        String json = "{\"departments\":[" + item + "," + item + "," + item + "," + item + "]}";
        assertThat(builder.parse(json, hits)).hasSize(3);
    }

    @Test
    void parseClampsAndRoundsConfidence() {
        assertThat(confidenceOf("1.7")).isEqualTo(1.0);
        assertThat(confidenceOf("-0.2")).isEqualTo(0.0);
        assertThat(confidenceOf("0.856")).isEqualTo(0.86);
        assertThat(confidenceOf("\"abc\"")).isEqualTo(0.5);
    }

    private Double confidenceOf(String confidenceJson) {
        String json = "{\"departments\":["
                + "{\"departmentName\":\"A\",\"confidence\":" + confidenceJson + "},"
                + "{\"departmentName\":\"B\",\"confidence\":0.5},"
                + "{\"departmentName\":\"C\",\"confidence\":0.4}]}";
        return builder.parse(json, List.of()).get(0).getConfidence();
    }

    @Test
    void parseIgnoresOutboundAndDuplicateCitationIndexes() {
        String json = "{\"departments\":["
                + "{\"departmentName\":\"A\",\"citationIndexes\":[2,9,0,2]},"
                + "{\"departmentName\":\"B\"},{\"departmentName\":\"C\"}]}";
        DepartmentVO first = builder.parse(json, hits).get(0);
        assertThat(first.getCitations()).hasSize(1);
        assertThat(first.getCitations().get(0).getDocId()).isEqualTo("d2");
    }

    @Test
    void parseTruncatesReasonAndSliceText() {
        List<KnowledgeSearchHit> longHits = List.of(
                new KnowledgeSearchHit("s1", "d1", "标题", "长".repeat(300), 0.9));
        String json = "{\"departments\":["
                + "{\"departmentName\":\"A\",\"reason\":\"" + "由".repeat(300) + "\",\"citationIndexes\":[1]},"
                + "{\"departmentName\":\"B\"},{\"departmentName\":\"C\"}]}";
        DepartmentVO first = builder.parse(json, longHits).get(0);
        assertThat(first.getReason()).hasSize(200);
        assertThat(first.getCitations().get(0).getSliceText()).hasSize(200);
    }

    @Test
    void parseRejectsFewerThanThreeDepartments() {
        String json = "{\"departments\":[{\"departmentName\":\"A\"},{\"departmentName\":\"B\"}]}";
        assertThatThrownBy(() -> builder.parse(json, hits))
                .isInstanceOf(AssistPromptBuilder.PromptParseException.class);
    }

    @Test
    void parseRejectsGarbageAndBlankDepartmentName() {
        assertThatThrownBy(() -> builder.parse(null, hits))
                .isInstanceOf(AssistPromptBuilder.PromptParseException.class);
        assertThatThrownBy(() -> builder.parse("对不起，我无法提供医疗建议。", hits))
                .isInstanceOf(AssistPromptBuilder.PromptParseException.class);
        String blankName = "{\"departments\":[{\"departmentName\":\" \"},{\"departmentName\":\"B\"},{\"departmentName\":\"C\"}]}";
        assertThatThrownBy(() -> builder.parse(blankName, hits))
                .isInstanceOf(AssistPromptBuilder.PromptParseException.class);
    }
}
