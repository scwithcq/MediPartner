package com.dsc.medipartner.module.user.controller;
import com.dsc.medipartner.common.ratelimit.RateLimit;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.user.domain.dto.*;
import com.dsc.medipartner.module.user.service.impl.UserPasswordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
public class PasswordController {
    private final UserPasswordService service;
    public PasswordController(UserPasswordService service) { this.service = service; }
    @PostMapping("/api/auth/phone/login")
    @RateLimit(name = "phonePasswordLogin", limit = 10, windowSeconds = 300, dimension = RateLimit.Dimension.PHONE)
    public R<LoginResponse> login(@Valid @RequestBody PhoneLoginRequest request) { return R.ok(service.login(request)); }
    @PutMapping("/api/user/password")
    @RateLimit(name = "setPassword", limit = 5, windowSeconds = 3600)
    public R<LoginResponse> set(@Valid @RequestBody SetPasswordRequest request) {
        return R.ok(service.setPassword(UserContext.userId(), request));
    }
}
