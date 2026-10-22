package com.webshop.auth.token;

import com.webshop.auth.user.User;
import com.webshop.common.security.WebshopJwt;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Phát hành access token JWT (HS256) với: sub = userId, iss, iat, exp, roles.
 * Không có refresh token trong phạm vi bài học (xem "Known gaps" trong README).
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, TokenProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(WebshopJwt.ROLES_CLAIM, user.getRoles())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.accessTokenTtl().toSeconds(), expiresAt);
    }

    public record IssuedToken(String value, long expiresInSeconds, Instant expiresAt) {
    }
}
