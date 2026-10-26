package com.webshop.auth.service.impl;

import com.webshop.auth.config.TokenProperties;
import com.webshop.auth.dto.response.TokenResponse;
import com.webshop.auth.entity.User;
import com.webshop.auth.service.TokenService;
import com.webshop.common.security.WebshopJwt;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final JwtEncoder jwtEncoder;
    private final TokenProperties tokenProperties;
    private final Clock clock;

    @Override
    public TokenResponse issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(tokenProperties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(tokenProperties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(WebshopJwt.ROLES_CLAIM, user.getRoles())
                // Để service khác hiển thị người dùng (ví dụ tên tác giả đánh giá, đã che bớt) mà không phải gọi Auth.
                .claim(WebshopJwt.EMAIL_CLAIM, user.getEmail())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", tokenProperties.accessTokenTtl().toSeconds(), expiresAt,
                user.getId(), user.getRoles());
    }
}
