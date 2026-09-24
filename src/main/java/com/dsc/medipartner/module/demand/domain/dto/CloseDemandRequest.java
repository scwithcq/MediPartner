package com.dsc.medipartner.module.demand.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 关闭需求工单请求体（接口文档 4.5），reason 可选。
 */
@Data
public class CloseDemandRequest {

    @Size(max = 100, message = "关闭原因不能超过100字")
    private String reason;
}
