-- Ai đã mua sản phẩm nào — dựng từ event OrderConfirmed của Order Service (không gọi Order Service lúc viết review).
-- Một dòng cho mỗi (user, sản phẩm, đơn hàng): event giao trùng → ON CONFLICT DO NOTHING.
CREATE TABLE purchase (
    user_id      UUID         NOT NULL,
    product_id   UUID         NOT NULL,
    order_id     UUID         NOT NULL,
    sku_id       UUID         NOT NULL,
    variant_name VARCHAR(200) NOT NULL DEFAULT '',
    purchased_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, product_id, order_id)
);

CREATE TABLE review (
    id           UUID PRIMARY KEY,
    product_id   UUID          NOT NULL,
    user_id      UUID          NOT NULL,
    -- Tên hiển thị đã che bớt (từ email trong JWT) — không lưu email đầy đủ, không lộ danh tính người mua.
    author_name  VARCHAR(100)  NOT NULL,
    rating       SMALLINT      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title        VARCHAR(150),
    content      VARCHAR(4000) NOT NULL,
    -- Biến thể đã mua ("Đen / M"), giống "Phân loại hàng" trên các sàn TMĐT.
    variant_name VARCHAR(200)  NOT NULL DEFAULT '',
    status       VARCHAR(20)   NOT NULL CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    version      BIGINT        NOT NULL DEFAULT 0
);
-- Mỗi người một đánh giá cho mỗi sản phẩm (viết lại = sửa đánh giá cũ). Chốt chặn cuối khi hai request song song.
CREATE UNIQUE INDEX ux_review_product_user ON review (product_id, user_id);
CREATE INDEX ix_review_product_status_created ON review (product_id, status, created_at DESC);
CREATE INDEX ix_review_status_created ON review (status, created_at DESC);

-- Tóm tắt đánh giá mỗi sản phẩm (chỉ tính review PUBLISHED). Dòng này cũng là KHÓA tuần tự hóa mọi thay đổi review
-- của cùng sản phẩm: tính lại tổng trong lúc giữ khóa → không có hai transaction ghi đè tổng của nhau.
CREATE TABLE product_rating (
    product_id   UUID PRIMARY KEY,
    rating_count INT         NOT NULL DEFAULT 0,
    rating_sum   BIGINT      NOT NULL DEFAULT 0,
    count_1      INT         NOT NULL DEFAULT 0,
    count_2      INT         NOT NULL DEFAULT 0,
    count_3      INT         NOT NULL DEFAULT 0,
    count_4      INT         NOT NULL DEFAULT 0,
    count_5      INT         NOT NULL DEFAULT 0,
    -- Tăng mỗi lần tính lại; đi kèm event ProductRatingChanged để Product bỏ qua bản cũ đến muộn.
    revision     BIGINT      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL
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
