package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.DesensitizeUtil;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.dsc.medipartner.module.user.mapper.SysUserMapper;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final WorkerService workerService;

    public UserServiceImpl(SysUserMapper userMapper, @Lazy WorkerService workerService) {
        this.userMapper = userMapper;
        this.workerService = workerService;
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

    @Override
    public List<SysUser> listByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(userIds);
    }

    /**
     * 我的资料（契约 §3.1、附录 H-5）：脱敏手机号、补 roles，并带上陪诊师审核状态。
     * 跨模块取陪诊师档案走 {@code WorkerService} 接口，不注入其 Mapper（手册 §3.2 铁律）。
     */
    @Override
    public UserVO getProfile(Long userId) {
        SysUser user = getById(userId);
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        // §1.11：手机号一律脱敏后返回
        vo.setPhone(DesensitizeUtil.phone(user.getPhone()));
        vo.setRoleMask(user.getRoleMask());
        vo.setRoles(RoleEnum.rolesOf(user.getRoleMask()));
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        // 仅陪诊师或已提交申请的用户下发审核状态（§2.1）
        WorkerProfile profile = workerService.getByUserId(userId);
        if (profile != null) {
            vo.setWorkerAuditStatus(profile.getAuditStatus());
        }
        return vo;
    }
}
