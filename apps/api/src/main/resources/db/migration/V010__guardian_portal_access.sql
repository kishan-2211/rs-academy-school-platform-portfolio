INSERT INTO capabilities (code, description, risk_level) VALUES
    ('guardian.portal.invite', 'Issue, revoke, and review guardian portal invitations', 'RESTRICTED'),
    ('portal.guardian', 'Use guardian self-service only for explicitly activated learner relationships', 'SENSITIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (
    id, organisation_id, code, display_name, description, system_role,
    created_at, updated_at, row_version
) VALUES (
    '00000000-0000-4000-8000-000000000006', NULL, 'parent_guardian',
    'Parent or guardian',
    'Relationship-scoped family portal access without staff learner-directory permissions.',
    TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
)
ON CONFLICT (organisation_id, code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'guardian.portal.invite'),
    ('00000000-0000-4000-8000-000000000006', 'portal.guardian')
ON CONFLICT DO NOTHING;

ALTER TABLE guardians
    ADD COLUMN user_account_id UUID REFERENCES user_accounts(id);

CREATE UNIQUE INDEX guardians_school_user_account_unique
    ON guardians (school_id, user_account_id)
    WHERE user_account_id IS NOT NULL;

ALTER TABLE learner_guardian_relationships
    ADD CONSTRAINT learner_guardian_relationships_scope_unique
        UNIQUE (learner_id, guardian_id, organisation_id, school_id);

CREATE TABLE guardian_portal_invitations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    guardian_id UUID NOT NULL,
    target_email_normalized VARCHAR(254) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    token_ciphertext VARCHAR(200),
    token_nonce VARCHAR(60),
    status VARCHAR(20) NOT NULL,
    delivery_status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    accepted_user_id UUID REFERENCES user_accounts(id),
    revoked_at TIMESTAMPTZ,
    revoked_by_membership_id UUID,
    revoked_reason VARCHAR(240),
    acceptance_attempt_count SMALLINT NOT NULL DEFAULT 0,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT guardian_portal_invitations_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT guardian_portal_invitations_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT guardian_portal_invitations_relationship_fk
        FOREIGN KEY (learner_id, guardian_id, organisation_id, school_id)
        REFERENCES learner_guardian_relationships(learner_id, guardian_id, organisation_id, school_id),
    CONSTRAINT guardian_portal_invitations_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT guardian_portal_invitations_revoker_fk
        FOREIGN KEY (revoked_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT guardian_portal_invitations_email_normalized CHECK (
        target_email_normalized = lower(target_email_normalized)
    ),
    CONSTRAINT guardian_portal_invitations_token_hash_valid CHECK (
        token_hash ~ '^[a-f0-9]{64}$'
    ),
    CONSTRAINT guardian_portal_invitations_status_valid CHECK (
        status IN ('ISSUED', 'ACCEPTED', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT guardian_portal_invitations_delivery_valid CHECK (
        delivery_status IN ('AWAITING_PROVIDER', 'QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT guardian_portal_invitations_attempts_valid CHECK (
        acceptance_attempt_count BETWEEN 0 AND 10
    ),
    CONSTRAINT guardian_portal_invitations_expiry_valid CHECK (
        expires_at > created_at
    ),
    CONSTRAINT guardian_portal_invitations_state_valid CHECK (
        (status = 'ISSUED'
            AND token_ciphertext IS NOT NULL AND token_nonce IS NOT NULL
            AND accepted_at IS NULL AND accepted_user_id IS NULL
            AND revoked_at IS NULL AND revoked_by_membership_id IS NULL AND revoked_reason IS NULL)
        OR (status = 'ACCEPTED'
            AND token_ciphertext IS NULL AND token_nonce IS NULL
            AND accepted_at IS NOT NULL AND accepted_user_id IS NOT NULL
            AND revoked_at IS NULL AND revoked_by_membership_id IS NULL AND revoked_reason IS NULL)
        OR (status = 'REVOKED'
            AND token_ciphertext IS NULL AND token_nonce IS NULL
            AND accepted_at IS NULL AND accepted_user_id IS NULL
            AND revoked_at IS NOT NULL AND revoked_by_membership_id IS NOT NULL
            AND length(btrim(revoked_reason)) BETWEEN 1 AND 240)
        OR (status = 'EXPIRED'
            AND token_ciphertext IS NULL AND token_nonce IS NULL
            AND accepted_at IS NULL AND accepted_user_id IS NULL
            AND revoked_at IS NULL AND revoked_by_membership_id IS NULL AND revoked_reason IS NULL)
    )
);

CREATE UNIQUE INDEX guardian_portal_invitations_one_open_relationship_idx
    ON guardian_portal_invitations (learner_id, guardian_id)
    WHERE status = 'ISSUED';

CREATE INDEX guardian_portal_invitations_school_status_idx
    ON guardian_portal_invitations (organisation_id, school_id, status, created_at DESC);
CREATE INDEX guardian_portal_invitations_expiry_idx
    ON guardian_portal_invitations (expires_at)
    WHERE status = 'ISSUED';

ALTER TABLE guardian_portal_invitations ENABLE ROW LEVEL SECURITY;

CREATE POLICY guardian_portal_invitations_tenant_select ON guardian_portal_invitations
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY guardian_portal_invitations_tenant_insert ON guardian_portal_invitations
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY guardian_portal_invitations_tenant_update ON guardian_portal_invitations
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY guardian_portal_invitations_token_select ON guardian_portal_invitations
    FOR SELECT USING (
        status = 'ISSUED'
        AND token_hash = NULLIF(current_setting('app.current_invitation_token_hash', TRUE), '')
    );
CREATE POLICY guardian_portal_invitations_token_update ON guardian_portal_invitations
    FOR UPDATE USING (
        status = 'ISSUED'
        AND token_hash = NULLIF(current_setting('app.current_invitation_token_hash', TRUE), '')
    )
    WITH CHECK (
        token_hash = NULLIF(current_setting('app.current_invitation_token_hash', TRUE), '')
    );

COMMENT ON COLUMN guardians.user_account_id IS
    'Verified global identity linked only after single-use guardian invitation acceptance.';
COMMENT ON TABLE guardian_portal_invitations IS
    'Single-relationship, expiring guardian access grants. Raw tokens are never stored and encrypted delivery material is cleared on terminal state.';
