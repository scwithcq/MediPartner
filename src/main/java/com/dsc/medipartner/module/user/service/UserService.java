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

    /**
     * 我的资料（契约 §3.1、附录 H-5）：返回 {@link UserVO} 而非裸实体，
     * {@code phone} 走 {@code DesensitizeUtil} 脱敏；用户不存在抛 2001。
     */
    UserVO getProfile(Long userId);

    /** §3.3 「我的」页聚合：一次返回用户、钱包摘要、各状态订单数与入驻入口 */
    UserOverviewVO getOverview(Long userId);
}
