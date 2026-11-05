ALTER TABLE user_accounts
    ADD COLUMN password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_sessions
    ADD COLUMN mfa_verified_at TIMESTAMPTZ,
    ADD COLUMN active_organisation_id UUID REFERENCES organisations(id),
    ADD COLUMN device_name VARCHAR(120);

CREATE INDEX user_sessions_active_organisation_idx
    ON user_sessions (active_organisation_id, user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE mfa_totp_factors (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    label VARCHAR(80) NOT NULL,
    secret_ciphertext VARCHAR(200) NOT NULL,
    secret_nonce VARCHAR(60) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    last_used_time_step BIGINT,
    CONSTRAINT mfa_totp_factors_status_valid CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    CONSTRAINT mfa_totp_factors_verified_state CHECK (
        (status = 'PENDING' AND verified_at IS NULL)
        OR (status IN ('ACTIVE', 'REVOKED') AND verified_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX mfa_totp_factors_one_pending_per_user
    ON mfa_totp_factors (user_id)
    WHERE status = 'PENDING';

CREATE INDEX mfa_totp_factors_active_user_idx
    ON mfa_totp_factors (user_id)
    WHERE status = 'ACTIVE';

CREATE TABLE mfa_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE INDEX mfa_recovery_codes_available_user_idx
    ON mfa_recovery_codes (user_id)
    WHERE used_at IS NULL;

CREATE TABLE mfa_login_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    remember_session BOOLEAN NOT NULL DEFAULT FALSE,
    user_agent_summary VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT mfa_login_challenges_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT mfa_login_challenges_attempts_valid CHECK (attempt_count BETWEEN 0 AND 5)
);

CREATE INDEX mfa_login_challenges_active_user_idx
    ON mfa_login_challenges (user_id, expires_at)
    WHERE consumed_at IS NULL;

CREATE OR REPLACE FUNCTION prevent_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$;

CREATE TRIGGER audit_events_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_mutation();

COMMENT ON TABLE mfa_totp_factors IS
    'Encrypted TOTP factors. Plaintext secrets are returned once during pending enrolment and are never stored.';
COMMENT ON TABLE mfa_recovery_codes IS
    'Single-use, high-entropy recovery codes stored only as SHA-256 hashes.';
COMMENT ON TABLE mfa_login_challenges IS
    'Short-lived, single-use second-factor challenges issued only after password verification.';
COMMENT ON TRIGGER audit_events_prevent_update_or_delete ON audit_events IS
    'Application-visible audit records are immutable; production database ownership remains separated from the runtime role.';
