-- Mijoz buyurtma berish shartlarini qachon tasdiqlagani (NULL — hali tasdiqlamagan)
ALTER TABLE customer ADD COLUMN terms_accepted_at timestamp with time zone;
