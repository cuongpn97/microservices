package com.webshop.auth.config;

import com.webshop.auth.user.RegistrationService;
import com.webshop.auth.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Tạo tài khoản ADMIN cho môi trường local nếu ADMIN_EMAIL/ADMIN_PASSWORD được cấu hình.
 * Đi qua RegistrationService nên admin cũng nhận UserRegistered → có ví như user thường.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final RegistrationService registrationService;
    private final UserRepository users;
    private final String email;
    private final String password;

    public AdminBootstrap(RegistrationService registrationService, UserRepository users,
                          @Value("${webshop.admin.email:}") String email,
                          @Value("${webshop.admin.password:}") String password) {
        this.registrationService = registrationService;
        this.users = users;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (users.existsByEmail(email.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            registrationService.register(email, password, "Local Admin", List.of("USER", "ADMIN"));
            log.info("Bootstrap admin account created");
        } catch (Exception e) {
            // Nhiều instance khởi động cùng lúc có thể đua nhau tạo admin; unique email chặn bản trùng.
            log.info("Bootstrap admin not created: {}", e.getMessage());
        }
    }
}
