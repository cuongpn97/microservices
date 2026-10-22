package com.webshop.inventory;

import com.webshop.inventory.application.InventoryService;
import com.webshop.inventory.application.InventoryService.LineRequest;
import com.webshop.inventory.application.ReservationOutcome;
import com.webshop.inventory.domain.InventoryItem;
import com.webshop.inventory.domain.InventoryItemRepository;
import com.webshop.inventory.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryReservationIT extends AbstractInventoryIT {

    @Autowired
    InventoryService inventory;
    @Autowired
    InventoryItemRepository items;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentReservationsCannotOversell() throws Exception {
        UUID product = newProduct(10);
        int buyers = 30;
        List<ReservationOutcome> outcomes = runConcurrently(buyers, i ->
                inventory.reserve(UUID.randomUUID(), List.of(new LineRequest(product, 1))));

        long reserved = outcomes.stream().filter(o -> o.status() == ReservationStatus.RESERVED).count();
        long rejected = outcomes.stream().filter(o -> o.status() == ReservationStatus.REJECTED).count();
        assertThat(reserved).isEqualTo(10);
        assertThat(rejected).isEqualTo(20);
        InventoryItem item = items.findById(product).orElseThrow();
        assertThat(item.getReservedQuantity()).isEqualTo(10);
        assertThat(item.available()).isZero();
        assertThat(item.getStockOnHand()).isEqualTo(10);
    }

    @Test
    void concurrentMultiProductReservationsInOppositeOrderDoNotDeadlockOrOversell() throws Exception {
        UUID a = newProduct(5);
        UUID b = newProduct(5);
        List<ReservationOutcome> outcomes = runConcurrently(20, i -> inventory.reserve(UUID.randomUUID(),
                i % 2 == 0
                        ? List.of(new LineRequest(a, 1), new LineRequest(b, 1))
                        : List.of(new LineRequest(b, 1), new LineRequest(a, 1))));

        assertThat(outcomes.stream().filter(o -> o.status() == ReservationStatus.RESERVED).count()).isEqualTo(5);
        assertThat(items.findById(a).orElseThrow().available()).isZero();
        assertThat(items.findById(b).orElseThrow().available()).isZero();
    }

    @Test
    void duplicateReserveCommandsForSameReservationReserveOnlyOnce() throws Exception {
        UUID product = newProduct(10);
        UUID reservationId = UUID.randomUUID();
        List<ReservationOutcome> outcomes = runConcurrently(8, i ->
                inventory.reserve(reservationId, List.of(new LineRequest(product, 3))));

        assertThat(outcomes).allMatch(o -> o.replyType().equals(ReservationOutcome.RESERVED));
        assertThat(outcomes.stream().filter(ReservationOutcome::applied).count()).isEqualTo(1);
        assertThat(items.findById(product).orElseThrow().getReservedQuantity()).isEqualTo(3);
    }

    @Test
    void insufficientStockRejectsWholeReservationAtomically() {
        UUID a = newProduct(5);
        UUID b = newProduct(1);
        ReservationOutcome outcome = tx.execute(s -> inventory.reserve(UUID.randomUUID(),
                List.of(new LineRequest(a, 2), new LineRequest(b, 2))));

        assertThat(outcome.replyType()).isEqualTo(ReservationOutcome.RESERVATION_REJECTED);
        assertThat(outcome.reason()).startsWith("INSUFFICIENT_STOCK");
        assertThat(items.findById(a).orElseThrow().getReservedQuantity()).as("all-or-nothing").isZero();
    }

    @Test
    void commitIsIdempotentAndReleaseAfterCommitIsRefused() {
        UUID product = newProduct(5);
        UUID reservationId = UUID.randomUUID();
        tx.execute(s -> inventory.reserve(reservationId, List.of(new LineRequest(product, 2))));

        ReservationOutcome first = tx.execute(s -> inventory.commit(reservationId));
        ReservationOutcome second = tx.execute(s -> inventory.commit(reservationId));
        assertThat(first.applied()).isTrue();
        assertThat(second.applied()).isFalse();
        assertThat(second.replyType()).isEqualTo(ReservationOutcome.COMMITTED);
        InventoryItem item = items.findById(product).orElseThrow();
        assertThat(item.getStockOnHand()).isEqualTo(3);
        assertThat(item.getReservedQuantity()).isZero();

        ReservationOutcome release = tx.execute(s -> inventory.release(reservationId, "TEST"));
        assertThat(release.replyType()).isEqualTo(ReservationOutcome.RELEASE_REJECTED);
        assertThat(items.findById(product).orElseThrow().getStockOnHand()).isEqualTo(3);
    }

    @Test
    void repeatedReleaseDoesNotDoubleRelease() {
        UUID product = newProduct(5);
        UUID other = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        tx.execute(s -> inventory.reserve(other, List.of(new LineRequest(product, 1))));
        tx.execute(s -> inventory.reserve(reservationId, List.of(new LineRequest(product, 2))));

        tx.execute(s -> inventory.release(reservationId, "PAYMENT_FAILED"));
        ReservationOutcome again = tx.execute(s -> inventory.release(reservationId, "PAYMENT_FAILED"));

        assertThat(again.applied()).isFalse();
        assertThat(again.replyType()).isEqualTo(ReservationOutcome.RELEASED);
        // reservation khác (1 cái) vẫn được giữ nguyên.
        assertThat(items.findById(product).orElseThrow().getReservedQuantity()).isEqualTo(1);
    }

    @Test
    void releaseBeforeReserveLeavesTombstoneSoLateReserveIsRejected() {
        UUID product = newProduct(5);
        UUID reservationId = UUID.randomUUID();

        ReservationOutcome release = tx.execute(s -> inventory.release(reservationId, "SAGA_TIMEOUT"));
        ReservationOutcome lateReserve = tx.execute(s -> inventory.reserve(reservationId,
                List.of(new LineRequest(product, 2))));

        assertThat(release.replyType()).isEqualTo(ReservationOutcome.RELEASED);
        assertThat(lateReserve.replyType()).isEqualTo(ReservationOutcome.RESERVATION_REJECTED);
        assertThat(lateReserve.reason()).isEqualTo("RESERVATION_RELEASED");
        assertThat(items.findById(product).orElseThrow().getReservedQuantity()).isZero();
    }

    @Test
    void databaseConstraintIsTheLastLineOfDefenceAgainstOverselling() {
        UUID product = newProduct(1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory_item SET reserved_quantity = 2 WHERE product_id = ?", product))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory_item SET stock_on_hand = -1 WHERE product_id = ?", product))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID newProduct(int stock) {
        UUID id = UUID.randomUUID();
        inventory.setStock(id, stock);
        return id;
    }

    private interface Op {
        ReservationOutcome run(int index);
    }

    private List<ReservationOutcome> runConcurrently(int threads, Op op) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                tasks.add(() -> {
                    start.await();
                    return tx.execute(s -> op.run(index));
                });
            }
            List<Future<ReservationOutcome>> futures = new ArrayList<>();
            for (Callable<ReservationOutcome> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<ReservationOutcome> results = new ArrayList<>();
            for (Future<ReservationOutcome> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
