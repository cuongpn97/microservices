package com.webshop.search.messaging.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.search.messaging.event.ChangeEvent;
import com.webshop.search.service.ProductIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer CDC của bảng products (topic do Debezium ghi). Lỗi được xử lý ở KafkaConfig: lỗi tạm thời retry mãi,
 * message hỏng đi thẳng DLT.
 *
 * <p>Thứ tự: Debezium dùng khóa chính làm key Kafka → mọi thay đổi của MỘT sản phẩm nằm cùng partition và đến
 * đúng thứ tự commit trong PostgreSQL. Giữa các sản phẩm khác nhau thì không có thứ tự — và không cần.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCdcListener {

    private final ObjectMapper objectMapper;
    private final ProductIndexService productIndexService;

    // idIsGroup = false: mặc định Spring Kafka dùng id của listener làm group id, ghi đè spring.kafka.consumer.group-id
    // → đổi index sẽ KHÔNG có group mới, không replay.
    @KafkaListener(id = "cdc-products", idIsGroup = false, topics = "${webshop.search.topics.products}")
    public void onProduct(ConsumerRecord<String, String> record) {
        ChangeEvent event = ChangeEvent.parse(objectMapper, record.value());
        if (event == null) {
            return; // tombstone sau delete — đã xử lý ở event 'd'
        }
        productIndexService.apply(event);
        log.debug("Applied product change op={} key={} offset={}", event.op(), record.key(), record.offset());
    }
}
