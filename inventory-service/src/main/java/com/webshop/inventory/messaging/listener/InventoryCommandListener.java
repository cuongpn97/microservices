package com.webshop.inventory.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.inventory.dto.ReservationLineDto;
import com.webshop.inventory.dto.ReservationOutcome;
import com.webshop.inventory.messaging.command.ReservationRefCommand;
import com.webshop.inventory.messaging.command.ReserveInventoryCommand;
import com.webshop.inventory.messaging.command.ReserveInventoryCommandV1;
import com.webshop.inventory.messaging.event.InventoryReplyEvent;
import com.webshop.inventory.service.InventoryService;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer command từ Saga của Order Service — vai trò giống Controller nhưng cho RabbitMQ thay vì HTTP.
 * Trong MỘT transaction cục bộ (do ReliableMessageProcessor mở):
 * Inbox (dedup) → thay đổi tồn kho → ghi reply vào Outbox. Reply luôn được gửi, kể cả khi command là bản trùng
 * về mặt nghiệp vụ, để Saga không bị "treo" chờ.
 */
@Component
@RequiredArgsConstructor
public class InventoryCommandListener {

    static final String CONSUMER = "inventory-commands";

    private final ReliableMessageProcessor processor;
    private final InventoryService inventoryService;
    private final OutboxWriter outboxWriter;

    @RabbitListener(queues = RoutingKeys.Q_INVENTORY_COMMANDS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        ReservationOutcome outcome = switch (envelope.eventType()) {
            case "ReserveInventory" -> {
                // v1 (trước SKU) có thể còn trong queue/retry/DLQ lúc nâng cấp → upcast thay vì từ chối.
                ReserveInventoryCommand cmd = envelope.eventVersion() == 1
                        ? processor.payload(envelope, ReserveInventoryCommandV1.class, 1).upcast()
                        : processor.payload(envelope, ReserveInventoryCommand.class, 2);
                yield inventoryService.reserve(cmd.reservationId(), cmd.items().stream()
                        .map(i -> new ReservationLineDto(i.skuId(), i.quantity())).toList());
            }
            case "CommitInventory" -> inventoryService.commit(
                    processor.payload(envelope, ReservationRefCommand.class, 1).reservationId());
            case "ReleaseInventory" -> {
                ReservationRefCommand cmd = processor.payload(envelope, ReservationRefCommand.class, 1);
                yield inventoryService.release(cmd.reservationId(), cmd.reason());
            }
            default -> throw new PermanentMessageException("Unsupported command " + envelope.eventType());
        };
        outboxWriter.append(OutboxEvent.of("Reservation", outcome.reservationId().toString(), outcome.replyType(),
                        RoutingKeys.INVENTORY_REPLY,
                        new InventoryReplyEvent(outcome.reservationId(),
                                outcome.status() == null ? null : outcome.status().name(), outcome.reason()))
                .withContext(envelope.correlationId(), envelope.eventId().toString(), envelope.sagaId()));
    }
}
