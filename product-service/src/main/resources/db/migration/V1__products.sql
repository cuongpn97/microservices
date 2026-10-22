CREATE TABLE products (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    -- Tiền tệ: số nguyên VND (BIGINT), không dùng float/double/numeric thập phân.
    price_vnd   BIGINT        NOT NULL CHECK (price_vnd >= 0),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_products_active ON products (active, name);
