package com.dsc.medipartner.module.ai.service;

import com.dsc.medipartner.module.ai.domain.vo.ReportVO;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.order.domain.enums.TraceNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 降级模板报告生成器：未配置模型 API Key 时按订单信息与留痕时间轴规则化产出报告（degraded=true）。
 * 保证课程演示与联调在无 Key 环境下依然可用，输出结构与真实模型完全一致（ReportDraft）。
 */
@Component
public class DegradedReportGenerator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ReportPromptBuilder.ReportDraft generate(ReportInput input) {
        String hospital = StringUtils.hasText(input.hospitalPref()) ? input.hospitalPref().trim() : "意向医院";
        String serviceTypeName = serviceTypeName(input.serviceType());
        String title = serviceTypeName + "服务报告";

        StringBuilder summary = new StringBuilder();
        summary.append("本次为").append(serviceTypeName).append("服务，");
        if (StringUtils.hasText(input.city())) {
            summary.append("就医城市").append(input.city().trim()).append("，");
        }
        summary.append("服务地点").append(hospital).append("。");
        if (input.actualHours() != null) {
            summary.append("实际服务时长约").append(trimDecimal(input.actualHours())).append("小时。");
        }
        if (StringUtils.hasText(input.serviceSummary())) {
            summary.append("陪诊师小结：").append(input.serviceSummary().trim());
        } else {
            summary.append("陪诊师已按约定完成陪诊，全程陪同就诊并协助沟通。");
        }
        summary.append("本报告由本地规则降级生成，仅供参考，请以医生诊断为准。");

        List<ReportVO.ReportSection> sections = new ArrayList<>();
        sections.add(new ReportVO.ReportSection("服务概览",
                "服务类型：" + serviceTypeName + "；服务地点：" + hospital
                        + (input.serviceTime() == null ? "" : "；预约时间：" + input.serviceTime().format(TS))
                        + (input.amount() == null ? "" : "；服务费用：" + trimDecimal(input.amount()) + " 元") + "。"));
        sections.add(new ReportVO.ReportSection("就诊过程", timeline(input)));
        if (StringUtils.hasText(input.serviceSummary())) {
            sections.add(new ReportVO.ReportSection("陪诊师服务小结", input.serviceSummary().trim()));
        }
        sections.add(new ReportVO.ReportSection("费用与时长",
                (input.actualHours() == null ? "实际服务时长以订单记录为准。"
                        : "实际服务时长约" + trimDecimal(input.actualHours()) + "小时。")
                        + (input.amount() == null ? "" : "本次服务费用" + trimDecimal(input.amount()) + "元，已完成结算。")));

        List<String> advice = List.of(
                "请遵医嘱按时复诊或用药，如有不适及时就医。",
                "保留好本次就诊的票据与检查报告，便于后续复查。",
                "如需再次陪诊，可在平台重新下单并优先选择熟悉的陪诊师。");

        return new ReportPromptBuilder.ReportDraft(title, truncate(summary.toString(), 300), sections, advice);
    }

    /** 留痕时间轴文字化；无留痕时给出占位说明 */
    private String timeline(ReportInput input) {
        if (input.traces() == null || input.traces().isEmpty()) {
            return "本次服务未上传过程留痕，陪诊师已按约定完成陪同就诊。";
        }
        StringBuilder sb = new StringBuilder();
        int seq = 1;
        for (ReportInput.TraceLine line : input.traces()) {
            sb.append(seq++).append(". ");
            if (line.watermarkTime() != null) {
                sb.append(line.watermarkTime().format(TS)).append(' ');
            }
            sb.append(nodeLabel(line.node()));
            if (StringUtils.hasText(line.address())) {
                sb.append("（").append(line.address().trim()).append("）");
            }
            if (StringUtils.hasText(line.remark())) {
                sb.append("：").append(line.remark().trim());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
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
            return "陪诊";
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
}
