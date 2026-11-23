-- Mijoz profili: botda so'ralgan ism va Telegram orqali tasdiqlangan raqam
alter table customer add column name varchar(100);
alter table customer add column phone_verified boolean not null default false;
