-- «Sovğa setlari» → «Setlar», tavsifda «sovğabop quti» deyilmaydi
UPDATE category SET name = 'Setlar', name_ru = 'Наборы' WHERE emoji = '🎁' AND name = 'Sovğa setlari';
UPDATE product SET description = 'Turli şirinliklardan yiğilgan set', description_ru = 'Набор из разных десертов'
WHERE description = 'Turli şirinliklardan yiğilgan sovğabop quti';
