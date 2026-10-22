package com.webshop.messaging.outbox;

/**
 * Gửi một record outbox tới broker và chỉ return bình thường khi broker đã CONFIRM.
 * Ném exception nếu bị nack, bị return (unroutable) hoặc hết thời gian chờ confirm.
 * Tách thành interface để test được kịch bản publish lỗi.
 */
public interface OutboxSender {

    void sendAndConfirm(OutboxRecord record) throws Exception;
}
