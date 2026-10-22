package com.webshop.inventory.messaging;

import com.rabbitmq.client.Channel;
import com.webshop.inventory.application.InventoryService;
import com.webshop.inventory.application.ReservationOutcome;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Consumer command từ Saga của Order Service. Trong MỘT transaction cục bộ:
 * Inbox (dedup) → thay đổi tồn kho → ghi reply vào Outbox. Reply luôn được gửi, kể cả khi command là bản trùng
 * về mặt nghiệp vụ, để Saga không bị "treo" chờ.
 */
@Component
public class InventoryCommandListener {

    static final String CONSUMER = "inventory-commands";

    private final ReliableMessageProcessor processor;
    private final InventoryService inventory;
    private final OutboxWriter outbox;

    public InventoryCommandListener(ReliableMessageProcessor processor, InventoryService inventory,
                                    OutboxWriter outbox) {
        this.processor = processor;
        this.inventory = inventory;
        this.outbox = outbox;
    }

    @RabbitListener(queues = RoutingKeys.Q_INVENTORY_COMMANDS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        ReservationOutcome outcome = switch (envelope.eventType()) {
            case "ReserveInventory" -> {
                ReserveInventory cmd = processor.payload(envelope, ReserveInventory.class, 1);
                yield inventory.reserve(cmd.reservationId(), cmd.items().stream()
                        .map(i -> new InventoryService.LineRequest(i.productId(), i.quantity())).toList());
            }
            case "CommitInventory" -> inventory.commit(
                    processor.payload(envelope, ReservationRef.class, 1).reservationId());
            case "ReleaseInventory" -> {
                ReservationRef cmd = processor.payload(envelope, ReservationRef.class, 1);
                yield inventory.release(cmd.reservationId(), cmd.reason());
            }
            default -> throw new PermanentMessageException("Unsupported command " + envelope.eventType());
        };
        outbox.append(OutboxEvent.of("Reservation", outcome.reservationId().toString(), outcome.replyType(),
                        RoutingKeys.INVENTORY_REPLY,
                        new InventoryReply(outcome.reservationId(),
                                outcome.status() == null ? null : outcome.status().name(), outcome.reason()))
                .withContext(envelope.correlationId(), envelope.eventId().toString(), envelope.sagaId()));
    }

    public record ReserveInventory(UUID reservationId, List<Item> items) {
        public record Item(UUID productId, int quantity) {
        }
    }

    public record ReservationRef(UUID reservationId, String reason) {
    }

    public record InventoryReply(UUID reservationId, String status, String reason) {
    }
}
