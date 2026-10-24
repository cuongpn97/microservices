package com.webshop.order.enums;

/** Trạng thái đơn hàng phía khách hàng (xem V1__orders_and_saga.sql cho định nghĩa nghiệp vụ). */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED
}
