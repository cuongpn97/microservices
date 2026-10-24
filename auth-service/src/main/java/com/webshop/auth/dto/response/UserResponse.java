package com.webshop.auth.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Không có passwordHash: DTO chỉ chứa những gì client được phép thấy. */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        List<String> roles,
        Instant createdAt) {
}
