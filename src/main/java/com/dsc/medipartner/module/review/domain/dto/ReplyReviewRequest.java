package com.dsc.medipartner.module.review.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 回复评价（契约 §7.21）：1~200 字纯文本，去首尾空格后非空。 */
@Data
public class ReplyReviewRequest {

    @NotBlank(message = "回复内容不能为空")
    @Size(min = 1, max = 200, message = "回复内容长度须在 1~200")
    private String content;
}
