-- Remove empty hidden categories left after the menu restructure (V29).
DELETE FROM category c
WHERE c.name IN ('Damskiy palçik', 'Trayfellar', 'Şokoladli şirinliklar')
  AND NOT EXISTS (SELECT 1 FROM product p WHERE p.category_id = c.id);
