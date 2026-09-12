package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.mapper.SysUserMapper;
import com.dsc.medipartner.module.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;

    public UserServiceImpl(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public SysUser getById(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Override
    public SysUser getByOpenid(String openid) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOpenid, openid));
    }

    @Override
    public SysUser createUser(String openid, String nickname, int roleMask) {
        SysUser user = new SysUser();
        user.setOpenid(openid);
        user.setNickname(nickname);
        user.setRoleMask(roleMask);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    @Override
    public void updateProfile(Long userId, String nickname, String avatar) {
        LambdaUpdateWrapper<SysUser> update = new LambdaUpdateWrapper<>();
        update.eq(SysUser::getId, userId);
        if (StringUtils.hasText(nickname)) {
            update.set(SysUser::getNickname, nickname);
        }
        if (StringUtils.hasText(avatar)) {
            update.set(SysUser::getAvatar, avatar);
        }
        userMapper.update(null, update);
    }
}
