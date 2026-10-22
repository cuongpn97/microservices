package com.webshop.inventory.web;

import com.webshop.common.web.ApiException;
import com.webshop.inventory.application.InventoryService;
import com.webshop.inventory.domain.InventoryItem;
import com.webshop.inventory.domain.InventoryItemRepository;
import com.webshop.inventory.domain.Reservation;
import com.webshop.inventory.domain.ReservationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
public class InventoryController {

    private final InventoryService inventory;
    private final InventoryItemRepository items;
    private final ReservationRepository reservations;

    public InventoryController(InventoryService inventory, InventoryItemRepository items,
                               ReservationRepository reservations) {
        this.inventory = inventory;
        this.items = items;
        this.reservations = reservations;
    }

    @GetMapping("/api/inventory/{productId}")
    @Transactional(readOnly = true)
    public StockResponse get(@PathVariable UUID productId) {
        return items.findById(productId).map(StockResponse::from)
                .orElseThrow(() -> ApiException.notFound("INVENTORY_NOT_FOUND", "No inventory for " + productId));
    }

    /**
     * Tồn kho của nhiều sản phẩm một lần (trang cửa hàng dùng) — tránh N lời gọi HTTP mỗi lượt tải.
     * Sản phẩm chưa có dòng tồn kho bị bỏ qua thay vì 404 cả lô.
     */
    @GetMapping("/api/inventory/batch")
    @Transactional(readOnly = true)
    public List<StockResponse> batch(@RequestParam @Size(min = 1, max = 100) List<UUID> ids) {
        return items.findAllById(ids).stream().map(StockResponse::from).toList();
    }

    /** Thiết lập tồn kho (ADMIN). Không được thấp hơn lượng đang giữ chỗ. */
    @PutMapping("/api/inventory/{productId}")
    public StockResponse setStock(@PathVariable UUID productId, @Valid @RequestBody SetStockRequest request) {
        return StockResponse.from(inventory.setStock(productId, request.stockOnHand()));
    }

    /** Tra cứu reservation phục vụ đối soát/can thiệp thủ công (ADMIN, không qua gateway). */
    @GetMapping("/admin/reservations/{reservationId}")
    @Transactional(readOnly = true)
    public ReservationResponse reservation(@PathVariable UUID reservationId) {
        Reservation r = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "Not found"));
        return new ReservationResponse(r.getId(), r.getStatus().name(), r.getReason(),
                r.getLines().stream().map(l -> new LineResponse(l.getProductId(), l.getQuantity())).toList(),
                r.getUpdatedAt());
    }

    public record SetStockRequest(@NotNull @PositiveOrZero @Max(1_000_000) Integer stockOnHand) {
    }

    public record StockResponse(UUID productId, int stockOnHand, int reservedQuantity, int available,
                                Instant updatedAt) {
        static StockResponse from(InventoryItem i) {
            return new StockResponse(i.getProductId(), i.getStockOnHand(), i.getReservedQuantity(), i.available(),
                    i.getUpdatedAt());
        }
    }

    public record ReservationResponse(UUID reservationId, String status, String reason, List<LineResponse> lines,
                                      Instant updatedAt) {
    }

    public record LineResponse(UUID productId, int quantity) {
    }
}
