package com.dsc.medipartner.module.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 发单助手请求（接口文档 §4.1）。
 * serviceTime 反序列化格式 yyyy-MM-dd HH:mm:ss（JacksonConfig 全局配置）。
 */
@Data
public class AiAssistRequest {

    @NotBlank(message = "症状描述不能为空")
    @Size(min = 5, max = 500, message = "症状描述长度需在5~500字之间")
    private String symptomDesc;

    @NotBlank(message = "城市不能为空")
    @Size(max = 64, message = "城市名称过长")
    private String city;

    @Size(max = 255, message = "意向医院名称过长")
    private String hospitalPref;

    /** 期望服务时间，可选 */
    private LocalDateTime serviceTime;

    /** 期望服务类型（ServiceType 枚举名），可选，影响 suggestServiceType */
    private String serviceType;
}
