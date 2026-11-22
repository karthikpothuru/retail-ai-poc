
-- Independent consent for external AI model processing.
-- Existing customers remain opted out by default.

ALTER TABLE customer_preferences
    ADD COLUMN ai_processing_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ai_processing_epoch UUID NOT NULL DEFAULT gen_random_uuid();
