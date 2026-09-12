package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.security.JwtUtil;
import com.dsc.medipartner.module.user.domain.dto.LoginRequest;
import com.dsc.medipartner.module.user.domain.dto.LoginResponse;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.dsc.medipartner.module.user.service.AuthService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WechatAuthService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private final WechatAuthService wechatAuthService;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    public AuthServiceImpl(WechatAuthService wechatAuthService, UserService userService, JwtUtil jwtUtil) {
        this.wechatAuthService = wechatAuthService;
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String openid = wechatAuthService.resolveOpenid(request.getCode());
        SysUser user = userService.getByOpenid(openid);
        if (user == null) {
            String nickname = StringUtils.hasText(request.getNickname())
                    ? request.getNickname()
                    : "用户" + openid.substring(openid.length() - 4);
            user = userService.createUser(openid, nickname, RoleEnum.USER.getCode());
        }

        String token = jwtUtil.generateToken(user.getId(), RoleEnum.authoritiesOf(user.getRoleMask()));
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUserInfo(user);
        response.setNeedBindPhone(user.getPhone() == null);
        return response;
    }
}
