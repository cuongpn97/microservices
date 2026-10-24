package com.webshop.payment.repository;

import com.webshop.payment.entity.CreditReservation;
import com.webshop.payment.enums.CreditReservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL của bảng credit_reservation. id = orderId là khóa idempotency nghiệp vụ:
 * INSERT ... ON CONFLICT DO NOTHING "claim" id một cách nguyên tử — lệnh trùng chạy song song nhận 0 dòng.
 */
@Repository
@RequiredArgsConstructor
public class CreditReservationRepository {

    private static final RowMapper<CreditReservation> ROW = (rs, i) -> new CreditReservation(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("wallet_id", UUID.class),
            rs.getLong("amount_vnd"),
            CreditReservationStatus.valueOf(rs.getString("status")),
            rs.getString("reason"),
            rs.getTimestamp("updated_at").toInstant());

    private static final String COLUMNS = "id, user_id, wallet_id, amount_vnd, status, reason, updated_at";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Tạo reservation RESERVED, wallet_id lấy từ ví của user (NULL nếu user chưa có ví).
     *
     * @return true nếu lần này thực sự tạo; false nếu id đã tồn tại (lệnh trùng).
     */
    public boolean insertReservedIfAbsent(UUID id, UUID userId, long amountVnd, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO credit_reservation (id, user_id, wallet_id, amount_vnd, status, created_at, updated_at)
                VALUES (?, ?, (SELECT id FROM wallet WHERE user_id = ?), ?, 'RESERVED', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, userId, userId, amountVnd, Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /**
     * "Tombstone": release đến TRƯỚC reserve vẫn tạo bản ghi RELEASED, nhờ đó reserve đến muộn bị từ chối.
     *
     * @return true nếu lần này tạo tombstone; false nếu reservation đã tồn tại.
     */
    public boolean insertReleasedTombstoneIfAbsent(UUID id, UUID userId, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO credit_reservation (id, user_id, wallet_id, amount_vnd, status, reason, created_at, updated_at)
                VALUES (?, ?, NULL, 0, 'RELEASED', 'RELEASED_BEFORE_RESERVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, userId, Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /** SELECT ... FOR UPDATE: giữ khóa dòng tới hết transaction, chặn lệnh khác cùng id chạy xen vào. */
    public Optional<CreditReservation> lockById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM credit_reservation WHERE id = ? FOR UPDATE",
                ROW, id).stream().findFirst();
    }

    public Optional<CreditReservation> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM credit_reservation WHERE id = ?", ROW, id)
                .stream().findFirst();
    }

    public void updateStatus(UUID id, CreditReservationStatus status, String reason, Instant now) {
        jdbcTemplate.update("UPDATE credit_reservation SET status = ?, reason = ?, updated_at = ? WHERE id = ?",
                status.name(), reason, Timestamp.from(now), id);
    }
}
