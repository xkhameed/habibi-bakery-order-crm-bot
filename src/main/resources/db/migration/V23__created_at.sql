-- «Yangi» belgisi uchun: qachon qo'shilgan (eskilarida bo'sh)
ALTER TABLE product ADD COLUMN created_at timestamp with time zone;
ALTER TABLE category ADD COLUMN created_at timestamp with time zone;
