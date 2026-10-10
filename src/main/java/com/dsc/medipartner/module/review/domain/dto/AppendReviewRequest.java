package com.dsc.medipartner.module.review.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 追评（契约 §5.13）：一评一追，评价后 7 天内。 */
@Data
public class AppendReviewRequest {

    @NotBlank(message = "追评内容不能为空")
    @Size(max = 500, message = "追评内容长度不能超过 500")
    private String content;
}
