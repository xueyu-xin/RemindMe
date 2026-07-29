package com.dormrepair.remindme.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// JWT 令牌工具。围绕 jjwt 库封装 4 个能力：令牌签发、令牌解析取载荷、从令牌取 userId、令牌有效性校验；
// Secret 与过期时长从 application.yaml 读取，不硬编码
@Component
public class JwtUtils {

    // JWT 签名密钥：从配置项 jwt.secret 注入，长度必须 ≥ 32 字节以满足 HS256 算法安全强度要求
    @Value("${jwt.secret}")
    private String secret;

    // 令牌过期时长（毫秒）：从配置项 jwt.expiration 注入；签发时 now + expiration = 到期日
    @Value("${jwt.expiration}")
    private Long expiration;

    // 根据 secret 字节生成 HS256 签名用的 SecretKey；每次签名/校验都重新构造，避免引用外部可变状态
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // 签发令牌。subject 存 userId；claim 附加 phone；写入 issuedAt 与 expiration；
    // 返回紧凑格式（三段 Base64）字符串，可直接放在 Authorization 头里
    public String generateToken(Long userId, String phone) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("phone", phone)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    // 解析令牌并取 Claims 载荷。签名不合法/过期/格式错误等一律抛 JwtException；
    // 不做 swallow，调用方（validateToken）决定是否捕获返回 bool
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // 从令牌载荷中提取 userId（即 subject）并转回 Long 类型
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    // 校验令牌有效性。成功 parse 即视为合法；
    // JwtException（过期/签名错/格式错）与 IllegalArgumentException（null/空字符串）统一返回 false
    public Boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
