package com.webshop.messaging;

/**
 * Lỗi KHÔNG thể khỏi bằng cách thử lại (message sai định dạng, version không hỗ trợ, vi phạm bất biến...).
 * Message được chuyển thẳng vào DLQ, không đi qua retry queue.
 */
public class PermanentMessageException extends RuntimeException {

    public PermanentMessageException(String message) {
        super(message);
    }

    public PermanentMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
