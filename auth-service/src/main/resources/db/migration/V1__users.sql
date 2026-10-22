CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    roles         VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Email đã được chuẩn hóa lowercase ở tầng ứng dụng; unique index là hàng rào cuối chống đăng ký trùng đồng thời.
CREATE UNIQUE INDEX ux_users_email ON users (email);
