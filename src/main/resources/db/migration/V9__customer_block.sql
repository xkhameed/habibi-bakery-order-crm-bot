-- Admin mijozni bloklashi mumkin (ma'lumotlari va buyurtmalari saqlanadi)
alter table customer add column blocked boolean not null default false;
alter table customer add column blocked_at timestamp with time zone;
