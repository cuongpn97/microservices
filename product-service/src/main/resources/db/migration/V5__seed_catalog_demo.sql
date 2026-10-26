-- Dữ liệu demo cho SPU/SKU. SKU dùng UUID cố định để Inventory seed tồn kho cho đúng SKU (migration RIÊNG của
-- inventory-service, không truy cập DB này) và để README/script/test tham chiếu được.
-- SPU của 5 sản phẩm cũ có id = md5('spu:' || id SKU cũ) (xem V4).

-- ---------------------------------------------------------------- Bàn phím: 3 biến thể theo switch
UPDATE product SET
    slug = 'mechanical-keyboard',
    brand = 'Webshop Gear',
    short_description = 'Bàn phím cơ 87 phím, hot-swap, đèn nền trắng',
    description = E'## Gõ êm, bền bỉ\n\nBàn phím cơ **87 phím** (TKL) gọn gàng, khung nhôm, keycap PBT double-shot.\n\n- Hot-swap: thay switch không cần hàn\n- Kết nối USB-C, dây rời\n- Đèn nền trắng 5 mức\n\n> Chọn switch theo cảm giác gõ: **Red** êm và nhẹ, **Blue** có tiếng click, **Brown** có khấc nhẹ.',
    specifications = '[{"name":"Số phím","value":"87 (TKL)"},{"name":"Kết nối","value":"USB-C"},{"name":"Keycap","value":"PBT double-shot"},{"name":"Bảo hành","value":"12 tháng"}]',
    options = '[{"name":"Switch","values":["Red","Blue","Brown"]}]'
WHERE id = md5('spu:11111111-1111-1111-1111-111111111111')::uuid;

UPDATE product_sku SET sku_code = 'KB87-RED', attributes = '{"Switch":"Red"}', compare_at_price_vnd = 1790000, position = 0
WHERE id = '11111111-1111-1111-1111-111111111111';

INSERT INTO product_sku (id, product_id, sku_code, attributes, price_vnd, compare_at_price_vnd, position, created_at, updated_at) VALUES
('11111111-1111-1111-1111-000000000002', md5('spu:11111111-1111-1111-1111-111111111111')::uuid, 'KB87-BLU', '{"Switch":"Blue"}', 1550000, NULL, 1, now(), now()),
('11111111-1111-1111-1111-000000000003', md5('spu:11111111-1111-1111-1111-111111111111')::uuid, 'KB87-BRN', '{"Switch":"Brown"}', 1550000, NULL, 2, now(), now());

-- ---------------------------------------------------------------- Chuột: 2 màu
UPDATE product SET
    slug = 'wireless-mouse',
    brand = 'Webshop Gear',
    short_description = 'Chuột không dây 2.4GHz, pin 6 tháng',
    description = E'Chuột không dây **2.4GHz** với receiver nano, cảm biến 4000 DPI.\n\n- Pin AA dùng khoảng 6 tháng\n- Nút bấm silent, không gây ồn\n- Thiết kế đối xứng, dùng được cả hai tay',
    specifications = '[{"name":"Cảm biến","value":"4000 DPI"},{"name":"Kết nối","value":"2.4GHz receiver"},{"name":"Pin","value":"1 x AA"}]',
    options = '[{"name":"Màu","values":["Đen","Trắng"]}]'
WHERE id = md5('spu:22222222-2222-2222-2222-222222222222')::uuid;

UPDATE product_sku SET sku_code = 'MS24-BLK', attributes = '{"Màu":"Đen"}', position = 0
WHERE id = '22222222-2222-2222-2222-222222222222';

INSERT INTO product_sku (id, product_id, sku_code, attributes, price_vnd, position, created_at, updated_at) VALUES
('22222222-2222-2222-2222-000000000002', md5('spu:22222222-2222-2222-2222-222222222222')::uuid, 'MS24-WHT', '{"Màu":"Trắng"}', 450000, 1, now(), now());

-- ---------------------------------------------------------------- Sản phẩm một SKU (giữ nguyên cho kịch bản demo)
UPDATE product SET slug = '27-inch-monitor', brand = 'Webshop Display',
    short_description = 'Màn hình 27 inch 2K, 75Hz — tồn kho ít để demo hết hàng',
    description = E'Màn hình **27 inch** độ phân giải 2560×1440, tấm nền IPS.\n\nTồn kho cố ý rất ít (2 chiếc) để demo kịch bản *hết hàng* khi checkout.',
    specifications = '[{"name":"Kích thước","value":"27 inch"},{"name":"Độ phân giải","value":"2560 x 1440"},{"name":"Tần số quét","value":"75Hz"}]'
WHERE id = md5('spu:33333333-3333-3333-3333-333333333333')::uuid;
UPDATE product_sku SET sku_code = 'MON27-2K' WHERE id = '33333333-3333-3333-3333-333333333333';

UPDATE product SET slug = 'premium-laptop', brand = 'Webshop Pro',
    short_description = 'Laptop giá cao để demo thiếu credit trong ví',
    description = E'Laptop cao cấp. Giá **25.000.000 ₫** — cao hơn số dư ví mặc định để demo kịch bản *thanh toán bị từ chối* và Saga bồi hoàn.',
    specifications = '[{"name":"CPU","value":"8 nhân"},{"name":"RAM","value":"32GB"},{"name":"SSD","value":"1TB"}]'
WHERE id = md5('spu:44444444-4444-4444-4444-444444444444')::uuid;
UPDATE product_sku SET sku_code = 'LAP-PRO' WHERE id = '44444444-4444-4444-4444-444444444444';

UPDATE product SET slug = 'discontinued-item'
WHERE id = md5('spu:55555555-5555-5555-5555-555555555555')::uuid;
UPDATE product_sku SET sku_code = 'OLD-ITEM' WHERE id = '55555555-5555-5555-5555-555555555555';

-- ---------------------------------------------------------------- Áo thun: 2 trục biến thể (Màu x Size = 6 SKU)
INSERT INTO product (id, slug, name, brand, short_description, description, specifications, options, active, created_at, updated_at) VALUES
('a0000000-0000-4000-8000-000000000006', 'ao-thun-cotton-basic', 'Áo thun Cotton Basic', 'Webshop Wear',
 'Áo thun 100% cotton, form regular',
 E'Áo thun **100% cotton** 220gsm, thấm hút tốt, không xù lông sau nhiều lần giặt.\n\n### Bảng size\n\n| Size | Ngực (cm) | Dài áo (cm) |\n|---|---|---|\n| M | 96 | 68 |\n| L | 102 | 71 |\n| XL | 108 | 74 |',
 '[{"name":"Chất liệu","value":"100% cotton 220gsm"},{"name":"Form","value":"Regular"},{"name":"Xuất xứ","value":"Việt Nam"}]',
 '[{"name":"Màu","values":["Đen","Trắng"]},{"name":"Size","values":["M","L","XL"]}]',
 TRUE, now(), now());

INSERT INTO product_sku (id, product_id, sku_code, attributes, price_vnd, position, created_at, updated_at) VALUES
('66666666-6666-6666-6666-000000000001', 'a0000000-0000-4000-8000-000000000006', 'TEE-BLK-M',  '{"Màu":"Đen","Size":"M"}',    199000, 0, now(), now()),
('66666666-6666-6666-6666-000000000002', 'a0000000-0000-4000-8000-000000000006', 'TEE-BLK-L',  '{"Màu":"Đen","Size":"L"}',    199000, 1, now(), now()),
('66666666-6666-6666-6666-000000000003', 'a0000000-0000-4000-8000-000000000006', 'TEE-BLK-XL', '{"Màu":"Đen","Size":"XL"}',   219000, 2, now(), now()),
('66666666-6666-6666-6666-000000000004', 'a0000000-0000-4000-8000-000000000006', 'TEE-WHT-M',  '{"Màu":"Trắng","Size":"M"}',  199000, 3, now(), now()),
('66666666-6666-6666-6666-000000000005', 'a0000000-0000-4000-8000-000000000006', 'TEE-WHT-L',  '{"Màu":"Trắng","Size":"L"}',  199000, 4, now(), now()),
('66666666-6666-6666-6666-000000000006', 'a0000000-0000-4000-8000-000000000006', 'TEE-WHT-XL', '{"Màu":"Trắng","Size":"XL"}', 219000, 5, now(), now());

-- ---------------------------------------------------------------- Tai nghe: 2 màu, có giá gạch ngang
INSERT INTO product (id, slug, name, brand, short_description, description, specifications, options, active, created_at, updated_at) VALUES
('a0000000-0000-4000-8000-000000000007', 'tai-nghe-bluetooth-anc', 'Tai nghe Bluetooth ANC', 'Webshop Audio',
 'Chống ồn chủ động, pin 30 giờ',
 E'Tai nghe chụp tai với **chống ồn chủ động (ANC)**, Bluetooth 5.3.\n\n- Pin 30 giờ (ANC bật), sạc nhanh 10 phút dùng 5 giờ\n- Kết nối đồng thời 2 thiết bị\n- Đệm tai protein mềm, đeo lâu không đau',
 '[{"name":"Bluetooth","value":"5.3"},{"name":"Pin","value":"30 giờ"},{"name":"Trọng lượng","value":"250g"}]',
 '[{"name":"Màu","values":["Đen","Bạc"]}]',
 TRUE, now(), now());

INSERT INTO product_sku (id, product_id, sku_code, attributes, price_vnd, compare_at_price_vnd, position, created_at, updated_at) VALUES
('77777777-7777-7777-7777-000000000001', 'a0000000-0000-4000-8000-000000000007', 'ANC-BLK', '{"Màu":"Đen"}', 1290000, 1590000, 0, now(), now()),
('77777777-7777-7777-7777-000000000002', 'a0000000-0000-4000-8000-000000000007', 'ANC-SLV', '{"Màu":"Bạc"}', 1290000, 1590000, 1, now(), now());
