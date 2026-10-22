package com.webshop.messaging.testapp;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.messaging.consumer.RetryTopology;
import com.webshop.messaging.outbox.OutboxSender;
import com.webshop.messaging.outbox.RabbitOutboxSender;
import com.webshop.messaging.test.MutableClock;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Ứng dụng tối giản chỉ dùng để integration-test thư viện messaging với PostgreSQL + RabbitMQ thật. */
@SpringBootApplication
public class MessagingTestApplication {

    public static final String QUEUE = "test.q";

    @Bean
    public MutableClock clock() {
        return MutableClock.startingNow();
    }

    @Bean
    public Declarables testQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, QUEUE, "test.#");
    }

    @Bean
    public TestHandler testHandler() {
        return new TestHandler();
    }

    @Bean
    @Primary
    public FlakySender flakySender(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new FlakySender(new RabbitOutboxSender(rabbitTemplate, properties));
    }

    @Bean
    public TestListener testListener(ReliableMessageProcessor processor, TestHandler handler) {
        return new TestListener(processor, handler);
    }

    public static class TestListener {
        private final ReliableMessageProcessor processor;
        private final TestHandler handler;

        TestListener(ReliableMessageProcessor processor, TestHandler handler) {
            this.processor = processor;
            this.handler = handler;
        }

        @RabbitListener(queues = QUEUE, ackMode = "MANUAL")
        public void onMessage(Message message, Channel channel) {
            processor.process(message, channel, "test-consumer", handler);
        }
    }

    /** OutboxSender có thể mô phỏng lỗi trước khi gửi, hoặc "crash" SAU khi broker đã confirm. */
    public static class FlakySender implements OutboxSender {
        public enum Mode { NORMAL, FAIL_BEFORE_SEND, FAIL_AFTER_CONFIRM }

        private final OutboxSender delegate;
        private volatile Mode mode = Mode.NORMAL;
        private final java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();

        FlakySender(OutboxSender delegate) {
            this.delegate = delegate;
        }

        public void mode(Mode mode) {
            this.mode = mode;
        }

        public int sends() {
            return sends.get();
        }

        public void resetSends() {
            sends.set(0);
        }

        @Override
        public void sendAndConfirm(com.webshop.messaging.outbox.OutboxRecord record) throws Exception {
            if (mode == Mode.FAIL_BEFORE_SEND) {
                throw new IllegalStateException("simulated broker unavailable");
            }
            delegate.sendAndConfirm(record);
            sends.incrementAndGet();
            if (mode == Mode.FAIL_AFTER_CONFIRM) {
                throw new IllegalStateException("simulated crash after broker confirm");
            }
        }
    }
}
