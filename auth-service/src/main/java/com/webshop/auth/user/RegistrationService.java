package com.webshop.auth.user;

import com.webshop.common.web.ApiException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Đăng ký tài khoản.
 * <p>
 * User và event UserRegistered được ghi trong MỘT transaction cục bộ (Transactional Outbox).
 * Không có cửa sổ "user đã tạo nhưng event bị mất" hoặc "event đã gửi nhưng user rollback".
 * Ví được Payment Service tạo SAU đó (eventual consistency) — xem docs/failure-scenarios.md.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RegistrationService(UserRepository users, PasswordEncoder passwordEncoder, OutboxWriter outbox,
                               TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    public User register(String email, String rawPassword, String fullName, List<String> roles) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // Hash ngoài transaction: BCrypt tốn CPU, không nên giữ connection DB trong lúc hash.
        String hash = passwordEncoder.encode(rawPassword);
        try {
            User saved = tx.execute(status -> {
                if (users.existsByEmail(normalizedEmail)) {
                    throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Email is already registered");
                }
                Instant now = clock.instant();
                User user = users.saveAndFlush(new User(UUID.randomUUID(), normalizedEmail, hash, fullName.trim(),
                        roles, now));
                outbox.append(OutboxEvent.of("User", user.getId().toString(), "UserRegistered",
                        RoutingKeys.USER_REGISTERED, new UserRegistered(user.getId(), now)));
                return user;
            });
            log.info("User registered userId={}", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Hai request đăng ký cùng email chạy song song: unique index chặn bản thứ hai.
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Email is already registered");
        }
    }

    /**
     * Payload event v1. Cố ý KHÔNG chứa email/mật khẩu: consumer chỉ cần userId (data minimization).
     */
    public record UserRegistered(UUID userId, Instant registeredAt) {
    }
}
