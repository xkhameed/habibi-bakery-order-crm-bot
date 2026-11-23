-- «Qabul qildim»: yo'lga chiqqan vaqt, mijozdan keyingi so'rov, javoblar va kim yopgani
ALTER TABLE orders ADD COLUMN on_way_at timestamp with time zone;
ALTER TABLE orders ADD COLUMN next_ask_at timestamp with time zone;
ALTER TABLE orders ADD COLUMN delivery_asks int NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN not_arrived_at timestamp with time zone;
-- ADMIN / CUSTOMER / AUTO
ALTER TABLE orders ADD COLUMN delivered_by varchar(20);
