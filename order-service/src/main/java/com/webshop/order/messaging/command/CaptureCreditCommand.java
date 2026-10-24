package com.webshop.order.messaging.command;

import java.util.UUID;

/** Command "CaptureCredit" v1: trừ tiền đã giữ. */
public record CaptureCreditCommand(UUID reservationId, UUID userId) {
}
