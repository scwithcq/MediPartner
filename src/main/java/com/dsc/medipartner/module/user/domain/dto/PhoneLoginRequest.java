package com.dsc.medipartner.module.user.domain.dto;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
@Getter @Setter
public class PhoneLoginRequest {
    @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;
    @NotBlank @Size(max = 32)
    private String password;
}
