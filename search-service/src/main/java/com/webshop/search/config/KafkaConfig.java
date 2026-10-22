package com.webshop.search.config;

import com.webshop.search.cdc.MalformedChangeEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Xử lý lỗi của consumer CDC — khác hẳn RabbitMQ consumer ở các service khác:
 * <ul>
 *   <li><b>Lỗi tạm thời</b> (Elasticsearch down, timeout): retry MÃI với backoff, CHẶN partition. Không đẩy sang DLT,
 *       vì bỏ qua một bản cập nhật rồi xử lý bản sau sẽ làm read model sai thứ tự; thà chậm còn hơn sai.</li>
 *   <li><b>Lỗi vĩnh viễn</b> (message không parse được): retry vô ích → đẩy ngay sang {@code <topic>.DLT} để
 *       không chặn partition mãi; người vận hành xem và xử lý.</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler cdcErrorHandler(KafkaTemplate<Object, Object> template) {
        // Partition -1 = để producer tự chọn: DLT được broker tự tạo (số partition mặc định) nên có thể ít partition
        // hơn topic nguồn (Debezium tạo 3); giữ nguyên số partition gốc sẽ gửi lỗi.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);
        // maxElapsedTime mặc định là không giới hạn → lỗi tạm thời retry tới khi Elasticsearch sống lại.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedChangeEventException.class);
        return handler;
    }
}
