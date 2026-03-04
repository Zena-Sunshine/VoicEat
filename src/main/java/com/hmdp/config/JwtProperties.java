package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hm.jwt")
public class JwtProperties {
    private String secret;           // 签名密钥
    private Long accessTokenExpire;   // access token 过期时间（毫秒）
    private Long refreshTokenExpire;  // refresh token 过期时间（毫秒）
}