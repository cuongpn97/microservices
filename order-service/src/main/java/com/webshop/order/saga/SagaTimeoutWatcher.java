package com.webshop.order.saga;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quét Saga quá hạn chờ reply và kích hoạt {@link CheckoutSaga#onTimeout}.
 * <p>
 * Đây cũng là cơ chế <b>phục hồi sau restart</b>: mọi Saga dở dang nằm trong DB với deadline; sau khi Order Service
 * khởi động lại, watcher tiếp tục chúng. Reply đến trong lúc service down vẫn nằm trong queue durable và được xử lý sau.
 * <p>
 * Nhiều instance chạy song song an toàn nhờ {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
public class SagaTimeoutWatcher {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutWatcher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CheckoutSagaRepository sagas;
    private final CheckoutSagaOrchestrator orchestrator;
    private final Clock clock;
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong needingAttention = new AtomicLong();

    public SagaTimeoutWatcher(JdbcTemplate jdbc, TransactionTemplate tx, CheckoutSagaRepository sagas,
                              CheckoutSagaOrchestrator orchestrator, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sagas = sagas;
        this.orchestrator = orchestrator;
        this.clock = clock;
        Gauge.builder("webshop.saga.in-flight", inFlight, AtomicLong::get)
                .description("Saga chưa kết thúc").register(meters);
        Gauge.builder("webshop.saga.needing-attention", needingAttention, AtomicLong::get)
                .description("Saga cần can thiệp thủ công").register(meters);
    }

    /** Xử lý một lô Saga quá hạn. Trả về số Saga đã xử lý. */
    public int processDueSagas() {
        Integer processed = tx.execute(status -> {
            List<UUID> due = jdbc.queryForList("""
                    SELECT id FROM checkout_saga
                    WHERE state IN ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMING', 'COMPENSATING')
                      AND deadline_at <= ?
                    ORDER BY deadline_at
                    LIMIT 20
                    FOR UPDATE SKIP LOCKED
                    """, UUID.class, Timestamp.from(clock.instant()));
            for (UUID id : due) {
                sagas.findById(id).ifPresent(orchestrator::onTimeout);
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }

    public void refreshGauges() {
        Long active = jdbc.queryForObject("SELECT count(*) FROM checkout_saga WHERE state IN "
                + "('PENDING', 'INVENTORY_RESERVED', 'CONFIRMING', 'COMPENSATING')", Long.class);
        Long attention = jdbc.queryForObject("SELECT count(*) FROM checkout_saga WHERE needs_attention", Long.class);
        inFlight.set(active == null ? 0 : active);
        needingAttention.set(attention == null ? 0 : attention);
    }

    @Component
    @ConditionalOnProperty(prefix = "webshop.saga", name = "watcher-enabled", matchIfMissing = true)
    static class Scheduler {
        private final SagaTimeoutWatcher watcher;

        Scheduler(SagaTimeoutWatcher watcher) {
            this.watcher = watcher;
        }

        @Scheduled(fixedDelayString = "${webshop.saga.watcher-interval-ms:2000}")
        void run() {
            try {
                int n;
                do {
                    n = watcher.processDueSagas();
                } while (n > 0);
                watcher.refreshGauges();
            } catch (Exception e) {
                log.warn("Saga watcher run failed: {}", e.toString());
            }
        }
    }
}
