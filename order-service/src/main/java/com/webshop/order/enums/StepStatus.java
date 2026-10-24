package com.webshop.order.enums;

/**
 * Trạng thái của từng participant (inventory, payment) theo góc nhìn của Saga.
 * Các trạng thái *_PENDING nghĩa là "đã gửi command, CHƯA biết kết quả" — tài nguyên có thể đã bị giữ hoặc chưa.
 */
public enum StepStatus {
    NOT_STARTED,
    RESERVE_PENDING,
    RESERVED,
    REJECTED,
    CONFIRM_PENDING,
    CONFIRMED,
    RELEASE_PENDING,
    RELEASED
}
