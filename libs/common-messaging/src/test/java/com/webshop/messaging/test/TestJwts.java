package com.webshop.messaging.test;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.webshop.common.security.WebshopJwt;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sinh JWT hợp lệ (cùng định dạng Auth Service phát hành) cho integration test của các service khác. */
public final class TestJwts {

    public static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    public static final String SECRET_PROPERTY = "webshop.security.jwt.secret=" + SECRET;

    private TestJwts() {
    }

    public static String user(UUID userId) {
        return token(userId, List.of("USER"), Duration.ofMinutes(10));
    }

    public static String admin(UUID userId) {
        return token(userId, List.of("USER", "ADMIN"), Duration.ofMinutes(10));
    }

    public static String expired(UUID userId) {
        return token(userId, List.of("USER"), Duration.ofMinutes(-10));
    }

    public static String token(UUID userId, List<String> roles, Duration ttl) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<SecurityContext>(WebshopJwt.secretKey(SECRET)));
        Instant now = Instant.now();
        Instant issuedAt = ttl.isNegative() ? now.plus(ttl).minus(Duration.ofMinutes(5)) : now;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("webshop-auth")
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(now.plus(ttl))
                .claim(WebshopJwt.ROLES_CLAIM, roles)
                .claim(WebshopJwt.EMAIL_CLAIM, "user-" + userId.toString().substring(0, 8) + "@example.com")
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
