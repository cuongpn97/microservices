package com.webshop.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Tiện ích JWT dùng chung: mọi service tự verify token (defense in depth) thay vì tin gateway.
 * Local dev dùng HS256 + shared secret; production nên dùng RS256/ES256 + JWKS (xem docs/design-decisions.md).
 */
public final class WebshopJwt {

    public static final String ROLES_CLAIM = "roles";
    public static final String EMAIL_CLAIM = "email";
    private static final int MIN_SECRET_BYTES = 32;

    private WebshopJwt() {
    }

    public static SecretKey secretKey(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "webshop.security.jwt.secret (JWT_SECRET) must be set and at least 32 bytes for HS256");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public static JwtDecoder decoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties.secret()))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Kiểm tra exp/nbf (clock skew mặc định 60s) và issuer.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    public static Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** userId luôn lấy từ claim "sub" của token đã verify — không bao giờ lấy từ body/query của client. */
    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
