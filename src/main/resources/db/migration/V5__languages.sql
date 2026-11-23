-- Uch til: o'zbek (lotin), o'zbek (kirill — avtomatik), rus
alter table customer add column language varchar(5);

alter table category add column name_ru varchar(100);
alter table product add column name_ru varchar(150);
alter table product add column description_ru varchar(500);
alter table product_variant add column name_ru varchar(60);

-- Buyurtma paytidagi ruscha nom (mijozga xabar uchun)
alter table order_item add column product_name_ru varchar(150);
alter table order_item add column variant_name_ru varchar(60);

update category set name_ru = 'Торты' where name = 'Tortlar';
update category set name_ru = 'Дамские пальчики' where name = 'Damskiy palchik';
update category set name_ru = 'Трайфлы' where name = 'Trayfellar';
update category set name_ru = 'Шоколадные десерты' where name = 'Shokoladli shirinliklar';
update category set name_ru = 'Подарочные наборы' where name = 'Sovg''a setlari';

update product set name_ru = 'Торт «Дубай»', description_ru = 'Знаменитый торт «Дубай» с фисташкой и шоколадом' where name = 'Dubai torti';
update product set name_ru = 'Торт «Сникерс»', description_ru = 'Арахис, карамель и шоколад' where name = 'Snikers torti';
update product set name_ru = 'Меренговый торт', description_ru = 'Лёгкий воздушный торт с безе' where name = 'Merengiviy tort';
update product set name_ru = 'Торт «Пчелиное гнездо»', description_ru = 'Классический слоёный медовый торт' where name = 'Ari ini torti';
update product set name_ru = 'Дамские пальчики', description_ru = 'Фирменный рецепт Habibi Bakery — самый любимый десерт' where name = 'Damskiy palchik';
update product set name_ru = 'Матильда «Дубай»', description_ru = 'Шоколадный десерт в стиле «Дубай»' where name = 'Dubai matilda';
update product set name_ru = 'Клубничный трайфл' where name = 'Qulupnayli trayfel';
update product set name_ru = 'Банановый трайфл' where name = 'Bananli trayfel';
update product set name_ru = 'Малиновый трайфл' where name = 'Malinali trayfel';
update product set name_ru = 'Трайфл «Сникерс»' where name = 'Snikers trayfel';
update product set name_ru = 'Микс банан-малина' where name = 'Banan-malina miks';
update product set name_ru = 'Микс клубника-банан' where name = 'Qulupnay-banan miks';
update product set name_ru = 'Трайфл «Дубай»' where name = 'Dubai trayfel';
update product set name_ru = 'Клубника в шоколаде', description_ru = 'Свежая клубника в шоколадной глазури' where name = 'Shokoladli qulupnay';
update product set name_ru = 'Малиновый десерт' where name = 'Malinali desert';
update product set name_ru = 'Десерт с голубикой' where name = 'Golubikali desert';
update product set name_ru = 'Вишнёвый десерт' where name = 'Olchali desert';
update product set name_ru = replace(name, 'Habibi set', 'Набор Habibi'),
                   description_ru = 'Подарочная коробка с ассорти десертов'
 where name like 'Habibi set%';

update product_variant set name_ru = 'Целый' where name = 'Butun';
update product_variant set name_ru = 'Половина' where name = 'Yarim';
update product_variant set name_ru = 'Четверть' where name = 'Chorak';
update product_variant set name_ru = 'Большой' where name = 'Katta';
update product_variant set name_ru = 'Средний' where name = 'O''rtacha';
update product_variant set name_ru = 'Маленький' where name = 'Kichik';
update product_variant set name_ru = '1 шт.' where name = '1 dona';
