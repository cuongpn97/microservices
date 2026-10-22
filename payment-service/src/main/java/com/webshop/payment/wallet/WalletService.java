package com.webshop.payment.wallet;

import com.webshop.common.web.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.webshop.payment.wallet.CreditOutcome.CAPTURED;
import static com.webshop.payment.wallet.CreditOutcome.CAPTURE_REJECTED;
import static com.webshop.payment.wallet.CreditOutcome.RELEASED;
import static com.webshop.payment.wallet.CreditOutcome.RELEASE_REJECTED;
import static com.webshop.payment.wallet.CreditOutcome.RESERVATION_REJECTED;
import static com.webshop.payment.wallet.CreditOutcome.RESERVED;

/**
 * Nghiệp vụ ví. Dùng SQL tường minh (JdbcTemplate) vì với tiền, cần thấy rõ từng câu lệnh và điều kiện:
 * <ul>
 *     <li><b>Atomic conditional update</b>: {@code UPDATE wallet SET reserved = reserved + :amt
 *     WHERE balance - reserved >= :amt} — kiểm tra và trừ trong MỘT câu lệnh, không có khoảng hở
 *     "đọc rồi mới ghi" giữa hai request đồng thời.</li>
 *     <li><b>Khóa idempotency nghiệp vụ</b>: credit_reservation.id = orderId; ledger.idempotency_key unique.</li>
 *     <li><b>Ledger append-only</b>: mỗi thay đổi số dư ghi một dòng; trigger DB cấm UPDATE/DELETE.</li>
 *     <li><b>CHECK constraint</b>: reserved_vnd &lt;= balance_vnd → available không bao giờ âm.</li>
 * </ul>
 * Các phương thức ghi yêu cầu transaction có sẵn (MANDATORY) — do consumer mở, cùng với Inbox + Outbox reply.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long initialCreditVnd;

    public WalletService(JdbcTemplate jdbc, Clock clock,
                         @Value("${webshop.wallet.initial-credit-vnd:10000000}") long initialCreditVnd) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.initialCreditVnd = initialCreditVnd;
    }

    /**
     * Tạo ví + tặng credit ban đầu. Gọi lại bao nhiêu lần cũng chỉ tặng MỘT lần:
     * unique(user_id) trên wallet và unique(idempotency_key) trên ledger.
     *
     * @return true nếu lần này thực sự tạo ví.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean createWalletWithInitialCredit(UUID userId) {
        Timestamp now = Timestamp.from(clock.instant());
        UUID walletId = UUID.randomUUID();
        int created = jdbc.update("""
                INSERT INTO wallet (id, user_id, balance_vnd, reserved_vnd, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, walletId, userId, initialCreditVnd, now, now);
        if (created == 0) {
            log.info("Wallet already exists, initial credit not granted again userId={}", userId);
            return false;
        }
        appendLedger(walletId, "INITIAL_CREDIT", initialCreditVnd, initialCreditVnd, 0,
                "initial-credit:" + userId, userId.toString());
        log.info("Wallet created with initial credit userId={}", userId);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CreditOutcome reserve(UUID reservationId, UUID userId, long amountVnd) {
        Timestamp now = Timestamp.from(clock.instant());
        int claimed = jdbc.update("""
                INSERT INTO credit_reservation (id, user_id, wallet_id, amount_vnd, status, created_at, updated_at)
                VALUES (?, ?, (SELECT id FROM wallet WHERE user_id = ?), ?, 'RESERVED', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, reservationId, userId, userId, Math.max(amountVnd, 0), now, now);
        Map<String, Object> reservation = lockReservation(reservationId).orElseThrow();
        if (claimed == 0) {
            return replayReserve(reservation, userId, amountVnd);
        }
        if (amountVnd <= 0) {
            return reject(reservationId, amountVnd, "INVALID_AMOUNT");
        }
        UUID walletId = (UUID) reservation.get("wallet_id");
        if (walletId == null) {
            // Trạng thái tạm thời: user vừa đăng ký nhưng UserRegistered chưa được xử lý xong.
            return reject(reservationId, amountVnd, "WALLET_NOT_FOUND");
        }
        List<Map<String, Object>> updated = jdbc.queryForList("""
                UPDATE wallet SET reserved_vnd = reserved_vnd + ?, updated_at = ?, version = version + 1
                WHERE id = ? AND balance_vnd - reserved_vnd >= ?
                RETURNING balance_vnd, reserved_vnd
                """, amountVnd, now, walletId, amountVnd);
        if (updated.isEmpty()) {
            return reject(reservationId, amountVnd, "INSUFFICIENT_FUNDS");
        }
        appendLedger(walletId, "RESERVE", amountVnd, (Long) updated.getFirst().get("balance_vnd"),
                (Long) updated.getFirst().get("reserved_vnd"), "reserve:" + reservationId, reservationId.toString());
        log.info("Credit reserved reservationId={} amountVnd={}", reservationId, amountVnd);
        return new CreditOutcome(RESERVED, reservationId, "RESERVED", null, amountVnd, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CreditOutcome capture(UUID reservationId) {
        Optional<Map<String, Object>> found = lockReservation(reservationId);
        if (found.isEmpty()) {
            return new CreditOutcome(CAPTURE_REJECTED, reservationId, null, "RESERVATION_NOT_FOUND", 0, false);
        }
        Map<String, Object> reservation = found.get();
        String status = (String) reservation.get("status");
        long amount = (Long) reservation.get("amount_vnd");
        switch (status) {
            case "RESERVED" -> {
                Timestamp now = Timestamp.from(clock.instant());
                UUID walletId = (UUID) reservation.get("wallet_id");
                Map<String, Object> wallet = jdbc.queryForList("""
                        UPDATE wallet SET balance_vnd = balance_vnd - ?, reserved_vnd = reserved_vnd - ?,
                                          updated_at = ?, version = version + 1
                        WHERE id = ? AND reserved_vnd >= ?
                        RETURNING balance_vnd, reserved_vnd
                        """, amount, amount, now, walletId, amount).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("Wallet invariant violated for " + reservationId));
                updateReservationStatus(reservationId, "CAPTURED", null, now);
                appendLedger(walletId, "CAPTURE", amount, (Long) wallet.get("balance_vnd"),
                        (Long) wallet.get("reserved_vnd"), "capture:" + reservationId, reservationId.toString());
                log.info("Credit captured reservationId={} amountVnd={}", reservationId, amount);
                return new CreditOutcome(CAPTURED, reservationId, "CAPTURED", null, amount, true);
            }
            // Capture lặp lại (Saga gửi lại sau timeout, hoặc reply trước đó bị mất) → trả lời giống hệt, không trừ lần 2.
            case "CAPTURED" -> {
                return new CreditOutcome(CAPTURED, reservationId, "CAPTURED", null, amount, false);
            }
            default -> {
                return new CreditOutcome(CAPTURE_REJECTED, reservationId, status, "INVALID_STATE:" + status, amount,
                        false);
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CreditOutcome release(UUID reservationId, UUID userId, String reason) {
        Timestamp now = Timestamp.from(clock.instant());
        int tombstone = jdbc.update("""
                INSERT INTO credit_reservation (id, user_id, wallet_id, amount_vnd, status, reason, created_at, updated_at)
                VALUES (?, ?, NULL, 0, 'RELEASED', 'RELEASED_BEFORE_RESERVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, reservationId, userId, now, now);
        if (tombstone == 1) {
            log.info("Release arrived before reserve; tombstone created reservationId={}", reservationId);
            return new CreditOutcome(RELEASED, reservationId, "RELEASED", "RELEASED_BEFORE_RESERVE", 0, true);
        }
        Map<String, Object> reservation = lockReservation(reservationId).orElseThrow();
        String status = (String) reservation.get("status");
        long amount = (Long) reservation.get("amount_vnd");
        return switch (status) {
            case "RESERVED" -> {
                UUID walletId = (UUID) reservation.get("wallet_id");
                Map<String, Object> wallet = jdbc.queryForList("""
                        UPDATE wallet SET reserved_vnd = reserved_vnd - ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND reserved_vnd >= ?
                        RETURNING balance_vnd, reserved_vnd
                        """, amount, now, walletId, amount).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("Wallet invariant violated for " + reservationId));
                updateReservationStatus(reservationId, "RELEASED", reason, now);
                appendLedger(walletId, "RELEASE", amount, (Long) wallet.get("balance_vnd"),
                        (Long) wallet.get("reserved_vnd"), "release:" + reservationId, reservationId.toString());
                log.info("Credit released reservationId={} amountVnd={}", reservationId, amount);
                yield new CreditOutcome(RELEASED, reservationId, "RELEASED", reason, amount, true);
            }
            case "RELEASED", "REJECTED" -> new CreditOutcome(RELEASED, reservationId, status,
                    (String) reservation.get("reason"), amount, false);
            // Đã capture thì không "release" được — cần nghiệp vụ hoàn tiền (refund), ngoài phạm vi; xem failure-scenarios.
            default -> new CreditOutcome(RELEASE_REJECTED, reservationId, status, "ALREADY_CAPTURED", amount, false);
        };
    }

    public Optional<WalletView> findWallet(UUID userId) {
        return jdbc.query("""
                SELECT w.user_id, w.balance_vnd, w.reserved_vnd, w.updated_at,
                       COALESCE((SELECT sum(amount_vnd) FROM wallet_ledger l
                                 WHERE l.wallet_id = w.id AND l.entry_type = 'CAPTURE'), 0) AS captured
                FROM wallet w WHERE w.user_id = ?
                """, (rs, i) -> new WalletView(rs.getObject("user_id", UUID.class), rs.getLong("balance_vnd"),
                rs.getLong("reserved_vnd"), rs.getLong("balance_vnd") - rs.getLong("reserved_vnd"),
                rs.getLong("captured"), rs.getTimestamp("updated_at").toInstant()), userId).stream().findFirst();
    }

    public List<LedgerEntry> ledger(UUID userId, int limit) {
        return jdbc.query("""
                SELECT l.id, l.entry_type, l.amount_vnd, l.balance_after_vnd, l.reserved_after_vnd,
                       l.reference_id, l.created_at
                FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id
                WHERE w.user_id = ? ORDER BY l.created_at DESC, l.id LIMIT ?
                """, (rs, i) -> new LedgerEntry(rs.getObject("id", UUID.class), rs.getString("entry_type"),
                rs.getLong("amount_vnd"), rs.getLong("balance_after_vnd"), rs.getLong("reserved_after_vnd"),
                rs.getString("reference_id"), rs.getTimestamp("created_at").toInstant()), userId, limit);
    }

    public Optional<Map<String, Object>> findReservation(UUID reservationId) {
        return jdbc.queryForList("SELECT id, user_id, amount_vnd, status, reason, updated_at FROM credit_reservation "
                + "WHERE id = ?", reservationId).stream().findFirst();
    }

    private CreditOutcome replayReserve(Map<String, Object> existing, UUID userId, long amountVnd) {
        UUID id = (UUID) existing.get("id");
        String status = (String) existing.get("status");
        long amount = (Long) existing.get("amount_vnd");
        if (!userId.equals(existing.get("user_id")) || ("RESERVED".equals(status) && amount != amountVnd)) {
            // Cùng khóa idempotency nhưng nội dung khác → bất thường, không áp dụng; ghi log để điều tra.
            log.warn("Reserve replay with different parameters reservationId={}", id);
        }
        return switch (status) {
            case "RESERVED", "CAPTURED" -> new CreditOutcome(RESERVED, id, status, null, amount, false);
            case "REJECTED" -> new CreditOutcome(RESERVATION_REJECTED, id, status, (String) existing.get("reason"),
                    amount, false);
            default -> new CreditOutcome(RESERVATION_REJECTED, id, status, "RESERVATION_RELEASED", amount, false);
        };
    }

    private CreditOutcome reject(UUID reservationId, long amountVnd, String reason) {
        updateReservationStatus(reservationId, "REJECTED", reason, Timestamp.from(clock.instant()));
        log.info("Credit reservation rejected reservationId={} reason={}", reservationId, reason);
        return new CreditOutcome(RESERVATION_REJECTED, reservationId, "REJECTED", reason, amountVnd, true);
    }

    private Optional<Map<String, Object>> lockReservation(UUID reservationId) {
        return jdbc.queryForList("""
                SELECT id, user_id, wallet_id, amount_vnd, status, reason
                FROM credit_reservation WHERE id = ? FOR UPDATE
                """, reservationId).stream().findFirst();
    }

    private void updateReservationStatus(UUID reservationId, String status, String reason, Timestamp now) {
        jdbc.update("UPDATE credit_reservation SET status = ?, reason = ?, updated_at = ? WHERE id = ?",
                status, reason, now, reservationId);
    }

    private void appendLedger(UUID walletId, String type, long amount, long balanceAfter, long reservedAfter,
                              String idempotencyKey, String referenceId) {
        jdbc.update("""
                        INSERT INTO wallet_ledger (id, wallet_id, entry_type, amount_vnd, balance_after_vnd,
                                                   reserved_after_vnd, idempotency_key, reference_id, correlation_id,
                                                   created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), walletId, type, amount, balanceAfter, reservedAfter, idempotencyKey,
                referenceId, CorrelationIds.current(), Timestamp.from(clock.instant()));
    }

    public record WalletView(UUID userId, long balanceVnd, long reservedVnd, long availableVnd,
                             long totalCapturedVnd, Instant updatedAt) {
    }

    public record LedgerEntry(UUID id, String type, long amountVnd, long balanceAfterVnd, long reservedAfterVnd,
                              String referenceId, Instant createdAt) {
    }
}
