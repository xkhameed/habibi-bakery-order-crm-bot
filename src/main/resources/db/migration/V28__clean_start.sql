-- Haqiqiy ishga tushirish uchun toza boshlanish (2026-11-12, egasining so'rovi bilan):
-- barcha sinov mahsulotlari, narxlari, foydalanuvchilar, buyurtmalar, cheklar, baholar, like va manzillar o'chiriladi.
-- Faqat kategoriyalar qoladi: Tortlar, Trayfellar, Damskiy palçik, Şokoladli şirinliklar, Setlar + yangi «Sovğa setlari».
-- Sozlamalar (karta, ish vaqti, bosh rasm, adminlar) saqlanadi.
DELETE FROM product_rating;
DELETE FROM product_like;
DELETE FROM customer_address;
DELETE FROM order_item;
DELETE FROM orders;
DELETE FROM customer;
DELETE FROM product_variant;
DELETE FROM product;

DELETE FROM category WHERE name NOT IN ('Tortlar', 'Trayfellar', 'Damskiy palçik', 'Şokoladli şirinliklar', 'Setlar');
UPDATE category SET active = true, deleted = false;
UPDATE category SET sort_order = 1 WHERE name = 'Tortlar';
UPDATE category SET sort_order = 2 WHERE name = 'Trayfellar';
UPDATE category SET sort_order = 3 WHERE name = 'Damskiy palçik';
UPDATE category SET sort_order = 4 WHERE name = 'Şokoladli şirinliklar';
UPDATE category SET sort_order = 5 WHERE name = 'Setlar';
INSERT INTO category (name, name_ru, emoji, sort_order, active, deleted)
VALUES ('Sovğa setlari', 'Подарочные наборы', '🎁', 6, true, false);
