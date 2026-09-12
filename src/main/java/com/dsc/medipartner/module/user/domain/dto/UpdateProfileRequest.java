package com.dsc.medipartner.module.user.domain.dto;

import lombok.Data;

@Data
public class UpdateProfileRequest {

    private String nickname;
    private String avatar;
}
