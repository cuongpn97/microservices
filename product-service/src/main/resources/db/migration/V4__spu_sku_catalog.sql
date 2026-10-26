-- Tách sản phẩm thành SPU (product: thông tin chung, ảnh, mô tả, thuộc tính) và SKU (product_sku: biến thể bán được,
-- có giá và tồn kho riêng). Cart/Order/Inventory tham chiếu SKU, không tham chiếu SPU.
--
-- Chuyển dữ liệu cũ: mỗi dòng products cũ → 1 SPU + 1 SKU mặc định. SKU GIỮ NGUYÊN id cũ, nên dữ liệu ở service
-- khác (inventory_item, cart_item, order_item) vẫn đúng mà không cần sửa chéo database. SPU nhận id MỚI, suy ra
-- tất định từ id cũ (md5) để chạy lại trên mọi môi trường đều ra cùng giá trị.

CREATE TABLE product (
    id                UUID PRIMARY KEY,
    slug              VARCHAR(220)  NOT NULL,
    name              VARCHAR(200)  NOT NULL,
    brand             VARCHAR(100),
    short_description VARCHAR(500)  NOT NULL DEFAULT '',
    -- Markdown. Storefront render bằng renderer KHÔNG cho HTML thô → không lưu/hiển thị HTML do admin nhập (XSS).
    description       TEXT          NOT NULL DEFAULT '',
    -- [{"name":"Kết nối","value":"USB-C"}] — thông số hiển thị, không dùng để lọc.
    specifications    JSONB         NOT NULL DEFAULT '[]',
    -- [{"name":"Màu","values":["Đen","Trắng"]}] — trục biến thể; SKU chọn đúng một giá trị trên mỗi trục.
    options           JSONB         NOT NULL DEFAULT '[]',
    active            BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Tóm tắt đánh giá, bản sao từ review-service (event ProductRatingChanged). rating_version chống áp dụng bản cũ.
    rating_count      INT           NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
    rating_sum        BIGINT        NOT NULL DEFAULT 0 CHECK (rating_sum >= 0),
    rating_version    BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_product_slug ON product (slug);
CREATE INDEX ix_product_active_name ON product (active, name);

CREATE TABLE product_image (
    id           UUID PRIMARY KEY,
    product_id   UUID         NOT NULL REFERENCES product (id),
    -- Chỉ lưu object key; URL công khai ghép lúc trả response (đổi CDN/bucket không phải sửa dữ liệu).
    object_key   VARCHAR(300) NOT NULL,
    content_type VARCHAR(50)  NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes > 0),
    alt_text     VARCHAR(200) NOT NULL DEFAULT '',
    position     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_product_image_product ON product_image (product_id, position);

CREATE TABLE product_sku (
    id                   UUID PRIMARY KEY,
    product_id           UUID         NOT NULL REFERENCES product (id),
    sku_code             VARCHAR(64)  NOT NULL,
    -- {"Màu":"Đen","Switch":"Red"}; {} khi SPU không có option (chỉ một SKU).
    attributes           JSONB        NOT NULL DEFAULT '{}',
    price_vnd            BIGINT       NOT NULL CHECK (price_vnd >= 0),
    -- Giá gạch ngang (giá trước khuyến mãi); chỉ hiển thị, không bao giờ dùng để tính tiền.
    compare_at_price_vnd BIGINT,
    image_id             UUID REFERENCES product_image (id) ON DELETE SET NULL,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    position             INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_sku_compare_at CHECK (compare_at_price_vnd IS NULL OR compare_at_price_vnd > price_vnd)
);
CREATE UNIQUE INDEX ux_product_sku_code ON product_sku (sku_code);
-- Hai SKU của cùng SPU không được trùng tổ hợp thuộc tính. So sánh jsonb không phụ thuộc thứ tự key.
CREATE UNIQUE INDEX ux_product_sku_attributes ON product_sku (product_id, attributes);

-- Bản chiếu (projection) mỗi SPU thành MỘT document JSON — đây là bảng DUY NHẤT được CDC sang search-service.
-- Search cần dữ liệu gộp (SPU + giá min/max của SKU + ảnh đại diện + rating) mà CDC từng bảng thô không cho được
-- (thay đổi SKU không chạm vào dòng product). payload là hợp đồng có version (schema_version), tách khỏi schema
-- nội bộ: đổi tên cột ở product/product_sku không làm vỡ search. Xem docs/design-decisions.md mục 13.
CREATE TABLE catalog_document (
    product_id     UUID PRIMARY KEY,
    -- Tăng 1 mỗi lần ghi (UPSERT giữ khóa dòng) → search bỏ qua bản cũ đến muộn.
    version        BIGINT      NOT NULL,
    schema_version INT         NOT NULL,
    payload        JSONB       NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

INSERT INTO product (id, slug, name, short_description, description, active, created_at, updated_at)
SELECT md5('spu:' || p.id::text)::uuid,
       -- slug tạm từ tên (chỉ ASCII) + 8 ký tự id cho chắc chắn duy nhất; admin sửa lại được.
       trim(both '-' from lower(regexp_replace(p.name, '[^A-Za-z0-9]+', '-', 'g'))) || '-' || left(p.id::text, 8),
       p.name, left(p.description, 500), p.description, p.active, p.created_at, p.updated_at
FROM products p;

INSERT INTO product_sku (id, product_id, sku_code, attributes, price_vnd, active, created_at, updated_at, version)
SELECT p.id, md5('spu:' || p.id::text)::uuid, 'SKU-' || upper(left(replace(p.id::text, '-', ''), 12)), '{}',
       p.price_vnd, TRUE, p.created_at, p.updated_at, p.version
FROM products p;

-- CDC bảng products cũ được thay bằng connector mới trên catalog_document (infra/debezium/catalog-connector.json).
-- Replication slot dbz_product (nếu đã tạo) phải drop tay — xem README mục "Search (CDC)".
DROP PUBLICATION IF EXISTS dbz_product;
DROP TABLE products;

CREATE PUBLICATION dbz_catalog FOR TABLE catalog_document;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_user') THEN
        GRANT USAGE ON SCHEMA public TO cdc_user;
        GRANT SELECT ON catalog_document TO cdc_user;
    END IF;
END
$$;

-- Product Service giờ nhận event ProductRatingChanged từ review-service (RabbitMQ) → cần Inbox; Outbox để
-- common-messaging hoạt động (hiện chưa publish gì qua RabbitMQ).
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
