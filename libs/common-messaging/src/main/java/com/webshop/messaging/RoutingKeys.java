package com.webshop.messaging;

/**
 * Routing key và tên queue dùng trong hệ thống. Đây là "hợp đồng" giữa các service;
 * payload class thì mỗi service tự định nghĩa (tolerant reader), không chia sẻ class domain.
 */
public final class RoutingKeys {

    public static final String USER_REGISTERED = "auth.user.registered";

    public static final String INVENTORY_RESERVE = "inventory.command.reserve";
    public static final String INVENTORY_COMMIT = "inventory.command.commit";
    public static final String INVENTORY_RELEASE = "inventory.command.release";
    public static final String INVENTORY_COMMANDS_PATTERN = "inventory.command.*";
    public static final String INVENTORY_REPLY = "inventory.reply";

    public static final String PAYMENT_RESERVE = "payment.command.reserve";
    public static final String PAYMENT_CAPTURE = "payment.command.capture";
    public static final String PAYMENT_RELEASE = "payment.command.release";
    public static final String PAYMENT_COMMANDS_PATTERN = "payment.command.*";
    public static final String PAYMENT_REPLY = "payment.reply";

    public static final String ORDER_CONFIRMED = "order.confirmed";

    public static final String Q_PAYMENT_USER_REGISTERED = "payment.user-registered.q";
    public static final String Q_PAYMENT_COMMANDS = "payment.commands.q";
    public static final String Q_INVENTORY_COMMANDS = "inventory.commands.q";
    public static final String Q_ORDER_SAGA_REPLIES = "order.saga-replies.q";
    public static final String Q_CART_ORDER_EVENTS = "cart.order-events.q";

    private RoutingKeys() {
    }
}
