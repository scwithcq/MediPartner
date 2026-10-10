package com.dsc.medipartner.module.user.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.user.domain.dto.UpdateProfileRequest;
import com.dsc.medipartner.module.user.domain.vo.UserOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
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

    /** §3.1 我的资料：返回 UserVO，手机号脱敏（附录 H-5 整改，不再裸出 SysUser 实体） */
    @GetMapping("/profile")
    public R<UserVO> profile() {
        return R.ok(userService.getProfile(UserContext.userId()));
    }

    /** §3.3 「我的」页聚合：一次请求渲染整页，避免首屏 4~5 个并发请求 */
    @GetMapping("/overview")
    public R<UserOverviewVO> overview() {
        return R.ok(userService.getOverview(UserContext.userId()));
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody UpdateProfileRequest request) {
        userService.updateProfile(UserContext.userId(), request.getNickname(), request.getAvatar());
        return R.ok();
    }
}
