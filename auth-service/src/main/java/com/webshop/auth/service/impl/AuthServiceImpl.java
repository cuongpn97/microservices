package com.webshop.auth.service.impl;

import com.webshop.auth.dto.request.LoginRequest;
import com.webshop.auth.dto.request.RegisterRequest;
import com.webshop.auth.dto.response.TokenResponse;
import com.webshop.auth.dto.response.UserResponse;
import com.webshop.auth.entity.User;
import com.webshop.auth.mapper.UserMapper;
import com.webshop.auth.messaging.event.UserRegisteredEvent;
import com.webshop.auth.repository.UserRepository;
import com.webshop.auth.service.AuthService;
import com.webshop.auth.service.TokenService;
import com.webshop.common.web.ApiException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Đăng ký / đăng nhập.
 * <p>
 * Đăng ký: User và event UserRegistered được ghi trong MỘT transaction cục bộ (Transactional Outbox).
 * Không có cửa sổ "user đã tạo nhưng event bị mất" hoặc "event đã gửi nhưng user rollback".
 * Ví được Payment Service tạo SAU đó (eventual consistency) — xem docs/failure-scenarios.md.
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /** Hash giả để thời gian phản hồi giống nhau dù email tồn tại hay không (giảm user enumeration qua timing). */
    private final String dummyHash;

    // Viết tay thay vì @RequiredArgsConstructor vì cần tính dummyHash lúc khởi tạo.
    public AuthServiceImpl(UserRepository userRepository, UserMapper userMapper, TokenService tokenService,
                           PasswordEncoder passwordEncoder, OutboxWriter outboxWriter,
                           TransactionTemplate transactionTemplate, Clock clock) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Override
    public UserResponse register(RegisterRequest request) {
        User user = createUser(request.email(), request.password(), request.fullName(), List.of("USER"));
        return userMapper.toResponse(user);
    }

    @Override
    public TokenResponse login(LoginRequest request) {
        Optional<User> user = userRepository.findByEmail(normalize(request.email()));
        // Luôn chạy BCrypt (với hash thật hoặc hash giả) để email không tồn tại cũng tốn thời gian như nhau.
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        return tokenService.issue(user.get());
    }

    @Override
    public UserResponse getCurrentUser(UUID userId) {
        return userRepository.findById(userId).map(userMapper::toResponse)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    @Override
    public void createAdminIfAbsent(String email, String password) {
        if (userRepository.existsByEmail(normalize(email))) {
            return;
        }
        // Đi qua createUser nên admin cũng phát UserRegistered → có ví như user thường.
        createUser(email, password, "Local Admin", List.of("USER", "ADMIN"));
        log.info("Bootstrap admin account created");
    }

    /**
     * Dùng TransactionTemplate thay vì @Transactional: BCrypt tốn CPU nên phải hash TRƯỚC khi mở transaction,
     * không giữ connection DB trong lúc hash. @Transactional ở mức method không tách được hai phần này.
     */
    private User createUser(String email, String rawPassword, String fullName, List<String> roles) {
        String normalizedEmail = normalize(email);
        String hash = passwordEncoder.encode(rawPassword);
        try {
            User saved = transactionTemplate.execute(status -> {
                if (userRepository.existsByEmail(normalizedEmail)) {
                    throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Email is already registered");
                }
                Instant now = clock.instant();
                User user = userRepository.saveAndFlush(
                        new User(UUID.randomUUID(), normalizedEmail, hash, fullName.trim(), roles, now));
                outboxWriter.append(OutboxEvent.of("User", user.getId().toString(), "UserRegistered",
                        RoutingKeys.USER_REGISTERED, new UserRegisteredEvent(user.getId(), now)));
                return user;
            });
            log.info("User registered userId={}", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Hai request đăng ký cùng email chạy song song: unique index chặn bản thứ hai.
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Email is already registered");
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
