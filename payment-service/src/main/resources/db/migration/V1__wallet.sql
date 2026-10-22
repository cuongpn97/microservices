-- Ví nội bộ (credit khuyến mãi VND). Không có nạp/rút tiền thật.
--   balance_vnd  : số credit đang sở hữu (đã trừ các khoản đã capture)
--   reserved_vnd : phần của balance đang bị giữ cho đơn hàng chưa hoàn tất
--   available    = balance_vnd - reserved_vnd  (không lưu, luôn tính)
CREATE TABLE wallet (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL,
    balance_vnd  BIGINT      NOT NULL CHECK (balance_vnd >= 0),
    reserved_vnd BIGINT      NOT NULL DEFAULT 0 CHECK (reserved_vnd >= 0),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    -- available không bao giờ âm: hàng rào cuối ở tầng DB.
    CONSTRAINT ck_wallet_available_non_negative CHECK (reserved_vnd <= balance_vnd)
);
-- Một user chỉ có một ví → UserRegistered giao trùng không thể tạo ví thứ hai.
CREATE UNIQUE INDEX ux_wallet_user ON wallet (user_id);

-- Giữ credit cho một đơn. id = orderId = khóa idempotency nghiệp vụ.
CREATE TABLE credit_reservation (
    id         UUID PRIMARY KEY,
    user_id    UUID,
    wallet_id  UUID REFERENCES wallet (id),
    amount_vnd BIGINT      NOT NULL CHECK (amount_vnd >= 0),
    status     VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'CAPTURED', 'RELEASED', 'REJECTED')),
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Sổ cái append-only: mọi thay đổi số dư đều có một dòng ledger, không sửa/xóa lịch sử.
CREATE TABLE wallet_ledger (
    id                 UUID PRIMARY KEY,
    wallet_id          UUID         NOT NULL REFERENCES wallet (id),
    entry_type         VARCHAR(20)  NOT NULL CHECK (entry_type IN ('INITIAL_CREDIT', 'RESERVE', 'CAPTURE', 'RELEASE')),
    amount_vnd         BIGINT       NOT NULL CHECK (amount_vnd > 0),
    balance_after_vnd  BIGINT       NOT NULL,
    reserved_after_vnd BIGINT       NOT NULL,
    -- Mỗi thao tác tài chính có một khóa nghiệp vụ duy nhất, ví dụ "capture:<orderId>".
    idempotency_key    VARCHAR(100) NOT NULL,
    reference_id       VARCHAR(64),
    correlation_id     VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_wallet_ledger_idempotency ON wallet_ledger (idempotency_key);
CREATE INDEX ix_wallet_ledger_wallet ON wallet_ledger (wallet_id, created_at);

CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'wallet_ledger is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wallet_ledger_append_only
    BEFORE UPDATE OR DELETE ON wallet_ledger
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

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
