-- Mijozning saqlangan manzillari («Mening manzillarim», 3 tagacha)
CREATE TABLE customer_address (
    id          bigserial PRIMARY KEY,
    telegram_id bigint       NOT NULL,
    label       varchar(40)  NOT NULL,
    latitude    double precision NOT NULL,
    longitude   double precision NOT NULL,
    map_label   varchar(300),
    address     varchar(500),
    created_at  timestamp with time zone NOT NULL
);
CREATE INDEX idx_address_customer ON customer_address (telegram_id);
