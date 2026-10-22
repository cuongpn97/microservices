-- Transactional Outbox: user và event UserRegistered được ghi trong CÙNG transaction.
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

-- Auth không consume message, nhưng giữ bảng inbox để schema đồng nhất giữa các service.
CREATE TABLE processed_message (
    consumer     VARCHAR(100) NOT NULL,
    message_id   UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
