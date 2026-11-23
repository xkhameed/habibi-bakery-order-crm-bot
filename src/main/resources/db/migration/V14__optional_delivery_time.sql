-- Sana/vaqt tanlash hozircha olib tashlandi: vaqt bo'lmasa — «iloji boricha tezroq»
ALTER TABLE orders ALTER COLUMN delivery_time DROP NOT NULL;
