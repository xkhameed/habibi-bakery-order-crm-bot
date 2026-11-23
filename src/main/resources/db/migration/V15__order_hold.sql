-- Buyurtma adminga faqat 5 daqiqalik bekor qilish vaqti o'tib, chek kelgandan keyin yuboriladi
ALTER TABLE orders ADD COLUMN admin_notified boolean NOT NULL DEFAULT true;
-- Vaqt o'tguncha ushlab turilgan chek (botdan — file_id, Mini App'dan — base64 rasm)
ALTER TABLE orders ADD COLUMN held_receipt_file_id varchar(255);
ALTER TABLE orders ADD COLUMN held_receipt_image TEXT;
