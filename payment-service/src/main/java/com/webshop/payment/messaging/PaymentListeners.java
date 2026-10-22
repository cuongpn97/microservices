package com.webshop.payment.messaging;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.payment.wallet.CreditOutcome;
import com.webshop.payment.wallet.WalletService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Hai consumer của Payment:
 * <ol>
 *     <li>UserRegistered (event từ Auth) → tạo ví + credit ban đầu (idempotent).</li>
 *     <li>Command của Saga (ReserveCredit / CaptureCredit / ReleaseCredit) → thao tác ví + reply qua Outbox.</li>
 * </ol>
 * Mỗi consumer có tên riêng trong Inbox để dedup độc lập.
 */
@Component
public class PaymentListeners {

    static final String USER_REGISTERED_CONSUMER = "payment-user-registered";
    static final String COMMAND_CONSUMER = "payment-commands";

    private final ReliableMessageProcessor processor;
    private final WalletService wallets;
    private final OutboxWriter outbox;

    public PaymentListeners(ReliableMessageProcessor processor, WalletService wallets, OutboxWriter outbox) {
        this.processor = processor;
        this.wallets = wallets;
        this.outbox = outbox;
    }

    @RabbitListener(queues = RoutingKeys.Q_PAYMENT_USER_REGISTERED, ackMode = "MANUAL")
    public void onUserRegistered(Message message, Channel channel) {
        processor.process(message, channel, USER_REGISTERED_CONSUMER, this::handleUserRegistered);
    }

    @RabbitListener(queues = RoutingKeys.Q_PAYMENT_COMMANDS, ackMode = "MANUAL")
    public void onCommand(Message message, Channel channel) {
        processor.process(message, channel, COMMAND_CONSUMER, this::handleCommand);
    }

    void handleUserRegistered(EventEnvelope envelope) {
        if (!"UserRegistered".equals(envelope.eventType())) {
            throw new PermanentMessageException("Unexpected event " + envelope.eventType());
        }
        UserRegistered event = processor.payload(envelope, UserRegistered.class, 1);
        wallets.createWalletWithInitialCredit(event.userId());
    }

    void handleCommand(EventEnvelope envelope) {
        CreditOutcome outcome = switch (envelope.eventType()) {
            case "ReserveCredit" -> {
                ReserveCredit cmd = processor.payload(envelope, ReserveCredit.class, 1);
                yield wallets.reserve(cmd.reservationId(), cmd.userId(), cmd.amountVnd());
            }
            case "CaptureCredit" -> wallets.capture(processor.payload(envelope, CreditRef.class, 1).reservationId());
            case "ReleaseCredit" -> {
                CreditRef cmd = processor.payload(envelope, CreditRef.class, 1);
                yield wallets.release(cmd.reservationId(), cmd.userId(), cmd.reason());
            }
            default -> throw new PermanentMessageException("Unsupported command " + envelope.eventType());
        };
        outbox.append(OutboxEvent.of("CreditReservation", outcome.reservationId().toString(), outcome.replyType(),
                        RoutingKeys.PAYMENT_REPLY,
                        new CreditReply(outcome.reservationId(), outcome.status(), outcome.reason(), outcome.amountVnd()))
                .withContext(envelope.correlationId(), envelope.eventId().toString(), envelope.sagaId()));
    }

    public record UserRegistered(UUID userId) {
    }

    public record ReserveCredit(UUID reservationId, UUID userId, long amountVnd) {
    }

    public record CreditRef(UUID reservationId, UUID userId, String reason) {
    }

    public record CreditReply(UUID reservationId, String status, String reason, long amountVnd) {
    }
}
