package com.webshop.payment.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.payment.messaging.event.UserRegisteredEvent;
import com.webshop.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * UserRegistered (event từ Auth) → tạo ví + credit ban đầu (idempotent).
 * Có tên consumer riêng trong Inbox để dedup độc lập với PaymentCommandListener.
 */
@Component
@RequiredArgsConstructor
public class UserRegisteredListener {

    /** Tên consumer trong Inbox — KHÔNG được đổi, nếu không message cũ sẽ bị xử lý lại. */
    static final String CONSUMER = "payment-user-registered";

    private final ReliableMessageProcessor processor;
    private final WalletService walletService;

    @RabbitListener(queues = RoutingKeys.Q_PAYMENT_USER_REGISTERED, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        if (!"UserRegistered".equals(envelope.eventType())) {
            throw new PermanentMessageException("Unexpected event " + envelope.eventType());
        }
        UserRegisteredEvent event = processor.payload(envelope, UserRegisteredEvent.class, 1);
        walletService.createWalletWithInitialCredit(event.userId());
    }
}
