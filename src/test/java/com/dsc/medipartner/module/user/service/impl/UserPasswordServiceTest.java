package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.cache.LocalCacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.*;
import com.dsc.medipartner.module.user.domain.dto.*;
import com.dsc.medipartner.module.user.domain.entity.SysUser;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.dsc.medipartner.module.user.mapper.SysUserMapper;
import com.dsc.medipartner.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserPasswordServiceTest {
    private final UserService users = mock(UserService.class);
    private final SysUserMapper mapper = mock(SysUserMapper.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final TokenService tokens = new TokenService(new LocalCacheService(),
        new JwtUtil("Password_Test_Secret_012345678901234567890123456789", 120), 10080);
    private final UserPasswordService service = new UserPasswordService(users, mapper, encoder, tokens);
    private SysUser user;
    @BeforeAll static void tables() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }
    @BeforeEach void setup() {
        user = new SysUser(); user.setId(9L); user.setPhone("13800138000"); user.setRoleMask(3); user.setStatus(1);
        when(users.getById(9L)).thenReturn(user); when(users.getByPhone(user.getPhone())).thenReturn(user);
        when(users.getProfile(9L)).thenAnswer(inv -> { var p = new UserVO(); p.setId(9L); p.setPasswordSet(user.getPasswordHash() != null); return p; });
        when(mapper.update(isNull(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "expireMinutes", 120L);
    }
    private SetPasswordRequest password(String current, String next) {
        var r = new SetPasswordRequest(); r.setCurrentPassword(current); r.setNewPassword(next); return r;
    }
    private PhoneLoginRequest login(String phone, String password) {
        var r = new PhoneLoginRequest(); r.setPhone(phone); r.setPassword(password); return r;
    }
    @Test void setsHashAndLogsIntoSameAccountWithFreshCredentials() {
        String oldRefresh = tokens.issueRefreshToken(9L);
        var response = service.setPassword(9L, password(null, "Password123!"));
        assertThat(user.getPasswordHash()).isNotEqualTo("Password123!");
        assertThat(encoder.matches("Password123!", user.getPasswordHash())).isTrue();
        assertThat(response.getNeedSetPassword()).isFalse();
        assertThat(response.getUserInfo().getPasswordSet()).isTrue();
        assertThat(tokens.resolveUserIdByRefreshToken(oldRefresh)).isNull();
        var signedIn = service.login(login(user.getPhone(), "Password123!"));
        assertThat(signedIn.getUserInfo().getId()).isEqualTo(9L);
        assertThat(signedIn.getToken()).isNotBlank(); assertThat(signedIn.getRefreshToken()).isNotBlank();
    }
    @Test void wrongUnknownAndUnsetPasswordsShareError() {
        user.setPasswordHash(encoder.encode("Password123!"));
        assertThatThrownBy(() -> service.login(login(user.getPhone(), "Wrong1234")))
            .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOGIN_PASSWORD_INVALID));
        user.setPasswordHash(null);
        assertThatThrownBy(() -> service.login(login(user.getPhone(), "Password123!"))).hasMessage("手机号或密码不正确");
        assertThatThrownBy(() -> service.login(login("13900139000", "Password123!"))).hasMessage("手机号或密码不正确");
    }
    @Test void disabledAccountCannotLoginOrSetPassword() {
        user.setPasswordHash(encoder.encode("Password123!")); user.setStatus(0);
        assertThatThrownBy(() -> service.login(login(user.getPhone(), "Password123!"))).hasMessageContaining("禁用");
        assertThatThrownBy(() -> service.setPassword(9L, password("Password123!", "NewPassword123"))).hasMessageContaining("禁用");
        verifyNoInteractions(mapper);
    }
    @Test void requiresBoundPhoneAndExistingPasswordForChanges() {
        user.setPhone(null);
        assertThatThrownBy(() -> service.setPassword(9L, password(null, "Password123!"))).hasMessage("请先绑定手机号");
        user.setPhone("13800138000"); user.setPasswordHash(encoder.encode("Password123!"));
        assertThatThrownBy(() -> service.setPassword(9L, password(null, "NextPassword123"))).hasMessage("当前密码不正确");
        assertThatThrownBy(() -> service.setPassword(9L, password("wrong", "NextPassword123"))).hasMessage("当前密码不正确");
        service.setPassword(9L, password("Password123!", "NextPassword123"));
        assertThat(encoder.matches("NextPassword123", user.getPasswordHash())).isTrue();
        assertThatThrownBy(() -> service.login(login(user.getPhone(), "Password123!"))).hasMessage("手机号或密码不正确");
    }
    @Test void rejectsWeakPasswordsAndConcurrentChanges() {
        for (String weak : new String[]{"12345678", "password", "Short1", "Password 123"})
            assertThatThrownBy(() -> service.setPassword(9L, password(null, weak))).hasMessageContaining("8～32");
        when(mapper.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.setPassword(9L, password(null, "Password123"))).hasMessageContaining("密码已变更");
        assertThat(user.getPasswordHash()).isNull();
    }
    @Test void hashIsNeverSerialized() throws Exception {
        user.setPasswordHash(encoder.encode("Password123!"));
        assertThat(new ObjectMapper().writeValueAsString(user)).doesNotContain("passwordHash", user.getPasswordHash());
    }
}
