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
}
