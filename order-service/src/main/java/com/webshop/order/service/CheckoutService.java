package com.webshop.order.service;

import com.webshop.order.dto.CheckoutResult;

import java.util.UUID;

public interface CheckoutService {

    /**
     * Bước ĐỒNG BỘ của checkout: kiểm tra, định giá, ghi đơn PENDING + khởi động Saga. Không chờ Saga chạy xong.
     *
     * @param bearerToken    access token của chính user — chuyển tiếp sang Cart Service để đọc đúng giỏ của họ.
     * @param idempotencyKey bắt buộc; gửi lại cùng key → trả đơn cũ (replayed = true).
     */
    CheckoutResult checkout(UUID userId, String bearerToken, String idempotencyKey);
}
