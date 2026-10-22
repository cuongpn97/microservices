package com.webshop.messaging.outbox;

import com.webshop.messaging.MessagingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polling publisher của Transactional Outbox.
 *
 * <h3>Claim an toàn khi chạy nhiều instance</h3>
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}: mỗi instance khóa một lô dòng khác nhau; dòng đang bị instance khác
 * khóa sẽ bị bỏ qua thay vì chờ. Khóa được giữ trong suốt transaction publish → không có 2 instance cùng gửi một dòng
 * (trừ khi transaction bị rollback sau khi đã gửi — xem dưới).
 *
 * <h3>Chỉ đánh dấu published SAU khi broker confirm</h3>
 * Nếu confirm thất bại/timeout → tăng publish_attempts, đặt next_attempt_at theo exponential backoff.
 *
 * <h3>Cửa sổ crash không tránh được</h3>
 * Broker đã confirm nhưng app chết (hoặc DB lỗi) trước khi commit UPDATE published_at → dòng vẫn "chưa publish"
 * và sẽ được gửi lại. Vì vậy hệ thống là AT-LEAST-ONCE và mọi consumer phải idempotent (Inbox + unique key nghiệp vụ).
 *
 * <h3>Thứ tự</h3>
 * Lô được lấy theo created_at, nhưng khi một dòng lỗi và được thử lại sau, các dòng mới hơn có thể đi trước.
 * Consumer KHÔNG được giả định thứ tự tuyệt đối (Saga kiểm tra state trước khi áp dụng reply).
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final String CLAIM_SQL = """
            SELECT id, exchange, routing_key, event_type, payload::text AS payload, correlation_id, saga_id,
                   publish_attempts
            FROM outbox_event
            WHERE published_at IS NULL AND next_attempt_at <= ?
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final RowMapper<OutboxRecord> MAPPER = (rs, i) -> new OutboxRecord(
            rs.getObject("id", UUID.class),
            rs.getString("exchange"),
            rs.getString("routing_key"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getString("correlation_id"),
            rs.getString("saga_id"),
            rs.getInt("publish_attempts"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxSender sender;
    private final MessagingProperties.Outbox config;
    private final Clock clock;
    private final AtomicLong backlog = new AtomicLong();
    private final Counter published;
    private final Counter failures;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, OutboxSender sender,
                           MessagingProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sender = sender;
        this.config = properties.outbox();
        this.clock = clock;
        this.published = Counter.builder("webshop.outbox.published").register(meterRegistry);
        this.failures = Counter.builder("webshop.outbox.publish.failures").register(meterRegistry);
        Gauge.builder("webshop.outbox.backlog", backlog, AtomicLong::get)
                .description("Số event outbox chưa publish")
                .register(meterRegistry);
    }

    /** Publish một lô. Trả về số event đã được broker confirm. */
    public int publishBatch() {
        Integer count = tx.execute(status -> {
            List<OutboxRecord> batch = jdbc.query(CLAIM_SQL, MAPPER, Timestamp.from(clock.instant()),
                    config.batchSize());
            int ok = 0;
            for (OutboxRecord record : batch) {
                try {
                    sender.sendAndConfirm(record);
                    jdbc.update("UPDATE outbox_event SET published_at = ?, publish_attempts = publish_attempts + 1, "
                            + "last_error = NULL WHERE id = ?", Timestamp.from(clock.instant()), record.id());
                    published.increment();
                    ok++;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    markFailed(record, e);
                }
            }
            return ok;
        });
        return count != null ? count : 0;
    }

    private void markFailed(OutboxRecord record, Exception e) {
        failures.increment();
        int attempts = record.publishAttempts() + 1;
        Duration backoff = backoff(attempts);
        log.warn("Outbox publish failed eventId={} type={} attempt={} nextRetryIn={}ms reason={}",
                record.id(), record.eventType(), attempts, backoff.toMillis(), e.toString());
        String error = e.toString();
        jdbc.update("UPDATE outbox_event SET publish_attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                attempts, Timestamp.from(clock.instant().plus(backoff)),
                error.length() > 1000 ? error.substring(0, 1000) : error, record.id());
    }

    Duration backoff(int attempts) {
        long base = config.initialBackoff().toMillis();
        double millis = base * Math.pow(2, Math.min(attempts - 1, 20));
        return Duration.ofMillis((long) Math.min(millis, config.maxBackoff().toMillis()));
    }

    public long refreshBacklog() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        backlog.set(value != null ? value : 0);
        return backlog.get();
    }

    /** Xóa event đã publish quá thời hạn lưu giữ, tránh bảng phình vô hạn. */
    public int purgePublishedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM outbox_event WHERE published_at IS NOT NULL AND published_at < ?",
                Timestamp.from(cutoff));
    }
}
