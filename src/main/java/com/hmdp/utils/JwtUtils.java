package com.hmdp.utils;

import cn.hutool.core.util.IdUtil;
import com.hmdp.config.JwtProperties;
import com.hmdp.dto.UserDTO;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private final JwtProperties jwtProperties;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes());
    }

    /**
     * 生成 access token
     */
    public String createAccessToken(UserDTO userDTO) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userDTO.getId());
        claims.put("nickName", userDTO.getNickName());
        claims.put("icon", userDTO.getIcon());
        return createToken(claims, jwtProperties.getAccessTokenExpire());
    }

    /**
     * 生成 refresh token
     */
    public String createRefreshToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("random", IdUtil.fastSimpleUUID()); // 增加随机性，使每个 token 唯一
        return createToken(claims, jwtProperties.getRefreshTokenExpire());
    }

    private String createToken(Map<String, Object> claims, long expireMs) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMs);
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 token，返回 Claims
     */
    public Claims parseToken(String token) throws JwtException {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 从 token 中获取 userId
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Long.class);
    }

    /**
     * 验证 token 是否有效（签名正确且未过期）
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}