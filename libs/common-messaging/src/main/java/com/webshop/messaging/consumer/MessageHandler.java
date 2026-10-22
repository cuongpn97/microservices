package com.webshop.messaging.consumer;

import com.webshop.messaging.EventEnvelope;

/** Xử lý nghiệp vụ của một message. Chạy BÊN TRONG transaction cục bộ cùng với bản ghi Inbox. */
@FunctionalInterface
public interface MessageHandler {

    void handle(EventEnvelope envelope);
}
