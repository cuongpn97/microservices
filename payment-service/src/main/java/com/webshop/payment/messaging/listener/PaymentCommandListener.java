package com.webshop.payment.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.payment.dto.CreditOutcome;
import com.webshop.payment.messaging.command.CreditRefCommand;
import com.webshop.payment.messaging.command.ReserveCreditCommand;
import com.webshop.payment.messaging.event.CreditReplyEvent;
import com.webshop.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Command của Saga (ReserveCredit / CaptureCredit / ReleaseCredit) → thao tác ví + reply qua Outbox,
 * trong MỘT transaction cục bộ cùng với Inbox. Reply luôn được gửi, kể cả với lệnh trùng.
 */
@Component
@RequiredArgsConstructor
public class PaymentCommandListener {

    /** Tên consumer trong Inbox — KHÔNG được đổi, nếu không message cũ sẽ bị xử lý lại. */
    static final String CONSUMER = "payment-commands";

    private final ReliableMessageProcessor processor;
    private final WalletService walletService;
    private final OutboxWriter outboxWriter;

    @RabbitListener(queues = RoutingKeys.Q_PAYMENT_COMMANDS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        CreditOutcome outcome = switch (envelope.eventType()) {
            case "ReserveCredit" -> {
                ReserveCreditCommand cmd = processor.payload(envelope, ReserveCreditCommand.class, 1);
                yield walletService.reserve(cmd.reservationId(), cmd.userId(), cmd.amountVnd());
            }
            case "CaptureCredit" -> walletService.capture(
                    processor.payload(envelope, CreditRefCommand.class, 1).reservationId());
            case "ReleaseCredit" -> {
                CreditRefCommand cmd = processor.payload(envelope, CreditRefCommand.class, 1);
                yield walletService.release(cmd.reservationId(), cmd.userId(), cmd.reason());
            }
            default -> throw new PermanentMessageException("Unsupported command " + envelope.eventType());
        };
        outboxWriter.append(OutboxEvent.of("CreditReservation", outcome.reservationId().toString(),
                        outcome.replyType(), RoutingKeys.PAYMENT_REPLY,
                        new CreditReplyEvent(outcome.reservationId(), outcome.status(), outcome.reason(),
                                outcome.amountVnd()))
                .withContext(envelope.correlationId(), envelope.eventId().toString(), envelope.sagaId()));
    }
}
