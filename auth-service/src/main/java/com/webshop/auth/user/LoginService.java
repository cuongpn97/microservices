package com.webshop.auth.user;

import com.webshop.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class LoginService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    /** Hash giả để thời gian phản hồi giống nhau dù email tồn tại hay không (giảm user enumeration qua timing). */
    private final String dummyHash;

    public LoginService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public User authenticate(String email, String rawPassword) {
        Optional<User> user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (user.isEmpty() || !matches) {
            // Thông báo chung, không tiết lộ email có tồn tại hay không.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        return user.get();
    }
}
