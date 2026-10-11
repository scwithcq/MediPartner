package com.dsc.medipartner.common.security;

import com.dsc.medipartner.common.result.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenService tokenService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, TokenService tokenService) {
        this.jwtUtil = jwtUtil;
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                List<?> authList = claims.get("auth", List.class);
                Set<String> roles = authList == null
                        ? Set.of()
                        : authList.stream().map(String::valueOf).collect(Collectors.toSet());

                // 阶段 5（§2.3）：登出后 token 立即失效。
                // JWT 无状态，不做这一步「退出登录」就只是前端删了本地存储，
                // 被截获的 token 仍能继续用到自然过期（最长 2 小时）。
                if (tokenService.isBlacklisted(token)) {
                    request.setAttribute("authError", ErrorCode.UNAUTHORIZED);
                } else {
                    LoginUser loginUser = new LoginUser(userId, roles);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute("authError", ErrorCode.TOKEN_EXPIRED);
            } catch (Exception e) {
                request.setAttribute("authError", ErrorCode.UNAUTHORIZED);
            }
        }
        filterChain.doFilter(request, response);
    }
}
