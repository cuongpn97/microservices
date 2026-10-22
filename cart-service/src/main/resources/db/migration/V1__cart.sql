-- Giỏ hàng chỉ lưu productId + quantity. KHÔNG lưu giá: giá luôn lấy từ Product Service khi hiển thị/checkout.
CREATE TABLE cart (
    user_id    UUID PRIMARY KEY,
    -- revision tăng mỗi lần giỏ thay đổi; Order Service dùng để chống checkout trùng cùng một "phiên bản" giỏ.
    revision   BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE cart_item (
    user_id    UUID NOT NULL REFERENCES cart (user_id),
    product_id UUID NOT NULL,
    quantity   INT  NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (user_id, product_id)
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
