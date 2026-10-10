package com.dsc.medipartner.module.user.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashSet;
import java.util.Set;

@Getter
@AllArgsConstructor
public enum RoleEnum {

    USER(1, "ROLE_USER"),
    WORKER(2, "ROLE_WORKER"),
    ADMIN(4, "ROLE_ADMIN");

    private final int code;
    private final String authority;

    public static Set<String> authoritiesOf(int roleMask) {
        Set<String> authorities = new HashSet<>();
        for (RoleEnum role : values()) {
            if ((roleMask & role.code) != 0) {
                authorities.add(role.authority);
            }
        }
        return authorities;
    }

    /**
     * 角色名列表（契约 §2.1 {@code UserVO.roles}），如 {@code ["USER","WORKER"]}，
     * 用枚举声明顺序保证输出稳定，前端免做位运算。
     */
    public static java.util.List<String> rolesOf(Integer roleMask) {
        if (roleMask == null) {
            return java.util.List.of();
        }
        java.util.List<String> roles = new java.util.ArrayList<>();
        for (RoleEnum role : values()) {
            if ((roleMask & role.code) != 0) {
                roles.add(role.name());
            }
        }
        return roles;
    }
}
