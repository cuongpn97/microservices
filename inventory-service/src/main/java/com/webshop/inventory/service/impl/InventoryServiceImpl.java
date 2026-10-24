package com.webshop.inventory.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.inventory.dto.ReservationLineDto;
import com.webshop.inventory.dto.ReservationOutcome;
import com.webshop.inventory.dto.response.ReservationResponse;
import com.webshop.inventory.dto.response.StockResponse;
import com.webshop.inventory.entity.InventoryItem;
import com.webshop.inventory.entity.Reservation;
import com.webshop.inventory.entity.ReservationLine;
import com.webshop.inventory.enums.ReservationStatus;
import com.webshop.inventory.mapper.InventoryMapper;
import com.webshop.inventory.repository.InventoryItemRepository;
import com.webshop.inventory.repository.ReservationRepository;
import com.webshop.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

import static com.webshop.inventory.dto.ReservationOutcome.COMMITTED;
import static com.webshop.inventory.dto.ReservationOutcome.COMMIT_REJECTED;
import static com.webshop.inventory.dto.ReservationOutcome.RELEASED;
import static com.webshop.inventory.dto.ReservationOutcome.RELEASE_REJECTED;
import static com.webshop.inventory.dto.ReservationOutcome.RESERVATION_REJECTED;
import static com.webshop.inventory.dto.ReservationOutcome.RESERVED;

/**
 * Nghiệp vụ tồn kho. Mọi thao tác reserve/commit/release:
 * <ul>
 *     <li>chạy trong transaction của caller (consumer message) — cùng transaction với Inbox và Outbox reply;</li>
 *     <li>idempotent theo reservationId (= orderId);</li>
 *     <li>khóa dòng tồn kho theo thứ tự cố định (pessimistic lock) để chống oversell và deadlock;</li>
 *     <li>được CHECK constraint trong DB bảo vệ lần cuối.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryItemRepository inventoryItemRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryMapper inventoryMapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome reserve(UUID reservationId, List<ReservationLineDto> requested) {
        Instant now = clock.instant();
        int claimed = reservationRepository.insertIfAbsent(reservationId, ReservationStatus.RESERVED.name(), null, now);
        Reservation reservation = reservationRepository.lockById(reservationId).orElseThrow();
        if (claimed == 0) {
            return replayReserve(reservation);
        }

        String invalid = validate(requested);
        if (invalid != null) {
            reservation.reject(invalid, now);
            return new ReservationOutcome(RESERVATION_REJECTED, reservationId, ReservationStatus.REJECTED, invalid, true);
        }

        Map<UUID, Integer> quantities = requested.stream()
                .collect(Collectors.toMap(ReservationLineDto::productId, ReservationLineDto::quantity));
        Map<UUID, InventoryItem> locked = inventoryItemRepository.lockAllOrdered(quantities.keySet()).stream()
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
        reservation.markReserved(requested.stream().map(l -> new ReservationLine(l.productId(), l.quantity()))
                .toList(), now);
        log.info("Stock reserved reservationId={} lines={}", reservationId, requested.size());
        return new ReservationOutcome(RESERVED, reservationId, ReservationStatus.RESERVED, null, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome commit(UUID reservationId) {
        Reservation reservation = reservationRepository.lockById(reservationId).orElse(null);
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome release(UUID reservationId, String reason) {
        Instant now = clock.instant();
        int tombstone = reservationRepository.insertIfAbsent(reservationId, ReservationStatus.RELEASED.name(),
                "RELEASED_BEFORE_RESERVE", now);
        if (tombstone == 1) {
            log.info("Release arrived before reserve; tombstone created reservationId={}", reservationId);
            return new ReservationOutcome(RELEASED, reservationId, ReservationStatus.RELEASED,
                    "RELEASED_BEFORE_RESERVE", true);
        }
        Reservation reservation = reservationRepository.lockById(reservationId).orElseThrow();
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

    @Override
    @Transactional(readOnly = true)
    public StockResponse getStock(UUID productId) {
        return inventoryItemRepository.findById(productId).map(inventoryMapper::toStockResponse)
                .orElseThrow(() -> ApiException.notFound("INVENTORY_NOT_FOUND", "No inventory for " + productId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockResponse> getStocks(List<UUID> productIds) {
        return inventoryMapper.toStockResponses(inventoryItemRepository.findAllById(productIds));
    }

    @Override
    @Transactional
    public StockResponse setStock(UUID productId, int stockOnHand) {
        if (stockOnHand < 0) {
            throw ApiException.badRequest("INVALID_STOCK", "stockOnHand must be >= 0");
        }
        Instant now = clock.instant();
        InventoryItem item = inventoryItemRepository.lockById(productId).orElse(null);
        if (item == null) {
            return inventoryMapper.toStockResponse(inventoryItemRepository.save(new InventoryItem(productId, stockOnHand, now)));
        }
        try {
            item.setStockOnHand(stockOnHand, now);
        } catch (IllegalStateException e) {
            throw ApiException.conflict("STOCK_BELOW_RESERVED", e.getMessage());
        }
        return inventoryMapper.toStockResponse(item);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID reservationId) {
        return reservationRepository.findById(reservationId).map(inventoryMapper::toReservationResponse)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "Not found"));
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

    private void applyToLockedItems(Reservation reservation, ObjIntConsumer<InventoryItem> action) {
        List<ReservationLine> lines = reservation.getLines();
        Map<UUID, InventoryItem> locked = inventoryItemRepository.lockAllOrdered(
                        lines.stream().map(ReservationLine::getProductId).toList()).stream()
                .collect(Collectors.toMap(InventoryItem::getProductId, Function.identity()));
        for (ReservationLine line : lines) {
            action.accept(locked.get(line.getProductId()), line.getQuantity());
        }
    }

    private static String validate(List<ReservationLineDto> lines) {
        if (lines == null || lines.isEmpty()) {
            return "INVALID_REQUEST:EMPTY";
        }
        Set<UUID> seen = new HashSet<>();
        for (ReservationLineDto line : lines) {
            if (line.productId() == null || line.quantity() <= 0) {
                return "INVALID_REQUEST:QUANTITY";
            }
            if (!seen.add(line.productId())) {
                return "INVALID_REQUEST:DUPLICATE_PRODUCT";
            }
        }
        return null;
    }
}
