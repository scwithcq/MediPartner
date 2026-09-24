package com.dsc.medipartner.module.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重新生成推荐请求（接口文档 §4.2）。同一工单上限 medi.ai.max-regenerate 次。
 */
@Data
public class AssistRegenerateRequest {

    @NotBlank(message = "demandId不能为空")
    private String demandId;

    /** 用户补充要求，可选，会拼入 Prompt */
    @Size(max = 200, message = "补充要求不能超过200字")
    private String hint;
}
