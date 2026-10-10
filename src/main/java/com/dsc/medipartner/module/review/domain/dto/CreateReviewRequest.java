package com.dsc.medipartner.module.review.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 评价陪诊师（契约 §5.11）。 */
@Data
public class CreateReviewRequest {

    @NotNull(message = "star 不能为空")
    @Min(value = 1, message = "star 取值 1~5")
    @Max(value = 5, message = "star 取值 1~5")
    private Integer star;

    @Size(max = 500, message = "content 长度不能超过 500")
    private String content;

    @Size(max = 5, message = "tags 最多 5 个")
    private List<String> tags;

    @Size(max = 3, message = "imageUrls 最多 3 张")
    private List<String> imageUrls;

    /** 匿名评价，默认 false */
    private Boolean anonymous;
}
