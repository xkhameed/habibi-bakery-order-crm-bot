-- O'chirilgan mahsulot menyudan yo'qoladi, lekin eski buyurtmalar uchun saqlanadi
ALTER TABLE product ADD COLUMN archived boolean NOT NULL DEFAULT false;
-- Qo'shimcha adminlarga yuborilgan buyurtma kartochkalari: "chatId:messageId,chatId:messageId"
ALTER TABLE orders ADD COLUMN admin_copies varchar(500);
