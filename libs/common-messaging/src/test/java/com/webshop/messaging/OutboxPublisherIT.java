package com.webshop.messaging;

import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxPublisher;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.messaging.test.MutableClock;
import com.webshop.messaging.testapp.MessagingTestApplication;
import com.webshop.messaging.testapp.MessagingTestApplication.FlakySender;
import com.webshop.messaging.testapp.TestHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxPublisherIT extends AbstractMessagingIT {

    @Autowired
    OutboxWriter writer;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    FlakySender sender;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MutableClock clock;
    @Autowired
    TestHandler handler;
    @Autowired
    MeterRegistry meters;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        sender.mode(FlakySender.Mode.NORMAL);
        // Xả các event còn sót từ test khác để mỗi test bắt đầu sạch.
        while (publisher.publishBatch() > 0) {
            // drain
        }
        clock.advance(Duration.ofMinutes(5));
        while (publisher.publishBatch() > 0) {
            // drain retries
        }
        sender.resetSends();
    }

    @AfterEach
    void tearDown() {
        sender.mode(FlakySender.Mode.NORMAL);
    }

    @Test
    void writerRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> writer.append(event()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void eventIsNotPersistedWhenBusinessTransactionRollsBack() {
        UUID[] id = new UUID[1];
        try {
            tx.executeWithoutResult(status -> {
                id[0] = writer.append(event());
                throw new IllegalStateException("business failure");
            });
        } catch (IllegalStateException expected) {
            // expected
        }
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, id[0]);
        assertThat(rows).isZero();
    }

    @Test
    void failedPublishIsRecordedWithBackoffAndRecoveredLater() {
        UUID eventId = tx.execute(status -> writer.append(event()));
        sender.mode(FlakySender.Mode.FAIL_BEFORE_SEND);

        assertThat(publisher.publishBatch()).isZero();
        Map<String, Object> row = row(eventId);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("publish_attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("simulated broker unavailable");

        // Ngay lập tức: chưa tới next_attempt_at → không thử lại (backoff).
        sender.mode(FlakySender.Mode.NORMAL);
        assertThat(publisher.publishBatch()).isZero();

        // Sau backoff: publish thành công và consumer nhận được.
        clock.advance(Duration.ofSeconds(2));
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(row(eventId).get("published_at")).isNotNull();
        await().atMost(Duration.ofSeconds(10)).until(() -> handler.succeeded(eventId));
    }

    @Test
    void crashAfterBrokerConfirmCausesRedeliveryWhichConsumerDeduplicates() {
        UUID eventId = tx.execute(status -> writer.append(event()));
        double duplicatesBefore = duplicates();

        // Broker đã nhận và confirm, nhưng app "chết" trước khi ghi published_at.
        sender.mode(FlakySender.Mode.FAIL_AFTER_CONFIRM);
        publisher.publishBatch();
        assertThat(row(eventId).get("published_at")).isNull();

        // Lần poll sau gửi lại cùng eventId → consumer thấy bản trùng và bỏ qua.
        sender.mode(FlakySender.Mode.NORMAL);
        clock.advance(Duration.ofSeconds(2));
        assertThat(publisher.publishBatch()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).until(() -> duplicates() >= duplicatesBefore + 1);
        assertThat(handler.invocations(eventId)).isEqualTo(1);
        assertThat(sender.sends()).isEqualTo(2);
    }

    @Test
    void concurrentPublishersNeverSendTheSameRowTwice() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(tx.execute(status -> writer.append(event())));
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> workers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                workers.add(() -> {
                    int total = 0;
                    int sent;
                    while ((sent = publisher.publishBatch()) > 0) {
                        total += sent;
                    }
                    return total;
                });
            }
            int total = 0;
            for (Future<Integer> f : pool.invokeAll(workers)) {
                total += f.get();
            }
            assertThat(total).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
        // SKIP LOCKED đảm bảo không có dòng nào bị gửi 2 lần bởi 2 publisher.
        assertThat(sender.sends()).isEqualTo(40);
        await().atMost(Duration.ofSeconds(10)).until(() -> ids.stream().allMatch(handler::succeeded));
        ids.forEach(id -> assertThat(handler.invocations(id)).isEqualTo(1));
    }

    private OutboxEvent event() {
        return OutboxEvent.of("Test", "agg-" + UUID.randomUUID(), "TestCreated", "test.outbox", Map.of("k", "v"));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT published_at, publish_attempts, last_error FROM outbox_event WHERE id = ?", id);
    }

    private double duplicates() {
        var counter = meters.find("webshop.messaging.duplicates").tag("queue", MessagingTestApplication.QUEUE)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
