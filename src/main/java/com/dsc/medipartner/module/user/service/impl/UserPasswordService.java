package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.TokenService;
import com.dsc.medipartner.module.user.domain.dto.*;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.dsc.medipartner.module.user.mapper.SysUserMapper;
import com.dsc.medipartner.module.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserPasswordService {
    private final UserService users;
    private final SysUserMapper mapper;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final String dummyHash;
    @Value("${medi.jwt.expire-minutes:120}") private long expireMinutes;
    public UserPasswordService(UserService users, SysUserMapper mapper, PasswordEncoder encoder, TokenService tokens) {
        this.users = users; this.mapper = mapper; this.encoder = encoder; this.tokens = tokens;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }
    public LoginResponse login(PhoneLoginRequest request) {
        if (request == null || request.getPassword() == null ||
                !request.getPassword().matches("^[\\x21-\\x7E]{1,32}$")) {
            throw new BizException(ErrorCode.LOGIN_PASSWORD_INVALID);
        }
        SysUser user = users.getByPhone(request.getPhone());
        String hash = user != null && StringUtils.hasText(user.getPasswordHash()) ? user.getPasswordHash() : dummyHash;
        boolean valid = encoder.matches(request.getPassword(), hash);
        if (user == null || !StringUtils.hasText(user.getPasswordHash()) || !valid) {
            throw new BizException(ErrorCode.LOGIN_PASSWORD_INVALID);
        }
        checkEnabled(user);
        return session(user);
    }
    public LoginResponse setPassword(Long userId, SetPasswordRequest request) {
        SysUser user = users.getById(userId);
        checkEnabled(user);
        if (!StringUtils.hasText(user.getPhone())) throw new BizException(ErrorCode.PARAM_ERROR, "请先绑定手机号");
        String oldHash = user.getPasswordHash();
        if (StringUtils.hasText(oldHash) && (!StringUtils.hasText(request.getCurrentPassword()) ||
                !encoder.matches(request.getCurrentPassword(), oldHash))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "当前密码不正确");
        }
        String password = request.getNewPassword();
        if (password == null || !password.matches("^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,32}$")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "密码需为8～32位，包含字母和数字，不含空格");
        }
        var update = new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, userId);
        // 条件更新防止并发首次设置绕过旧密码校验。
        if (oldHash == null) update.isNull(SysUser::getPasswordHash);
        else update.eq(SysUser::getPasswordHash, oldHash);
        String newHash = encoder.encode(password);
        update.set(SysUser::getPasswordHash, newHash);
        if (mapper.update(null, update) != 1) throw new BizException(ErrorCode.PARAM_ERROR, "密码已变更，请刷新后重试");
        user.setPasswordHash(newHash);
        tokens.revokeRefreshToken(userId);
        return session(user);
    }
    private void checkEnabled(SysUser user) {
        if (Integer.valueOf(0).equals(user.getStatus())) throw new BizException(ErrorCode.USER_DISABLED);
    }
    private LoginResponse session(SysUser user) {
        var result = new LoginResponse();
        result.setToken(tokens.issueAccessToken(user.getId(), RoleEnum.authoritiesOf(user.getRoleMask())));
        result.setRefreshToken(tokens.issueRefreshToken(user.getId()));
        result.setExpiresIn(expireMinutes * 60);
        result.setNeedBindPhone(!StringUtils.hasText(user.getPhone()));
        result.setNeedSetPassword(!StringUtils.hasText(user.getPasswordHash()));
        result.setUserInfo(users.getProfile(user.getId()));
        return result;
    }
}
