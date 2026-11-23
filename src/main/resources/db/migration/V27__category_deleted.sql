-- O'chirilgan kategoriya (bo'sh bo'lsagina o'chiriladi; eski o'chirilgan mahsulotlar uchun yozuv qoladi)
ALTER TABLE category ADD COLUMN deleted boolean NOT NULL DEFAULT false;
