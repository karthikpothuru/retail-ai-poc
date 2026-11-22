ALTER TABLE customer_preferences
    ADD COLUMN personalization_epoch uuid NOT NULL DEFAULT gen_random_uuid();
