package com.webshop.cart.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param estimatedTotalVnd giá HIỆN TẠI (chỉ tham khảo); giá chốt là giá Order Service lấy lúc checkout.
 *                          null khi không có giá.
 * @param pricingAvailable  false khi không lấy được giá từ Product Service — giỏ vẫn trả về (fallback).
 */
public record CartResponse(
        UUID userId,
        long revision,
        List<CartItemResponse> items,
        Long estimatedTotalVnd,
        boolean pricingAvailable,
        Instant updatedAt) {
}
