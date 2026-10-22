package com.webshop.messaging.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Inbox (processed_message): bảng dedup phía consumer.
 * <p>
 * INSERT ... ON CONFLICT DO NOTHING chạy trong CÙNG transaction với hiệu ứng nghiệp vụ:
 * <ul>
 *     <li>Nếu xử lý nghiệp vụ lỗi → rollback cả dòng inbox → lần giao lại sẽ xử lý lại.</li>
 *     <li>Nếu commit thành công nhưng ack thất bại → lần giao lại thấy dòng inbox → bỏ qua (không lặp hiệu ứng).</li>
 *     <li>Hai consumer cùng nhận bản sao đồng thời → bản thứ hai bị chặn trên khóa của primary key cho tới khi
 *     bản thứ nhất commit, rồi ON CONFLICT trả 0 dòng → bỏ qua.</li>
 * </ul>
 */
public class InboxStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InboxStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true nếu message CHƯA từng được xử lý bởi consumer này (và giờ đã được ghi nhận). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID messageId) {
        int inserted = jdbc.update("""
                INSERT INTO processed_message (consumer, message_id, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (consumer, message_id) DO NOTHING
                """, consumer, messageId, Timestamp.from(clock.instant()));
        return inserted == 1;
    }
}
