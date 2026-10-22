package com.webshop.inventory.application;

import com.webshop.common.web.ApiException;
import com.webshop.inventory.domain.InventoryItem;
import com.webshop.inventory.domain.InventoryItemRepository;
import com.webshop.inventory.domain.Reservation;
import com.webshop.inventory.domain.ReservationRepository;
import com.webshop.inventory.domain.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.webshop.inventory.application.ReservationOutcome.COMMITTED;
import static com.webshop.inventory.application.ReservationOutcome.COMMIT_REJECTED;
import static com.webshop.inventory.application.ReservationOutcome.RELEASED;
import static com.webshop.inventory.application.ReservationOutcome.RELEASE_REJECTED;
import static com.webshop.inventory.application.ReservationOutcome.RESERVATION_REJECTED;
import static com.webshop.inventory.application.ReservationOutcome.RESERVED;

/**
 * Nghiệp vụ tồn kho. Mọi thao tác reserve/commit/release:
 * <ul>
 *     <li>chạy trong transaction của caller (consumer message) — cùng transaction với Inbox và Outbox reply;</li>
 *     <li>idempotent theo reservationId (= orderId);</li>
 *     <li>khóa dòng tồn kho theo thứ tự cố định (pessimistic lock) để chống oversell và deadlock;</li>
 *     <li>được CHECK constraint trong DB bảo vệ lần cuối.</li>
 * </ul>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository items;
    private final ReservationRepository reservations;
    private final Clock clock;

    public InventoryService(InventoryItemRepository items, ReservationRepository reservations, Clock clock) {
        this.items = items;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome reserve(UUID reservationId, List<LineRequest> requested) {
        Instant now = clock.instant();
        int claimed = reservations.insertIfAbsent(reservationId, ReservationStatus.RESERVED.name(), null, now);
        Reservation reservation = reservations.lockById(reservationId).orElseThrow();
        if (claimed == 0) {
            return replayReserve(reservation);
        }

        String invalid = validate(requested);
        if (invalid != null) {
            reservation.reject(invalid, now);
            return new ReservationOutcome(RESERVATION_REJECTED, reservationId, ReservationStatus.REJECTED, invalid, true);
        }

        Map<UUID, Integer> quantities = requested.stream()
                .collect(Collectors.toMap(LineRequest::productId, LineRequest::quantity));
        Map<UUID, InventoryItem> locked = items.lockAllOrdered(quantities.keySet()).stream()
                .collect(Collectors.toMap(InventoryItem::getProductId, Function.identity()));

        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            InventoryItem item = locked.get(entry.getKey());
            if (item == null || !item.canReserve(entry.getValue())) {
                String reason = (item == null ? "UNKNOWN_PRODUCT:" : "INSUFFICIENT_STOCK:") + entry.getKey();
                reservation.reject(reason, now);
                log.info("Reservation rejected reservationId={} reason={}", reservationId, reason);
                return new ReservationOutcome(RESERVATION_REJECTED, reservationId, ReservationStatus.REJECTED,
                        reason, true);
            }
        }
        // Đã kiểm tra đủ hàng cho TẤT CẢ dòng khi đang giữ khóa → giữ chỗ toàn bộ (all-or-nothing).
        quantities.forEach((productId, qty) -> locked.get(productId).reserve(qty, now));
        reservation.markReserved(requested.stream().map(l -> new Reservation.Line(l.productId(), l.quantity()))
                .toList(), now);
        log.info("Stock reserved reservationId={} lines={}", reservationId, requested.size());
        return new ReservationOutcome(RESERVED, reservationId, ReservationStatus.RESERVED, null, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome commit(UUID reservationId) {
        Reservation reservation = reservations.lockById(reservationId).orElse(null);
        if (reservation == null) {
            return new ReservationOutcome(COMMIT_REJECTED, reservationId, null, "RESERVATION_NOT_FOUND", false);
        }
        return switch (reservation.getStatus()) {
            case RESERVED -> {
                Instant now = clock.instant();
                applyToLockedItems(reservation, (item, qty) -> item.commit(qty, now));
                reservation.commit(now);
                log.info("Reservation committed reservationId={}", reservationId);
                yield new ReservationOutcome(COMMITTED, reservationId, ReservationStatus.COMMITTED, null, true);
            }
            case COMMITTED -> new ReservationOutcome(COMMITTED, reservationId, ReservationStatus.COMMITTED, null, false);
            default -> new ReservationOutcome(COMMIT_REJECTED, reservationId, reservation.getStatus(),
                    "INVALID_STATE:" + reservation.getStatus(), false);
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome release(UUID reservationId, String reason) {
        Instant now = clock.instant();
        int tombstone = reservations.insertIfAbsent(reservationId, ReservationStatus.RELEASED.name(),
                "RELEASED_BEFORE_RESERVE", now);
        if (tombstone == 1) {
            log.info("Release arrived before reserve; tombstone created reservationId={}", reservationId);
            return new ReservationOutcome(RELEASED, reservationId, ReservationStatus.RELEASED,
                    "RELEASED_BEFORE_RESERVE", true);
        }
        Reservation reservation = reservations.lockById(reservationId).orElseThrow();
        return switch (reservation.getStatus()) {
            case RESERVED -> {
                applyToLockedItems(reservation, (item, qty) -> item.release(qty, now));
                reservation.release(reason, now);
                log.info("Reservation released reservationId={} reason={}", reservationId, reason);
                yield new ReservationOutcome(RELEASED, reservationId, ReservationStatus.RELEASED, reason, true);
            }
            // REJECTED không giữ hàng; RELEASED đã trả rồi → trả lời "đã release" mà không đổi gì (idempotent).
            case RELEASED, REJECTED -> new ReservationOutcome(RELEASED, reservationId, reservation.getStatus(),
                    reservation.getReason(), false);
            case COMMITTED -> new ReservationOutcome(RELEASE_REJECTED, reservationId, ReservationStatus.COMMITTED,
                    "ALREADY_COMMITTED", false);
        };
    }

    @Transactional
    public InventoryItem setStock(UUID productId, int stockOnHand) {
        if (stockOnHand < 0) {
            throw ApiException.badRequest("INVALID_STOCK", "stockOnHand must be >= 0");
        }
        Instant now = clock.instant();
        InventoryItem item = items.lockById(productId).orElse(null);
        if (item == null) {
            return items.save(new InventoryItem(productId, stockOnHand, now));
        }
        try {
            item.setStockOnHand(stockOnHand, now);
        } catch (IllegalStateException e) {
            throw ApiException.conflict("STOCK_BELOW_RESERVED", e.getMessage());
        }
        return item;
    }

    private ReservationOutcome replayReserve(Reservation existing) {
        return switch (existing.getStatus()) {
            case RESERVED, COMMITTED -> new ReservationOutcome(RESERVED, existing.getId(), existing.getStatus(),
                    null, false);
            case REJECTED -> new ReservationOutcome(RESERVATION_REJECTED, existing.getId(), existing.getStatus(),
                    existing.getReason(), false);
            case RELEASED -> new ReservationOutcome(RESERVATION_REJECTED, existing.getId(), existing.getStatus(),
                    "RESERVATION_RELEASED", false);
        };
    }

    private void applyToLockedItems(Reservation reservation, java.util.function.ObjIntConsumer<InventoryItem> action) {
        List<Reservation.Line> lines = reservation.getLines();
        Map<UUID, InventoryItem> locked = items.lockAllOrdered(lines.stream().map(Reservation.Line::getProductId)
                        .toList()).stream()
                .collect(Collectors.toMap(InventoryItem::getProductId, Function.identity()));
        for (Reservation.Line line : lines) {
            action.accept(locked.get(line.getProductId()), line.getQuantity());
        }
    }

    private static String validate(List<LineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            return "INVALID_REQUEST:EMPTY";
        }
        Set<UUID> seen = new HashSet<>();
        for (LineRequest line : lines) {
            if (line.productId() == null || line.quantity() <= 0) {
                return "INVALID_REQUEST:QUANTITY";
            }
            if (!seen.add(line.productId())) {
                return "INVALID_REQUEST:DUPLICATE_PRODUCT";
            }
        }
        return null;
    }

    public record LineRequest(UUID productId, int quantity) {
    }
}
