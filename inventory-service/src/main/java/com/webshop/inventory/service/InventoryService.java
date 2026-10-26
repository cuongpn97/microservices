package com.webshop.inventory.service;

import com.webshop.inventory.dto.ReservationLineDto;
import com.webshop.inventory.dto.ReservationOutcome;
import com.webshop.inventory.dto.response.ReservationResponse;
import com.webshop.inventory.dto.response.StockResponse;

import java.util.List;
import java.util.UUID;

public interface InventoryService {

    // ---- Command từ Saga (gọi bởi InventoryCommandListener) ----
    // Bắt buộc chạy trong transaction có sẵn của caller (Propagation.MANDATORY), cùng transaction với Inbox/Outbox.
    // Idempotent theo reservationId: gọi lại nhiều lần trả cùng kết quả, chỉ lần đầu có applied=true.

    /** Giữ chỗ all-or-nothing: thiếu một dòng là từ chối cả reservation. */
    ReservationOutcome reserve(UUID reservationId, List<ReservationLineDto> lines);

    /** Hàng chính thức rời kho (đơn đã thanh toán). */
    ReservationOutcome commit(UUID reservationId);

    /** Trả hàng đang giữ về available (bù trừ Saga). Đến trước reserve → tạo tombstone RELEASED. */
    ReservationOutcome release(UUID reservationId, String reason);

    // ---- HTTP API ----

    StockResponse getStock(UUID skuId);

    /** Sản phẩm chưa có dòng tồn kho bị bỏ qua thay vì 404 cả lô. */
    List<StockResponse> getStocks(List<UUID> skuIds);

    /** Thiết lập tồn kho (ADMIN). Chưa có dòng → tạo mới; không được thấp hơn lượng đang giữ chỗ (409). */
    StockResponse setStock(UUID skuId, int stockOnHand);

    ReservationResponse getReservation(UUID reservationId);
}
