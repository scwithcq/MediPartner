package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.entity.SysUser;

import java.util.Collection;
import java.util.List;

public interface UserService {

    SysUser getById(Long userId);

    SysUser getByOpenid(String openid);

    SysUser createUser(String openid, String nickname, int roleMask);

    void updateProfile(Long userId, String nickname, String avatar);

    /** 批量查用户（评价列表回填昵称头像用）；入参为空返回空列表，查不到的 ID 直接缺席 */
    List<SysUser> listByIds(Collection<Long> userIds);
}
