package com.webshop.messaging.dlq;

/**
 * Một message đang nằm trong DLQ, kèm các header chẩn đoán do ReliableMessageProcessor ghi lúc đẩy vào DLQ.
 * Mọi field trừ body có thể null (ví dụ message không parse được thì không có eventType).
 *
 * @param originalRoutingKey routing key lúc message đến lần đầu — retry tier đổi routing key nên phải lưu lại.
 * @param attempt            số lần đã thử xử lý trước khi bỏ cuộc.
 * @param failureReason      RETRIES_EXHAUSTED (lỗi tạm thời, hết lượt), PERMANENT hoặc UNPARSEABLE.
 * @param lastError          exception cuối cùng (đã cắt ngắn).
 * @param failedAt           thời điểm vào DLQ, dạng ISO-8601.
 * @param redriveCount       số lần đã được redrive; null nếu chưa redrive lần nào.
 * @param body               payload gốc, nguyên văn (UTF-8).
 */
public record DlqMessage(
        String messageId,
        String correlationId,
        String eventType,
        String originalRoutingKey,
        Integer attempt,
        String failureReason,
        String lastError,
        String failedAt,
        Integer redriveCount,
        String body) {
}
