-- Oxirgi yuborilgan chek rasmi (Telegram file_id) — admin keyin buyurtmani ochganda ko'rsatiladi
ALTER TABLE orders ADD COLUMN receipt_file_id varchar(255);
