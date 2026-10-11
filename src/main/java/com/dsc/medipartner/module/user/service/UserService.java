package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.vo.UserOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.UserVO;

import java.util.Collection;
import java.util.List;

public interface UserService {

    SysUser getById(Long userId);

    SysUser getByOpenid(String openid);

    SysUser createUser(String openid, String nickname, int roleMask);

    void updateProfile(Long userId, String nickname, String avatar);

    /** 批量查用户（评价列表回填昵称头像用）；入参为空返回空列表，查不到的 ID 直接缺席 */
    List<SysUser> listByIds(Collection<Long> userIds);

    /** 按手机号批量查（入驻申请做手机号唯一性校验用）；入参为空返回空列表 */
    List<SysUser> listByPhones(Collection<String> phones);

    /** 用户当前未绑定手机号时补绑（入驻申请把联系手机号同步到账号）；已有手机号则不动 */
    void bindPhoneIfAbsent(Long userId, String phone);

    /** 按手机号精确查用户（阶段 5：绑定前判重）；查不到返回 null */
    SysUser getByPhone(String phone);

    /**
     * 绑定/换绑手机号（契约 §2.4 / §2.6），已存在该手机号的其它账号时抛 {@code 2002}。
     *
     * <p>与 {@link #bindPhoneIfAbsent} 的区别：本方法<b>会覆盖</b>已有手机号，
     * 且做全局唯一性校验；后者只在空值时补写，用于入驻同步场景。
     *
     * @return 脱敏后的手机号（契约要求响应回显 {@code 138****1234}）
     */
    String bindPhone(Long userId, String phone);

    /**
     * 我的资料（契约 §3.1、附录 H-5）：返回 {@link UserVO} 而非裸实体，
     * {@code phone} 走 {@code DesensitizeUtil} 脱敏；用户不存在抛 2001。
     */
    UserVO getProfile(Long userId);

    /** §3.3 「我的」页聚合：一次返回用户、钱包摘要、各状态订单数与入驻入口 */
    UserOverviewVO getOverview(Long userId);
}
