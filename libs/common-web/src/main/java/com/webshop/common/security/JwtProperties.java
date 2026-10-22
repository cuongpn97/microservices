package com.webshop.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình xác thực JWT. Secret KHÔNG có giá trị mặc định trong source code —
 * phải được cấp qua biến môi trường JWT_SECRET (xem .env.example).
 */
@ConfigurationProperties(prefix = "webshop.security.jwt")
public record JwtProperties(String secret, String issuer) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "webshop-auth";
        }
    }
}
