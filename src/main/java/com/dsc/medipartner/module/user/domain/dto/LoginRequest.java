package com.dsc.medipartner.module.user.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "code不能为空")
    private String code;

    private String nickname;
}
