package com.webshop.auth.config;

import com.webshop.auth.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Tạo tài khoản ADMIN cho môi trường local nếu ADMIN_EMAIL/ADMIN_PASSWORD được cấu hình.
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    private final AuthService authService;
    private final String email;
    private final String password;

    // Viết tay vì @RequiredArgsConstructor không chép @Value sang tham số constructor.
    public AdminBootstrap(AuthService authService,
                          @Value("${webshop.admin.email:}") String email,
                          @Value("${webshop.admin.password:}") String password) {
        this.authService = authService;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        try {
            authService.createAdminIfAbsent(email, password);
        } catch (Exception e) {
            // Nhiều instance khởi động cùng lúc có thể đua nhau tạo admin; unique email chặn bản trùng.
            log.info("Bootstrap admin not created: {}", e.getMessage());
        }
    }
}
