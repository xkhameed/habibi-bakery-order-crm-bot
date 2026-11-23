-- Mahsulotga 1–5 yulduzli baho (har mijoz har mahsulotga bitta, keyin o'zgartirishi mumkin)
CREATE TABLE product_rating (
    id          bigserial PRIMARY KEY,
    product_id  bigint   NOT NULL REFERENCES product (id),
    telegram_id bigint   NOT NULL,
    stars       smallint NOT NULL CHECK (stars BETWEEN 1 AND 5),
    created_at  timestamp with time zone NOT NULL,
    updated_at  timestamp with time zone NOT NULL,
    UNIQUE (product_id, telegram_id)
);
CREATE INDEX idx_rating_product ON product_rating (product_id);
