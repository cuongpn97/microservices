package com.webshop.order.scheduler;

import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.enums.SagaState;
import com.webshop.order.repository.CheckoutSagaRepository;
import com.webshop.order.service.CheckoutSagaOrchestrator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quét Saga quá hạn chờ reply và kích hoạt {@link CheckoutSaga#onTimeout} — vai trò giống Controller/Listener
 * nhưng tác nhân là thời gian thay vì HTTP/message.
 * <p>
 * Đây cũng là cơ chế <b>phục hồi sau restart</b>: mọi Saga dở dang nằm trong DB với deadline; sau khi Order Service
 * khởi động lại, watcher tiếp tục chúng. Reply đến trong lúc service down vẫn nằm trong queue durable và được xử lý sau.
 * <p>
 * Nhiều instance chạy song song an toàn nhờ {@code FOR UPDATE SKIP LOCKED} (xem CheckoutSagaRepository.lockDueIds).
 */
@Slf4j
@Component
public class SagaTimeoutWatcher {

    private static final Set<SagaState> ACTIVE_STATES = EnumSet.of(
            SagaState.PENDING, SagaState.INVENTORY_RESERVED, SagaState.CONFIRMING, SagaState.COMPENSATING);

    private final TransactionTemplate transactionTemplate;
    private final CheckoutSagaRepository checkoutSagaRepository;
    private final CheckoutSagaOrchestrator checkoutSagaOrchestrator;
    private final Clock clock;
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong needingAttention = new AtomicLong();

    // Viết tay vì cần đăng ký Gauge lúc khởi tạo.
    public SagaTimeoutWatcher(TransactionTemplate transactionTemplate, CheckoutSagaRepository checkoutSagaRepository,
                              CheckoutSagaOrchestrator checkoutSagaOrchestrator, Clock clock, MeterRegistry meters) {
        this.transactionTemplate = transactionTemplate;
        this.checkoutSagaRepository = checkoutSagaRepository;
        this.checkoutSagaOrchestrator = checkoutSagaOrchestrator;
        this.clock = clock;
        Gauge.builder("webshop.saga.in-flight", inFlight, AtomicLong::get)
                .description("Saga chưa kết thúc").register(meters);
        Gauge.builder("webshop.saga.needing-attention", needingAttention, AtomicLong::get)
                .description("Saga cần can thiệp thủ công").register(meters);
    }

    /** Xử lý một lô Saga quá hạn trong MỘT transaction (khóa giữ tới khi commit). Trả về số Saga đã xử lý. */
    public int processDueSagas() {
        Integer processed = transactionTemplate.execute(status -> {
            List<UUID> due = checkoutSagaRepository.lockDueIds(clock.instant());
            for (UUID id : due) {
                checkoutSagaRepository.findById(id).ifPresent(checkoutSagaOrchestrator::onTimeout);
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }

    public void refreshGauges() {
        inFlight.set(checkoutSagaRepository.countByStateIn(ACTIVE_STATES));
        needingAttention.set(checkoutSagaRepository.countByNeedsAttentionTrue());
    }

    /**
     * Tách lịch chạy ra class riêng để test có thể tắt (webshop.saga.watcher-enabled=false) và gọi
     * processDueSagas() thủ công, kiểm soát được thời điểm timeout.
     */
    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(prefix = "webshop.saga", name = "watcher-enabled", matchIfMissing = true)
    static class Scheduler {

        private final SagaTimeoutWatcher watcher;

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
