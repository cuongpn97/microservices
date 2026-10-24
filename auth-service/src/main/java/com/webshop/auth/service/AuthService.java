package com.webshop.auth.service;

import com.webshop.auth.dto.request.LoginRequest;
import com.webshop.auth.dto.request.RegisterRequest;
import com.webshop.auth.dto.response.TokenResponse;
import com.webshop.auth.dto.response.UserResponse;

import java.util.UUID;

public interface AuthService {

    /** Đăng ký user thường (role USER) và phát event UserRegistered. Email trùng → 409. */
    UserResponse register(RegisterRequest request);

    /** Sai email hoặc mật khẩu đều trả cùng một lỗi 401 (không tiết lộ email có tồn tại hay không). */
    TokenResponse login(LoginRequest request);

    UserResponse getCurrentUser(UUID userId);

    /** Dùng cho AdminBootstrap: tạo tài khoản USER+ADMIN nếu email chưa tồn tại. */
    void createAdminIfAbsent(String email, String password);
}
