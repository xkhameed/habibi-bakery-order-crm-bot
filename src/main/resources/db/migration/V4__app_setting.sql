-- Admin bot orqali o'zgartiradigan sozlamalar (masalan, Click/Payme QR rasmi)
create table app_setting (
    setting_key   varchar(100) primary key,
    setting_value varchar(500) not null
);
