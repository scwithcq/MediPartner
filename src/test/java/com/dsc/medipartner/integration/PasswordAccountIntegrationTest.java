package com.dsc.medipartner.integration;

import com.dsc.medipartner.module.user.domain.dto.*;
import com.dsc.medipartner.module.user.service.AuthService;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.impl.UserPasswordService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@Tag("integration") @SpringBootTest @ActiveProfiles("integration") @Transactional
class PasswordAccountIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserService users;
    @Autowired UserPasswordService passwords;
    @Autowired JdbcTemplate jdbc;
    @Test void sameWechatAccountCanSetChangePasswordAndLoginByPhone() {
        var request = new LoginRequest(); request.setCode("password-check-" + java.util.UUID.randomUUID());
        var first = auth.login(request); Long id = first.getUserInfo().getId();
        assertThat(first.getNeedSetPassword()).isTrue();
        String phone = null;
        for (int i = 0; i < 100; i++) {
            String candidate = "199" + String.format("%08d", new java.security.SecureRandom().nextInt(100000000));
            if (users.getByPhone(candidate) == null) { phone = candidate; break; }
        }
        assertThat(phone).isNotNull(); users.bindPhone(id, phone);
        var set = new SetPasswordRequest(); set.setNewPassword("Password123!");
        var created = passwords.setPassword(id, set);
        assertThat(created.getUserInfo().getPasswordSet()).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, id)).startsWith("$2");
        var login = new PhoneLoginRequest(); login.setPhone(phone); login.setPassword("Password123!");
        assertThat(passwords.login(login).getUserInfo().getId()).isEqualTo(id);
        users.updateProfile(id, "更新后的昵称", "/files/AVATAR/20261014/test.jpg");
        assertThat(passwords.login(login).getUserInfo().getNickname()).isEqualTo("更新后的昵称");
        set.setCurrentPassword("Password123!"); set.setNewPassword("NextPassword123!"); passwords.setPassword(id, set);
        assertThatThrownBy(() -> passwords.login(login)).hasMessage("手机号或密码不正确");
        login.setPassword("NextPassword123!"); assertThat(passwords.login(login).getUserInfo().getId()).isEqualTo(id);
        assertThat(auth.login(request).getNeedSetPassword()).isFalse();
    }
}
