package com.dsc.medipartner.module.user.domain.dto;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
@Getter @Setter
public class SetPasswordRequest {
    @Size(max = 32)
    private String currentPassword;
    @NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,32}$", message = "密码需为8～32位，包含字母和数字，不含空格")
    private String newPassword;
}
