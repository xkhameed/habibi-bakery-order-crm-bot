-- Aksiya: mahsulotga foizli chegirma (0 — aksiya yo'q)
alter table product add column discount_percent int not null default 0;
