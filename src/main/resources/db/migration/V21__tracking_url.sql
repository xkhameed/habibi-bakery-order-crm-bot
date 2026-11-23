-- Yandex kuryerini kuzatish havolasi (admin «Yo'lga chiqdi» bosganda yuboradi)
ALTER TABLE orders ADD COLUMN tracking_url varchar(500);
