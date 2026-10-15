package com.dsc.medipartner.module.user.domain.dto;

import com.dsc.medipartner.module.user.domain.vo.UserVO;
import lombok.Data;

/**
 * §2.1 登录响应。
 *
 * <p>阶段 5 起 {@code userInfo} 由裸 {@code SysUser} 实体改为 {@link UserVO}（附录 H-5）：
 * 实体出参会泄露 {@code openid}、{@code version} 等内部字段，且手机号未脱敏。
 */
@Data
public class LoginResponse {

    private String token;

    /** 用户信息（脱敏手机号、含 roles） */
    private UserVO userInfo;

    /** true 表示未绑手机号，前端引导到绑定页（阶段 5 起为真实值，此前恒为 false） */
    private Boolean needBindPhone;
    private Boolean needSetPassword;

    /** 阶段 5（§2.1）：刷新凭据，有效期 7 天 */
    private String refreshToken;

    /** accessToken 剩余秒数，便于前端提前续期（§2.1，阶段 1.5 起返回） */
    private Long expiresIn;
}
