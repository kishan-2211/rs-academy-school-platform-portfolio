-- An account preference is deliberately nullable until the user makes a choice.
-- Existing school records, authentication and memberships are unchanged.
ALTER TABLE user_accounts ADD COLUMN preferred_language VARCHAR(16);
ALTER TABLE user_accounts ADD CONSTRAINT user_accounts_preferred_language_valid
    CHECK (preferred_language IS NULL OR preferred_language IN ('en', 'hi'));
