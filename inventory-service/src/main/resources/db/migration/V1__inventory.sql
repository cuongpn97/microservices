-- available = stock_on_hand - reserved_quantity (tính toán, không lưu để tránh lệch dữ liệu).
-- Các CHECK constraint là hàng rào CUỐI CÙNG: kể cả khi code có bug, DB không cho phép tồn kho âm
-- hoặc giữ chỗ vượt quá tồn kho thực (oversell).
CREATE TABLE inventory_item (
    product_id        UUID PRIMARY KEY,
    stock_on_hand     INT         NOT NULL CHECK (stock_on_hand >= 0),
    reserved_quantity INT         NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_inventory_not_oversold CHECK (reserved_quantity <= stock_on_hand)
);

-- Một reservation = một đơn hàng (reservation_id = orderId = sagaId) → khóa idempotency tự nhiên.
CREATE TABLE reservation (
    id         UUID PRIMARY KEY,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('RESERVED', 'REJECTED', 'COMMITTED', 'RELEASED')),
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE reservation_line (
    reservation_id UUID NOT NULL REFERENCES reservation (id),
    product_id     UUID NOT NULL,
    quantity       INT  NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, product_id)
);

CREATE TABLE outbox_event (
    id               UUID PRIMARY KEY,
    aggregate_type   VARCHAR(64)   NOT NULL,
    aggregate_id     VARCHAR(64)   NOT NULL,
    event_type       VARCHAR(128)  NOT NULL,
    event_version    INT           NOT NULL,
    exchange         VARCHAR(128)  NOT NULL,
    routing_key      VARCHAR(128)  NOT NULL,
    payload          JSONB         NOT NULL,
    correlation_id   VARCHAR(64),
    causation_id     VARCHAR(64),
    saga_id          VARCHAR(64),
    created_at       TIMESTAMPTZ   NOT NULL,
    published_at     TIMESTAMPTZ,
    publish_attempts INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL,
    last_error       VARCHAR(1000)
);
CREATE INDEX ix_outbox_event_pending ON outbox_event (next_attempt_at, created_at) WHERE published_at IS NULL;

CREATE TABLE processed_message (
    consumer     VARCHAR(100) NOT NULL,
    message_id   UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
