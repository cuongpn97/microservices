package com.webshop.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "webshop.security.jwt")
public record TokenProperties(String issuer, Duration accessTokenTtl) {

    public TokenProperties {
        issuer = issuer != null ? issuer : "webshop-auth";
        accessTokenTtl = accessTokenTtl != null ? accessTokenTtl : Duration.ofMinutes(30);
    }
}
