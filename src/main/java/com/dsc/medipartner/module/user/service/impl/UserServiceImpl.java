package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.DesensitizeUtil;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.AuditStatusEnum;
import com.dsc.medipartner.module.user.domain.enums.RoleEnum;
import com.dsc.medipartner.module.user.domain.vo.UserOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.dsc.medipartner.module.user.mapper.SysUserMapper;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import com.dsc.medipartner.module.wallet.domain.vo.WalletVO;
import com.dsc.medipartner.module.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final WorkerService workerService;
    private final WalletService walletService;
    private final OrderService orderService;

    public UserServiceImpl(SysUserMapper userMapper, @Lazy WorkerService workerService,
                           @Lazy WalletService walletService, @Lazy OrderService orderService) {
        this.userMapper = userMapper;
        this.workerService = workerService;
        this.walletService = walletService;
        this.orderService = orderService;
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

    @Override
    public List<SysUser> listByPhones(Collection<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return List.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getPhone, phones));
    }

    @Override
    public void bindPhoneIfAbsent(Long userId, String phone) {
        if (!StringUtils.hasText(phone)) {
            return;
        }
        // 条件更新：仅当当前 phone 为空时写入，避免覆盖用户已绑定的号码
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .and(w -> w.isNull(SysUser::getPhone).or().eq(SysUser::getPhone, ""))
                .set(SysUser::getPhone, phone.trim()));
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

    /**
     * §3.3 「我的」页聚合。一次请求渲染整页，避免小程序首屏发 4~5 个并发请求。
     * 跨模块只调对方 Service（手册 §3.2 铁律）：钱包走 WalletService、订单计数走 OrderService。
     */
    @Override
    public UserOverviewVO getOverview(Long userId) {
        UserOverviewVO overview = new UserOverviewVO();
        overview.setUserInfo(getProfile(userId));

        // 钱包摘要：账户不存在时不阻断整页渲染
        try {
            WalletVO account = walletService.getAccountVO(userId);
            UserOverviewVO.WalletBrief wallet = new UserOverviewVO.WalletBrief();
            wallet.setBalance(account.getBalance());
            wallet.setFrozen(account.getFrozen());
            overview.setWallet(wallet);
        } catch (Exception e) {
            log.warn("[user] 查询钱包账户失败，overview 不返回钱包摘要 userId={}: {}", userId, e.getMessage());
        }

        overview.setOrderCount(orderService.countByStatusForUser(userId));

        // 入驻入口（§3.3 workerEntry）：非陪诊师且未申请时 canApply=true
        WorkerProfile profile = workerService.getByUserId(userId);
        UserOverviewVO.WorkerEntry entry = new UserOverviewVO.WorkerEntry();
        if (profile == null) {
            entry.setCanApply(true);
        } else {
            entry.setAuditStatus(profile.getAuditStatus());
            entry.setCreditScore(profile.getCreditScore());
            boolean passed = AuditStatusEnum.PASS.name().equals(profile.getAuditStatus());
            // 已通过则无需再申请；待审核期间也不允许重复提交（§3.4 返回 5002）
            entry.setCanApply(AuditStatusEnum.REJECT.name().equals(profile.getAuditStatus()));
            if (passed) {
                entry.setCanApply(false);
            }
        }
        overview.setWorkerEntry(entry);

        // 未读通知数属阶段 5，未上线恒为 0（§3.3）
        overview.setUnreadNotice(0);
        return overview;
    }
}
