package com.webshop.search.service;

import com.webshop.search.messaging.event.ChangeEvent;

public interface ProductIndexService {

    /**
     * Áp dụng một thay đổi của bảng products (event CDC từ Debezium) vào index.
     * Idempotent và chịu được replay: áp dụng lại cùng event, hoặc event cũ hơn, không làm hỏng dữ liệu.
     */
    void apply(ChangeEvent event);
}
