-- O'zbek lotin yozuvining yangi alifbosi (2026-yil 21-oktabr qonuni): Oʻ→Ö, Gʻ→Ğ, Sh→Ş, Ch→Ç
-- Brend nomlari (Chocoboom, chocoberry) o'zgarmaydi.
update category set name = replace(replace(replace(replace(replace(replace(replace(replace(name,
    'O''', 'Ö'), 'o''', 'ö'), 'G''', 'Ğ'), 'g''', 'ğ'), 'Sh', 'Ş'), 'sh', 'ş'), 'Ch', 'Ç'), 'ch', 'ç');

update product set name = replace(replace(replace(replace(replace(replace(replace(replace(name,
    'O''', 'Ö'), 'o''', 'ö'), 'G''', 'Ğ'), 'g''', 'ğ'), 'Sh', 'Ş'), 'sh', 'ş'), 'Ch', 'Ç'), 'ch', 'ç')
 where lower(name) not like '%choco%';

update product set description = replace(replace(replace(replace(replace(replace(replace(replace(replace(description,
    'O''', 'Ö'), 'o''', 'ö'), 'G''', 'Ğ'), 'g''', 'ğ'), 'Sh', 'Ş'), 'sh', 'ş'), 'Ch', 'Ç'), 'ch', 'ç'), '''', 'ʼ')
 where description is not null;

update product_variant set name = replace(replace(replace(replace(replace(replace(replace(replace(name,
    'O''', 'Ö'), 'o''', 'ö'), 'G''', 'Ğ'), 'g''', 'ğ'), 'Sh', 'Ş'), 'sh', 'ş'), 'Ch', 'Ç'), 'ch', 'ç')
 where name is not null;
