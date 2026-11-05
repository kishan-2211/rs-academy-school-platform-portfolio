ALTER TABLE memberships
    ADD CONSTRAINT memberships_push_actor_scope_unique
    UNIQUE (id, user_id, organisation_id, school_id);

CREATE TABLE communication_push_subscriptions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    user_account_id UUID NOT NULL REFERENCES user_accounts(id),
    endpoint_hash CHAR(64) NOT NULL,
    endpoint_ciphertext TEXT,
    endpoint_nonce VARCHAR(32),
    p256dh_ciphertext TEXT,
    p256dh_nonce VARCHAR(32),
    auth_ciphertext TEXT,
    auth_nonce VARCHAR(32),
    device_label VARCHAR(80),
    status VARCHAR(20) NOT NULL,
    registered_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_confirmed_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(160),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_push_subscriptions_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT communication_push_subscriptions_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT communication_push_subscriptions_actor_fk
        FOREIGN KEY (
            registered_by_membership_id, user_account_id, organisation_id, school_id
        ) REFERENCES memberships(id, user_id, organisation_id, school_id),
    CONSTRAINT communication_push_subscriptions_hash_valid CHECK (
        endpoint_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT communication_push_subscriptions_label_valid CHECK (
        device_label IS NULL OR (
            length(btrim(device_label)) BETWEEN 1 AND 80
            AND device_label !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT communication_push_subscriptions_status_valid CHECK (
        status IN ('ACTIVE','REVOKED','EXPIRED')
    ),
    CONSTRAINT communication_push_subscriptions_time_valid CHECK (
        last_confirmed_at >= created_at
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    ),
    CONSTRAINT communication_push_subscriptions_state_valid CHECK (
        (status = 'ACTIVE'
            AND endpoint_ciphertext IS NOT NULL AND endpoint_nonce IS NOT NULL
            AND p256dh_ciphertext IS NOT NULL AND p256dh_nonce IS NOT NULL
            AND auth_ciphertext IS NOT NULL AND auth_nonce IS NOT NULL
            AND revoked_at IS NULL AND revoked_reason IS NULL)
        OR (status IN ('REVOKED','EXPIRED')
            AND endpoint_ciphertext IS NULL AND endpoint_nonce IS NULL
            AND p256dh_ciphertext IS NULL AND p256dh_nonce IS NULL
            AND auth_ciphertext IS NULL AND auth_nonce IS NULL
            AND revoked_at IS NOT NULL
            AND length(btrim(revoked_reason)) BETWEEN 3 AND 160)
    )
);

CREATE UNIQUE INDEX communication_push_subscriptions_active_endpoint_idx
    ON communication_push_subscriptions (school_id, endpoint_hash)
    WHERE status = 'ACTIVE';
CREATE INDEX communication_push_subscriptions_user_status_idx
    ON communication_push_subscriptions (
        organisation_id, school_id, user_account_id, status, last_confirmed_at DESC, id
    );

CREATE TABLE communication_push_subscription_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_push_subscription_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES user_accounts(id),
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    safe_reason VARCHAR(160),
    CONSTRAINT communication_push_subscription_events_subscription_fk
        FOREIGN KEY (communication_push_subscription_id, organisation_id, school_id)
        REFERENCES communication_push_subscriptions(id, organisation_id, school_id),
    CONSTRAINT communication_push_subscription_events_actor_fk
        FOREIGN KEY (actor_membership_id, actor_user_id, organisation_id, school_id)
        REFERENCES memberships(id, user_id, organisation_id, school_id),
    CONSTRAINT communication_push_subscription_events_type_valid CHECK (
        event_type IN ('REGISTERED','REFRESHED','REVOKED','EXPIRED')
    ),
    CONSTRAINT communication_push_subscription_events_reason_valid CHECK (
        safe_reason IS NULL OR length(btrim(safe_reason)) BETWEEN 3 AND 160
    )
);

CREATE INDEX communication_push_subscription_events_subscription_idx
    ON communication_push_subscription_events (
        communication_push_subscription_id, occurred_at DESC, id
    );

CREATE OR REPLACE FUNCTION guard_communication_push_subscription()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'push subscription evidence cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.row_version <> 0 THEN
            RAISE EXCEPTION 'push subscriptions must begin active';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.user_account_id <> NEW.user_account_id
       OR OLD.endpoint_hash <> NEW.endpoint_hash
       OR OLD.registered_by_membership_id <> NEW.registered_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'push subscription identity is immutable';
    END IF;
    IF OLD.status IN ('REVOKED','EXPIRED') THEN
        RAISE EXCEPTION 'terminal push subscriptions are immutable';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE','REVOKED','EXPIRED') THEN
        RAISE EXCEPTION 'invalid push subscription transition';
    END IF;
    IF NEW.last_confirmed_at < OLD.last_confirmed_at OR NEW.row_version <= OLD.row_version THEN
        RAISE EXCEPTION 'push subscription refresh evidence cannot move backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER communication_push_subscriptions_guard
    BEFORE INSERT OR UPDATE OR DELETE ON communication_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION guard_communication_push_subscription();

CREATE TRIGGER communication_push_subscription_events_append_only
    BEFORE UPDATE OR DELETE ON communication_push_subscription_events
    FOR EACH ROW EXECUTE FUNCTION prevent_communication_evidence_change();

ALTER TABLE communication_push_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_push_subscription_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY communication_push_subscriptions_tenant_all
    ON communication_push_subscriptions
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_push_subscription_events_tenant_all
    ON communication_push_subscription_events
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE communication_push_subscriptions IS
    'School-scoped browser push capability material. Endpoints and browser keys are AES-GCM encrypted and erased on terminal state; only a SHA-256 lookup hash remains.';
COMMENT ON TABLE communication_push_subscription_events IS
    'Append-only, destination-free device registration lifecycle evidence. Provider delivery attempts remain in the shared communication delivery ledger.';
