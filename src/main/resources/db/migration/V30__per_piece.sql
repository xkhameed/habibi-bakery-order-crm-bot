-- Donada sotiladigan mahsulotlar (narx yonida «/ dona» ko'rinadi)
ALTER TABLE product ADD COLUMN per_piece boolean NOT NULL DEFAULT false;
