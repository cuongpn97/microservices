package com.webshop.payment.repository;

import com.webshop.payment.entity.WalletBalance;
import com.webshop.payment.entity.WalletSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL của bảng wallet. Viết tay bằng JdbcTemplate (không dùng JPA) vì với tiền cần thấy rõ từng câu lệnh:
 * mọi thay đổi số dư là MỘT câu UPDATE có điều kiện — kiểm tra và ghi trong cùng một lệnh,
 * không có khoảng hở "đọc rồi mới ghi" giữa hai request đồng thời.
 * <p>
 * Các method ghi trả Optional.empty() khi điều kiện WHERE không thỏa (không đủ tiền / bất biến bị vi phạm).
 */
@Repository
@RequiredArgsConstructor
public class WalletRepository {

    private static final RowMapper<WalletBalance> BALANCE = (rs, i) ->
            new WalletBalance(rs.getLong("balance_vnd"), rs.getLong("reserved_vnd"));

    private final JdbcTemplate jdbcTemplate;

    /** @return true nếu lần này thực sự tạo ví; false nếu user đã có ví (unique user_id). */
    public boolean insertIfAbsent(UUID walletId, UUID userId, long balanceVnd, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO wallet (id, user_id, balance_vnd, reserved_vnd, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, walletId, userId, balanceVnd, Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /** Giữ tiền: chỉ thành công nếu available (balance - reserved) đủ. */
    public Optional<WalletBalance> reserve(UUID walletId, long amountVnd, Instant now) {
        return jdbcTemplate.query("""
                UPDATE wallet SET reserved_vnd = reserved_vnd + ?, updated_at = ?, version = version + 1
                WHERE id = ? AND balance_vnd - reserved_vnd >= ?
                RETURNING balance_vnd, reserved_vnd
                """, BALANCE, amountVnd, Timestamp.from(now), walletId, amountVnd).stream().findFirst();
    }

    /** Trừ tiền thật: giảm cả balance và reserved. */
    public Optional<WalletBalance> capture(UUID walletId, long amountVnd, Instant now) {
        return jdbcTemplate.query("""
                UPDATE wallet SET balance_vnd = balance_vnd - ?, reserved_vnd = reserved_vnd - ?,
                                  updated_at = ?, version = version + 1
                WHERE id = ? AND reserved_vnd >= ?
                RETURNING balance_vnd, reserved_vnd
                """, BALANCE, amountVnd, amountVnd, Timestamp.from(now), walletId, amountVnd).stream().findFirst();
    }

    /** Trả lại tiền đang giữ: chỉ giảm reserved. */
    public Optional<WalletBalance> releaseReserved(UUID walletId, long amountVnd, Instant now) {
        return jdbcTemplate.query("""
                UPDATE wallet SET reserved_vnd = reserved_vnd - ?, updated_at = ?, version = version + 1
                WHERE id = ? AND reserved_vnd >= ?
                RETURNING balance_vnd, reserved_vnd
                """, BALANCE, amountVnd, Timestamp.from(now), walletId, amountVnd).stream().findFirst();
    }

    public Optional<WalletSummary> findSummaryByUserId(UUID userId) {
        return jdbcTemplate.query("""
                SELECT w.user_id, w.balance_vnd, w.reserved_vnd, w.updated_at,
                       COALESCE((SELECT sum(amount_vnd) FROM wallet_ledger l
                                 WHERE l.wallet_id = w.id AND l.entry_type = 'CAPTURE'), 0) AS captured
                FROM wallet w WHERE w.user_id = ?
                """, (rs, i) -> new WalletSummary(rs.getObject("user_id", UUID.class), rs.getLong("balance_vnd"),
                rs.getLong("reserved_vnd"), rs.getLong("captured"), rs.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }
}
