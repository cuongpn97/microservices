package com.webshop.auth.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** expiresIn tính bằng giây (theo chuẩn OAuth2); expiresAt là thời điểm tuyệt đối để client tiện so sánh. */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        UUID userId,
        List<String> roles) {
}
