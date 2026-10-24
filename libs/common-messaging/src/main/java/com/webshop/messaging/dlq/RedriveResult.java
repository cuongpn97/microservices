package com.webshop.messaging.dlq;

/**
 * Kết quả POST /admin/dlq/{dlq}/redrive.
 *
 * @param redriven số message đã chuyển về queue gốc; có thể nhỏ hơn max nếu DLQ hết message
 *                 hoặc một lần publish lại thất bại (dừng ngay, message đó ở lại DLQ).
 */
public record RedriveResult(String dlq, int redriven) {
}
