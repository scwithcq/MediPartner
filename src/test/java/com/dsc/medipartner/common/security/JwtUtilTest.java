package com.dsc.medipartner.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-that-is-long-enough-32-bytes", 120);

    @Test
    void generateAndParse() {
        String token = jwtUtil.generateToken(123L, Set.of("ROLE_USER", "ROLE_WORKER"));
        Claims claims = jwtUtil.parse(token);

        assertThat(claims.getSubject()).isEqualTo("123");
        List<?> authList = claims.get("auth", List.class);
        List<String> auth = authList.stream().map(String::valueOf).toList();
        assertThat(auth).containsExactlyInAnyOrder("ROLE_USER", "ROLE_WORKER");
    }
}
