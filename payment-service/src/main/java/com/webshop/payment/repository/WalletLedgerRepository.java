package com.webshop.payment.repository;

import com.webshop.payment.entity.LedgerEntry;
import com.webshop.payment.enums.LedgerEntryType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SQL của bảng wallet_ledger — sổ cái append-only: mỗi thay đổi số dư ghi một dòng, trigger DB cấm UPDATE/DELETE.
 * idempotency_key unique → cùng một thao tác không thể ghi sổ hai lần.
 */
@Repository
@RequiredArgsConstructor
public class WalletLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public void append(UUID walletId, LedgerEntryType type, long amountVnd, long balanceAfterVnd,
                       long reservedAfterVnd, String idempotencyKey, String referenceId, String correlationId,
                       Instant now) {
        jdbcTemplate.update("""
                        INSERT INTO wallet_ledger (id, wallet_id, entry_type, amount_vnd, balance_after_vnd,
                                                   reserved_after_vnd, idempotency_key, reference_id, correlation_id,
                                                   created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), walletId, type.name(), amountVnd, balanceAfterVnd, reservedAfterVnd,
                idempotencyKey, referenceId, correlationId, Timestamp.from(now));
    }

    /** Mới nhất trước. */
    public List<LedgerEntry> findByUserId(UUID userId, int limit) {
        return jdbcTemplate.query("""
                SELECT l.id, l.entry_type, l.amount_vnd, l.balance_after_vnd, l.reserved_after_vnd,
                       l.reference_id, l.created_at
                FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id
                WHERE w.user_id = ? ORDER BY l.created_at DESC, l.id LIMIT ?
                """, (rs, i) -> new LedgerEntry(rs.getObject("id", UUID.class),
                LedgerEntryType.valueOf(rs.getString("entry_type")), rs.getLong("amount_vnd"),
                rs.getLong("balance_after_vnd"), rs.getLong("reserved_after_vnd"), rs.getString("reference_id"),
                rs.getTimestamp("created_at").toInstant()), userId, limit);
    }
}
