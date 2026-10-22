package com.webshop.messaging.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/** Kích hoạt OutboxPublisher định kỳ. Tắt bằng webshop.messaging.outbox.scheduling-enabled=false (dùng trong test). */
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisher publisher;
    private final Clock clock;

    public OutboxScheduler(OutboxPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${webshop.messaging.outbox.poll-interval-ms:500}")
    public void poll() {
        try {
            int sent;
            do {
                sent = publisher.publishBatch();
            } while (sent > 0 && !Thread.currentThread().isInterrupted());
        } catch (Exception e) {
            // Ví dụ DB down: không để exception giết scheduler thread, lần poll sau sẽ thử lại.
            log.warn("Outbox poll failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void refreshBacklog() {
        try {
            publisher.refreshBacklog();
        } catch (Exception e) {
            log.debug("Cannot refresh outbox backlog: {}", e.toString());
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purge() {
        int deleted = publisher.purgePublishedBefore(clock.instant().minus(Duration.ofDays(7)));
        if (deleted > 0) {
            log.info("Purged {} published outbox events", deleted);
        }
    }
}
