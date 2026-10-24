package com.webshop.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Không có field role: role luôn do server quyết định (USER) — client không thể tự đăng ký làm ADMIN. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 72, message = "password must be 8-72 characters") String password,
        @NotBlank @Size(max = 200) String fullName) {

    /** Che mật khẩu: record mặc định in mọi field → lộ password nếu object bị log. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***]";
    }
}
