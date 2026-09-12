package com.dsc.medipartner.module.user.domain.dto;

import com.dsc.medipartner.module.user.domain.entity.SysUser;
import lombok.Data;

@Data
public class LoginResponse {

    private String token;
    private SysUser userInfo;
    private Boolean needBindPhone;
}
