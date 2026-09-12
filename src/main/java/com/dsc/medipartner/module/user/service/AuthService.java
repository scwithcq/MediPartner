package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);
}
