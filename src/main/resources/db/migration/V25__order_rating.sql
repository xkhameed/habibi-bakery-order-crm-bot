-- Mijoz buyurtmani olgach bergan bahosi (1–5) va izohi
ALTER TABLE orders ADD COLUMN rating smallint;
ALTER TABLE orders ADD COLUMN rating_comment varchar(500);
