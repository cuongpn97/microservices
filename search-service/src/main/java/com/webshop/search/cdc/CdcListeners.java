package com.webshop.search.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.search.index.ProductIndexer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Chiếu (project) thay đổi của bảng nguồn thành document tìm kiếm.
 *
 * <p>Thứ tự: Debezium dùng khóa chính làm key Kafka → mọi thay đổi của MỘT sản phẩm nằm cùng partition và đến
 * đúng thứ tự commit trong PostgreSQL. Giữa các sản phẩm khác nhau thì không có thứ tự — và không cần.</p>
 *
 * <p>Ánh xạ cột: tên cột SQL (snake_case) là hợp đồng với service nguồn. Đổi tên cột ở product-service là
 * breaking change với search-service — cái giá của CDC trên bảng thô (xem docs/design-decisions.md).</p>
 */
@Component
public class CdcListeners {

    private static final Logger log = LoggerFactory.getLogger(CdcListeners.class);

    private final ObjectMapper mapper;
    private final ProductIndexer indexer;

    public CdcListeners(ObjectMapper mapper, ProductIndexer indexer) {
        this.mapper = mapper;
        this.indexer = indexer;
    }

    // idIsGroup = false: mặc định Spring Kafka dùng id của listener làm group id, ghi đè spring.kafka.consumer.group-id
    // → đổi index sẽ KHÔNG có group mới, không replay.
    @KafkaListener(id = "cdc-products", idIsGroup = false, topics = "${webshop.search.topics.products}")
    public void onProduct(ConsumerRecord<String, String> record) {
        ChangeEvent event = ChangeEvent.parse(mapper, record.value());
        if (event == null) {
            return; // tombstone sau delete — đã xử lý ở event 'd'
        }
        String id = event.requiredText("id");
        switch (event.op()) {
            case DELETE -> indexer.deleteProduct(id);
            case CREATE, UPDATE, SNAPSHOT_READ -> indexer.upsertProduct(new ProductIndexer.ProductRow(
                    id,
                    event.requiredText("name"),
                    event.row().path("description").asText(""),
                    event.requiredLong("price_vnd"),
                    event.requiredBoolean("active"),
                    event.requiredLong("version"),
                    event.requiredText("updated_at")));
        }
        log.debug("Applied product {} op={} offset={}", id, event.op(), record.offset());
    }
}
