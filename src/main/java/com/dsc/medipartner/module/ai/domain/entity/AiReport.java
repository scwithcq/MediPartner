package com.dsc.medipartner.module.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AI 陪诊报告（契约 V10 ai_report 表）。一单一报告，uk_order_no 兜底。
 * sections 存结构化正文 JSON（[{heading,content}]），advice 存建议 JSON 数组，读写都过 ObjectMapper。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_report")
public class AiReport extends BaseEntity {

    private String orderNo;
    /** PROCESSING / DONE / FAILED */
    private String status;
    private String title;
    private String summary;
    /** 结构化正文 JSON：[{"heading":"...","content":"..."}] */
    private String sections;
    /** 后续建议 JSON 数组：["...","..."] */
    private String advice;
    /** 生成厂商：deepseek / qwen / zhipu */
    private String provider;
    /** 1 降级模板生成 0 真实模型 */
    private Integer degraded;
    /** 失败原因（用户可见文案） */
    private String failReason;
    private LocalDateTime generatedAt;
    /** 重生成版本号，覆盖时自增 */
    private Integer version;
    /** 已重试次数（上限 3） */
    private Integer retryCount;
}
