package com.webshop.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cấu hình messaging. Các giá trị mặc định phù hợp local; override bằng biến môi trường.
 */
@ConfigurationProperties(prefix = "webshop.messaging")
public record MessagingProperties(String exchange, String deadLetterExchange, Retry retry, Outbox outbox) {

    public MessagingProperties {
        exchange = exchange != null ? exchange : "webshop.events";
        deadLetterExchange = deadLetterExchange != null ? deadLetterExchange : "webshop.dlx";
        retry = retry != null ? retry : new Retry(null, null, null, null, null);
        outbox = outbox != null ? outbox : new Outbox(null, null, null, null, null, null);
    }

    /**
     * Retry bất đồng bộ phía consumer. maxAttempts tính cả lần xử lý đầu tiên;
     * hết số lần thử thì message vào DLQ (không requeue vô hạn).
     */
    public record Retry(Integer maxAttempts, Duration initialDelay, Double multiplier, Duration maxDelay,
                        Double jitterFactor) {
        public Retry {
            maxAttempts = maxAttempts != null ? maxAttempts : 4;
            initialDelay = initialDelay != null ? initialDelay : Duration.ofSeconds(1);
            multiplier = multiplier != null ? multiplier : 3.0;
            maxDelay = maxDelay != null ? maxDelay : Duration.ofSeconds(30);
            jitterFactor = jitterFactor != null ? jitterFactor : 0.2;
        }
    }

    /**
     * Polling publisher của Outbox. Lỗi publish được thử lại vô hạn (broker có thể down lâu)
     * với backoff có trần; backlog được phơi ra qua metric webshop.outbox.backlog.
     */
    public record Outbox(Boolean schedulingEnabled, Long pollIntervalMs, Integer batchSize, Duration confirmTimeout,
                         Duration initialBackoff, Duration maxBackoff) {
        public Outbox {
            schedulingEnabled = schedulingEnabled != null ? schedulingEnabled : Boolean.TRUE;
            pollIntervalMs = pollIntervalMs != null ? pollIntervalMs : 500L;
            batchSize = batchSize != null ? batchSize : 50;
            confirmTimeout = confirmTimeout != null ? confirmTimeout : Duration.ofSeconds(5);
            initialBackoff = initialBackoff != null ? initialBackoff : Duration.ofSeconds(1);
            maxBackoff = maxBackoff != null ? maxBackoff : Duration.ofMinutes(1);
        }
    }
}
