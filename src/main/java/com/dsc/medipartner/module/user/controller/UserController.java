package com.dsc.medipartner.module.user.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.user.domain.dto.UpdateProfileRequest;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/profile")
    public R<SysUser> profile() {
        return R.ok(userService.getById(UserContext.userId()));
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody UpdateProfileRequest request) {
        userService.updateProfile(UserContext.userId(), request.getNickname(), request.getAvatar());
        return R.ok();
    }
}
