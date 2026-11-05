-- Preserve cancelled setup and replaced recovery batches without granting runtime DELETE.
-- Never fabricate a verification time for a factor that was never activated.
ALTER TABLE mfa_totp_factors
    DROP CONSTRAINT mfa_totp_factors_verified_state,
    ADD CONSTRAINT mfa_totp_factors_verified_state CHECK (
        (status = 'PENDING' AND verified_at IS NULL)
        OR (status = 'ACTIVE' AND verified_at IS NOT NULL)
        OR status = 'REVOKED'
    );

ALTER TABLE mfa_recovery_codes ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE INDEX mfa_recovery_codes_unrevoked_user_idx
    ON mfa_recovery_codes (user_id)
    WHERE used_at IS NULL AND revoked_at IS NULL;

COMMENT ON COLUMN mfa_recovery_codes.revoked_at IS
    'Time an unused recovery code was superseded; used_at remains actual redemption evidence.';
