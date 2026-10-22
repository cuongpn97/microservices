-- Trạng thái đơn hàng mà khách hàng nhìn thấy:
--   PENDING   : đã ghi nhận, Saga đang chạy (giữ hàng / giữ credit / xác nhận / bồi hoàn)
--   CONFIRMED : đã commit tồn kho VÀ capture credit thành công
--   FAILED    : không thể hoàn tất; mọi tài nguyên đã giữ đều đã được trả lại
CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    total_vnd       BIGINT       NOT NULL CHECK (total_vnd >= 0),
    idempotency_key VARCHAR(100) NOT NULL,
    cart_revision   BIGINT       NOT NULL,
    failure_reason  VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);
-- Client retry cùng Idempotency-Key → không tạo đơn thứ hai.
CREATE UNIQUE INDEX ux_orders_user_idempotency ON orders (user_id, idempotency_key);
-- Hai request checkout KHÁC key nhưng cùng một revision giỏ hàng → chỉ một đơn đang hoạt động.
-- Đơn FAILED không chặn (user được thử lại với cùng giỏ).
CREATE UNIQUE INDEX ux_orders_active_cart ON orders (user_id, cart_revision) WHERE status <> 'FAILED';
CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC);

-- Snapshot bất biến: tên + đơn giá tại thời điểm checkout (đổi giá sau này không ảnh hưởng đơn cũ).
CREATE TABLE order_item (
    order_id       UUID         NOT NULL REFERENCES orders (id),
    product_id     UUID         NOT NULL,
    product_name   VARCHAR(200) NOT NULL,
    unit_price_vnd BIGINT       NOT NULL CHECK (unit_price_vnd >= 0),
    quantity       INT          NOT NULL CHECK (quantity > 0),
    line_total_vnd BIGINT       NOT NULL CHECK (line_total_vnd >= 0),
    PRIMARY KEY (order_id, product_id)
);

CREATE FUNCTION forbid_order_item_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order_item snapshot is immutable: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_item_immutable
    BEFORE UPDATE OR DELETE ON order_item
    FOR EACH ROW EXECUTE FUNCTION forbid_order_item_mutation();

-- Trạng thái Saga được lưu bền: process restart không làm mất tiến trình.
CREATE TABLE checkout_saga (
    id              UUID PRIMARY KEY REFERENCES orders (id), -- sagaId = orderId
    user_id         UUID         NOT NULL,
    total_vnd       BIGINT       NOT NULL,
    correlation_id  VARCHAR(64)  NOT NULL,
    state           VARCHAR(30)  NOT NULL CHECK (state IN
                        ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMING', 'COMPLETED', 'COMPENSATING', 'FAILED')),
    inventory_step  VARCHAR(30)  NOT NULL,
    payment_step    VARCHAR(30)  NOT NULL,
    step_attempts   INT          NOT NULL DEFAULT 0,
    deadline_at     TIMESTAMPTZ,
    needs_attention BOOLEAN      NOT NULL DEFAULT FALSE,
    failure_reason  VARCHAR(300),
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);
-- Watcher quét Saga quá hạn (chỉ các state chưa kết thúc).
CREATE INDEX ix_checkout_saga_due ON checkout_saga (deadline_at)
    WHERE state IN ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMING', 'COMPENSATING');

-- Nhật ký chuyển trạng thái để đối soát/điều tra.
CREATE TABLE saga_transition (
    id         BIGSERIAL PRIMARY KEY,
    saga_id    UUID         NOT NULL REFERENCES checkout_saga (id),
    from_state VARCHAR(30),
    to_state   VARCHAR(30)  NOT NULL,
    trigger    VARCHAR(100) NOT NULL,
    detail     VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_saga_transition_saga ON saga_transition (saga_id, id);

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
