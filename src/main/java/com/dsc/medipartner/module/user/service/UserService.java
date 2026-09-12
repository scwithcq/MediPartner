package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.entity.SysUser;

public interface UserService {

    SysUser getById(Long userId);

    SysUser getByOpenid(String openid);

    SysUser createUser(String openid, String nickname, int roleMask);

    void updateProfile(Long userId, String nickname, String avatar);
}
