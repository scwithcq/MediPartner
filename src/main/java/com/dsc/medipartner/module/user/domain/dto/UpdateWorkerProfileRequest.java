package com.dsc.medipartner.module.user.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修改接单资料（契约 §7.3）：字段全部可选，只提交要改的；
 * realName / idCardNo 不在此接口修改范围，只能走 §3.4 重新入驻，避免绕过实名审核。
 */
@Data
public class UpdateWorkerProfileRequest {

    @Size(max = 64, message = "city 长度不能超过 64")
    private String city;

    @Size(max = 5, message = "serviceTypes 最多 5 项")
    private List<String> serviceTypes;

    @Size(max = 500, message = "intro 长度不能超过 500")
    private String intro;

    @Size(max = 255, message = "healthCertUrl 长度不能超过 255")
    private String healthCertUrl;
}
