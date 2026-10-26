package com.webshop.review.service;

import com.webshop.review.messaging.event.OrderConfirmedEvent;

import java.time.Instant;

public interface PurchaseService {

    /**
     * Ghi nhận các SPU trong một đơn CONFIRMED là "đã mua" bởi user của đơn. Bắt buộc chạy trong transaction của
     * consumer (cùng Inbox); idempotent theo (user, sản phẩm, đơn).
     */
    void recordPurchases(OrderConfirmedEvent event, Instant confirmedAt);
}
