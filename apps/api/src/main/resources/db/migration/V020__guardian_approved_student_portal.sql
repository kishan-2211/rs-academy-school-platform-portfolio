INSERT INTO capabilities (code, description, risk_level) VALUES
    ('student.portal.manage', 'Prepare, suspend, resume, and end guardian-approved student portal access', 'RESTRICTED'),
    ('portal.student', 'Use the learner-owned portal only for the linked learner record', 'SENSITIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (
    id, organisation_id, code, display_name, description, system_role,
    created_at, updated_at, row_version
) VALUES (
    '00000000-0000-4000-8000-000000000010', NULL, 'student', 'Student',
    'Own-record-only learning access. This role never grants learner-directory, guardian, fee, safeguarding, health, or staff authority.',
    TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
)
ON CONFLICT (organisation_id, code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'student.portal.manage'),
    ('00000000-0000-4000-8000-000000000007', 'student.portal.manage'),
    ('00000000-0000-4000-8000-000000000010', 'portal.student')
ON CONFLICT DO NOTHING;

ALTER TABLE user_accounts
    ADD COLUMN login_identifier_normalized VARCHAR(254);

UPDATE user_accounts
SET login_identifier_normalized = email_normalized;

ALTER TABLE user_accounts
    ALTER COLUMN login_identifier_normalized SET NOT NULL,
    ALTER COLUMN email_normalized DROP NOT NULL,
    DROP CONSTRAINT user_accounts_email_normalized,
    ADD CONSTRAINT user_accounts_login_identifier_unique UNIQUE (login_identifier_normalized),
    ADD CONSTRAINT user_accounts_login_identifier_normalized CHECK (
        login_identifier_normalized = lower(login_identifier_normalized)
        AND length(login_identifier_normalized) BETWEEN 3 AND 254
        AND login_identifier_normalized !~ '[[:space:][:cntrl:]]'
    ),
    ADD CONSTRAINT user_accounts_email_normalized CHECK (
        email_normalized IS NULL
        OR (email_normalized = lower(email_normalized)
            AND length(email_normalized) BETWEEN 3 AND 254)
    );

CREATE TABLE student_portal_consent_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    guardian_id UUID NOT NULL,
    supersedes_request_id UUID,
    proposed_login_identifier_normalized VARCHAR(80) NOT NULL,
    privacy_notice_version VARCHAR(60) NOT NULL,
    purpose_summary VARCHAR(1000) NOT NULL,
    adult_verification_method VARCHAR(30) NOT NULL,
    adult_verification_reference VARCHAR(160) NOT NULL,
    adult_verified_at TIMESTAMPTZ NOT NULL,
    adult_verified_by_membership_id UUID NOT NULL,
    preparation_reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    guardian_decided_by_user_id UUID REFERENCES user_accounts(id),
    guardian_decided_by_membership_id UUID,
    guardian_decided_at TIMESTAMPTZ,
    guardian_decision_reason VARCHAR(500),
    approved_user_account_id UUID REFERENCES user_accounts(id),
    revoked_by_membership_id UUID,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(500),
    withdrawn_by_user_id UUID REFERENCES user_accounts(id),
    withdrawn_by_membership_id UUID,
    withdrawn_at TIMESTAMPTZ,
    withdrawal_reason VARCHAR(500),
    expired_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT student_portal_consent_requests_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT student_portal_consent_requests_relationship_fk
        FOREIGN KEY (learner_id, guardian_id, organisation_id, school_id)
        REFERENCES learner_guardian_relationships(learner_id, guardian_id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_supersedes_fk
        FOREIGN KEY (supersedes_request_id, organisation_id, school_id)
        REFERENCES student_portal_consent_requests(id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_supersedes_not_self CHECK (
        supersedes_request_id IS NULL OR supersedes_request_id <> id
    ),
    CONSTRAINT student_portal_consent_requests_adult_verifier_fk
        FOREIGN KEY (adult_verified_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_guardian_decider_fk
        FOREIGN KEY (guardian_decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_revoker_fk
        FOREIGN KEY (revoked_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_withdrawer_fk
        FOREIGN KEY (withdrawn_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_portal_consent_requests_login_valid CHECK (
        proposed_login_identifier_normalized = lower(proposed_login_identifier_normalized)
        AND proposed_login_identifier_normalized ~ '^[a-z0-9][a-z0-9._-]{2,79}$'
    ),
    CONSTRAINT student_portal_consent_requests_notice_valid CHECK (
        length(btrim(privacy_notice_version)) BETWEEN 1 AND 60
        AND length(btrim(purpose_summary)) BETWEEN 20 AND 1000
        AND length(btrim(preparation_reason)) BETWEEN 3 AND 500
    ),
    CONSTRAINT student_portal_consent_requests_verification_method_valid CHECK (
        adult_verification_method IN ('SCHOOL_RECORD', 'AUTHORIZED_TOKEN', 'OTHER_REVIEWED')
    ),
    CONSTRAINT student_portal_consent_requests_verification_reference_valid CHECK (
        (adult_verification_method = 'SCHOOL_RECORD'
            AND adult_verification_reference ~ '^school-record:[A-Za-z0-9._/-]{3,140}$')
        OR (adult_verification_method = 'AUTHORIZED_TOKEN'
            AND adult_verification_reference ~ '^authorized-token:[A-Za-z0-9._/-]{3,136}$')
        OR (adult_verification_method = 'OTHER_REVIEWED'
            AND adult_verification_reference ~ '^review-case:[A-Za-z0-9._/-]{3,144}$')
    ),
    CONSTRAINT student_portal_consent_requests_status_valid CHECK (
        status IN ('PENDING', 'APPROVED', 'DECLINED', 'REVOKED', 'WITHDRAWN', 'EXPIRED')
    ),
    CONSTRAINT student_portal_consent_requests_expiry_valid CHECK (
        expires_at > created_at AND expires_at <= created_at + INTERVAL '14 days'
    ),
    CONSTRAINT student_portal_consent_requests_verification_time_valid CHECK (
        adult_verified_at <= created_at
        AND adult_verified_by_membership_id = created_by_membership_id
    ),
    CONSTRAINT student_portal_consent_requests_reason_valid CHECK (
        (guardian_decision_reason IS NULL
            OR length(btrim(guardian_decision_reason)) BETWEEN 3 AND 500)
        AND (revoked_reason IS NULL OR length(btrim(revoked_reason)) BETWEEN 3 AND 500)
        AND (withdrawal_reason IS NULL OR length(btrim(withdrawal_reason)) BETWEEN 3 AND 500)
    ),
    CONSTRAINT student_portal_consent_requests_evidence_time_valid CHECK (
        (guardian_decided_at IS NULL
            OR (guardian_decided_at >= created_at AND guardian_decided_at <= updated_at))
        AND (revoked_at IS NULL OR (revoked_at >= created_at AND revoked_at <= updated_at))
        AND (withdrawn_at IS NULL OR (withdrawn_at >= created_at AND withdrawn_at <= updated_at))
        AND (expired_at IS NULL OR (expired_at >= created_at AND expired_at <= updated_at))
    ),
    CONSTRAINT student_portal_consent_requests_decision_state_valid CHECK (
        (status = 'PENDING'
            AND guardian_decided_by_user_id IS NULL
            AND guardian_decided_by_membership_id IS NULL
            AND guardian_decided_at IS NULL
            AND guardian_decision_reason IS NULL
            AND approved_user_account_id IS NULL
            AND revoked_at IS NULL AND withdrawn_at IS NULL AND expired_at IS NULL)
        OR (status = 'APPROVED'
            AND guardian_decided_by_user_id IS NOT NULL
            AND guardian_decided_by_membership_id IS NOT NULL
            AND guardian_decided_at IS NOT NULL
            AND guardian_decision_reason IS NOT NULL
            AND approved_user_account_id IS NOT NULL
            AND revoked_at IS NULL AND withdrawn_at IS NULL AND expired_at IS NULL)
        OR (status = 'DECLINED'
            AND guardian_decided_by_user_id IS NOT NULL
            AND guardian_decided_by_membership_id IS NOT NULL
            AND guardian_decided_at IS NOT NULL
            AND guardian_decision_reason IS NOT NULL
            AND approved_user_account_id IS NULL
            AND revoked_at IS NULL AND withdrawn_at IS NULL AND expired_at IS NULL)
        OR (status = 'REVOKED'
            AND revoked_by_membership_id IS NOT NULL AND revoked_at IS NOT NULL
            AND revoked_reason IS NOT NULL AND withdrawn_at IS NULL AND expired_at IS NULL
            AND ((guardian_decided_at IS NULL
                    AND guardian_decided_by_user_id IS NULL
                    AND guardian_decided_by_membership_id IS NULL
                    AND approved_user_account_id IS NULL)
                OR (guardian_decided_at IS NOT NULL
                    AND guardian_decided_by_user_id IS NOT NULL
                    AND guardian_decided_by_membership_id IS NOT NULL
                    AND guardian_decision_reason IS NOT NULL
                    AND approved_user_account_id IS NOT NULL)))
        OR (status = 'WITHDRAWN'
            AND guardian_decided_by_user_id IS NOT NULL
            AND guardian_decided_by_membership_id IS NOT NULL
            AND guardian_decided_at IS NOT NULL
            AND approved_user_account_id IS NOT NULL
            AND withdrawn_by_user_id IS NOT NULL
            AND withdrawn_by_membership_id IS NOT NULL
            AND withdrawn_at IS NOT NULL AND withdrawal_reason IS NOT NULL
            AND revoked_by_membership_id IS NULL AND revoked_at IS NULL
            AND revoked_reason IS NULL AND expired_at IS NULL)
        OR (status = 'EXPIRED'
            AND guardian_decided_by_user_id IS NULL
            AND guardian_decided_by_membership_id IS NULL
            AND guardian_decided_at IS NULL AND guardian_decision_reason IS NULL
            AND approved_user_account_id IS NULL
            AND revoked_by_membership_id IS NULL AND revoked_at IS NULL AND revoked_reason IS NULL
            AND withdrawn_by_user_id IS NULL AND withdrawn_by_membership_id IS NULL
            AND withdrawn_at IS NULL AND withdrawal_reason IS NULL
            AND expired_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX student_portal_consent_requests_one_pending_idx
    ON student_portal_consent_requests (school_id, learner_id)
    WHERE status = 'PENDING';
CREATE UNIQUE INDEX student_portal_consent_requests_one_approved_idx
    ON student_portal_consent_requests (school_id, learner_id)
    WHERE status = 'APPROVED';
CREATE INDEX student_portal_consent_requests_guardian_queue_idx
    ON student_portal_consent_requests (school_id, guardian_id, status, expires_at, created_at DESC);

CREATE TABLE learner_portal_accounts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    user_account_id UUID NOT NULL REFERENCES user_accounts(id),
    current_consent_request_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    suspended_at TIMESTAMPTZ,
    suspended_by_membership_id UUID,
    suspension_reason VARCHAR(500),
    ended_at TIMESTAMPTZ,
    ended_by_membership_id UUID,
    ending_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT learner_portal_accounts_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_portal_accounts_learner_unique UNIQUE (learner_id),
    CONSTRAINT learner_portal_accounts_user_unique UNIQUE (user_account_id),
    CONSTRAINT learner_portal_accounts_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT learner_portal_accounts_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_portal_accounts_consent_fk
        FOREIGN KEY (current_consent_request_id, organisation_id, school_id)
        REFERENCES student_portal_consent_requests(id, organisation_id, school_id),
    CONSTRAINT learner_portal_accounts_suspender_fk
        FOREIGN KEY (suspended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_portal_accounts_ender_fk
        FOREIGN KEY (ended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_portal_accounts_status_valid CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'ENDED')
    ),
    CONSTRAINT learner_portal_accounts_state_valid CHECK (
        (status = 'ACTIVE'
            AND suspended_at IS NULL AND suspended_by_membership_id IS NULL
            AND suspension_reason IS NULL AND ended_at IS NULL
            AND ended_by_membership_id IS NULL AND ending_reason IS NULL)
        OR (status = 'SUSPENDED'
            AND suspended_at IS NOT NULL AND suspended_by_membership_id IS NOT NULL
            AND suspension_reason IS NOT NULL AND ended_at IS NULL
            AND ended_by_membership_id IS NULL AND ending_reason IS NULL)
        OR (status = 'ENDED'
            AND ended_at IS NOT NULL AND ending_reason IS NOT NULL)
    ),
    CONSTRAINT learner_portal_accounts_reason_valid CHECK (
        (suspension_reason IS NULL OR length(btrim(suspension_reason)) BETWEEN 3 AND 500)
        AND (ending_reason IS NULL OR length(btrim(ending_reason)) BETWEEN 3 AND 500)
    )
);

CREATE INDEX learner_portal_accounts_school_status_idx
    ON learner_portal_accounts (school_id, status, updated_at DESC);

CREATE TABLE learner_portal_account_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_portal_account_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    consent_request_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES user_accounts(id),
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT learner_portal_account_history_account_fk
        FOREIGN KEY (learner_portal_account_id, organisation_id, school_id)
        REFERENCES learner_portal_accounts(id, organisation_id, school_id),
    CONSTRAINT learner_portal_account_history_consent_fk
        FOREIGN KEY (consent_request_id, organisation_id, school_id)
        REFERENCES student_portal_consent_requests(id, organisation_id, school_id),
    CONSTRAINT learner_portal_account_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_portal_account_history_event_valid CHECK (
        event_type IN ('ACTIVATED', 'REACTIVATED', 'SUSPENDED', 'RESUMED', 'WITHDRAWN', 'ENDED')
    ),
    CONSTRAINT learner_portal_account_history_status_valid CHECK (
        new_status IN ('ACTIVE', 'SUSPENDED', 'ENDED')
        AND (previous_status IS NULL OR previous_status IN ('ACTIVE', 'SUSPENDED', 'ENDED'))
    ),
    CONSTRAINT learner_portal_account_history_reason_valid CHECK (
        length(btrim(reason)) BETWEEN 3 AND 500
    )
);

CREATE INDEX learner_portal_account_history_account_idx
    ON learner_portal_account_history (learner_portal_account_id, occurred_at DESC, id);

ALTER TABLE communication_announcements
    DROP CONSTRAINT communication_announcements_audience_valid,
    ADD CONSTRAINT communication_announcements_audience_valid CHECK (
        (audience_kind IN ('PUBLIC','ALL_STAFF','ALL_FAMILIES','ALL_STUDENTS')
            AND audience_reference_id IS NULL)
        OR (audience_kind IN (
                'SECTION_STAFF','SECTION_FAMILIES','SECTION_STUDENTS',
                'LEARNER_FAMILIES','LEARNER_STUDENT')
            AND audience_reference_id IS NOT NULL)
    );

ALTER TABLE communication_recipients
    DROP CONSTRAINT communication_recipients_kind_valid,
    ADD CONSTRAINT communication_recipients_kind_valid CHECK (
        (recipient_kind = 'STAFF' AND membership_id IS NOT NULL
            AND guardian_id IS NULL AND learner_id IS NULL)
        OR (recipient_kind = 'GUARDIAN' AND membership_id IS NULL
            AND guardian_id IS NOT NULL AND learner_id IS NOT NULL)
        OR (recipient_kind = 'STUDENT' AND membership_id IS NOT NULL
            AND guardian_id IS NULL AND learner_id IS NOT NULL)
    );

CREATE UNIQUE INDEX communication_recipients_student_unique
    ON communication_recipients (communication_announcement_id, membership_id, learner_id)
    WHERE recipient_kind = 'STUDENT';

CREATE OR REPLACE FUNCTION guard_student_portal_consent_request()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    guardian_user UUID;
    decision_membership_user UUID;
    withdrawal_membership_user UUID;
    approved_login VARCHAR(254);
    approved_status VARCHAR(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Student portal request evidence cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'Student portal requests must begin pending';
        END IF;
        IF NEW.supersedes_request_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM student_portal_consent_requests previous
            WHERE previous.id = NEW.supersedes_request_id
              AND previous.organisation_id = NEW.organisation_id
              AND previous.school_id = NEW.school_id
              AND previous.learner_id = NEW.learner_id
              AND previous.status <> 'PENDING'
        ) THEN
            RAISE EXCEPTION 'A student portal request may only supersede a terminal request for the same learner';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id
       OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id
       OR OLD.learner_id <> NEW.learner_id
       OR OLD.guardian_id <> NEW.guardian_id
       OR OLD.supersedes_request_id IS DISTINCT FROM NEW.supersedes_request_id
       OR OLD.proposed_login_identifier_normalized <> NEW.proposed_login_identifier_normalized
       OR OLD.privacy_notice_version <> NEW.privacy_notice_version
       OR OLD.purpose_summary <> NEW.purpose_summary
       OR OLD.adult_verification_method <> NEW.adult_verification_method
       OR OLD.adult_verification_reference <> NEW.adult_verification_reference
       OR OLD.adult_verified_at <> NEW.adult_verified_at
       OR OLD.adult_verified_by_membership_id <> NEW.adult_verified_by_membership_id
       OR OLD.preparation_reason <> NEW.preparation_reason
       OR OLD.expires_at <> NEW.expires_at
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'Student portal request evidence is immutable';
    END IF;
    IF OLD.status <> 'PENDING'
       AND NOT (OLD.status = 'APPROVED' AND NEW.status IN ('WITHDRAWN', 'REVOKED')) THEN
        RAISE EXCEPTION 'Student portal request is terminal';
    END IF;
    IF OLD.status = 'PENDING'
       AND NEW.status NOT IN ('APPROVED', 'DECLINED', 'REVOKED', 'EXPIRED') THEN
        RAISE EXCEPTION 'Invalid student portal request transition';
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status NOT IN ('WITHDRAWN', 'REVOKED') THEN
        RAISE EXCEPTION 'Approved student portal consent can only be withdrawn or administratively revoked';
    END IF;
    IF OLD.status = 'APPROVED' AND (
        OLD.guardian_decided_by_user_id IS DISTINCT FROM NEW.guardian_decided_by_user_id
        OR OLD.guardian_decided_by_membership_id IS DISTINCT FROM NEW.guardian_decided_by_membership_id
        OR OLD.guardian_decided_at IS DISTINCT FROM NEW.guardian_decided_at
        OR OLD.guardian_decision_reason IS DISTINCT FROM NEW.guardian_decision_reason
        OR OLD.approved_user_account_id IS DISTINCT FROM NEW.approved_user_account_id
    ) THEN
        RAISE EXCEPTION 'Approved guardian decision evidence is immutable';
    END IF;
    IF NEW.guardian_decided_by_user_id IS NOT NULL THEN
        SELECT guardian.user_account_id INTO guardian_user
        FROM guardians guardian
        WHERE guardian.id = NEW.guardian_id
          AND guardian.organisation_id = NEW.organisation_id
          AND guardian.school_id = NEW.school_id;
        SELECT membership.user_id INTO decision_membership_user
        FROM memberships membership
        WHERE membership.id = NEW.guardian_decided_by_membership_id
          AND membership.organisation_id = NEW.organisation_id
          AND membership.school_id = NEW.school_id;
        IF guardian_user IS DISTINCT FROM NEW.guardian_decided_by_user_id
           OR decision_membership_user IS DISTINCT FROM NEW.guardian_decided_by_user_id THEN
            RAISE EXCEPTION 'Guardian decision must use the linked guardian identity and membership';
        END IF;
    END IF;
    IF NEW.withdrawn_by_user_id IS NOT NULL THEN
        SELECT membership.user_id INTO withdrawal_membership_user
        FROM memberships membership
        WHERE membership.id = NEW.withdrawn_by_membership_id
          AND membership.organisation_id = NEW.organisation_id
          AND membership.school_id = NEW.school_id;
        IF guardian_user IS NULL THEN
            SELECT guardian.user_account_id INTO guardian_user
            FROM guardians guardian
            WHERE guardian.id = NEW.guardian_id
              AND guardian.organisation_id = NEW.organisation_id
              AND guardian.school_id = NEW.school_id;
        END IF;
        IF guardian_user IS DISTINCT FROM NEW.withdrawn_by_user_id
           OR withdrawal_membership_user IS DISTINCT FROM NEW.withdrawn_by_user_id THEN
            RAISE EXCEPTION 'Consent withdrawal must use the linked guardian identity and membership';
        END IF;
    END IF;
    IF NEW.approved_user_account_id IS NOT NULL THEN
        SELECT account.login_identifier_normalized, account.status
          INTO approved_login, approved_status
        FROM user_accounts account
        WHERE account.id = NEW.approved_user_account_id;
        IF approved_login IS DISTINCT FROM NEW.proposed_login_identifier_normalized
           OR approved_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'Approved learner identity must be active and match the proposed sign-in ID';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER student_portal_consent_requests_guard
    BEFORE INSERT OR UPDATE OR DELETE ON student_portal_consent_requests
    FOR EACH ROW EXECUTE FUNCTION guard_student_portal_consent_request();

CREATE OR REPLACE FUNCTION guard_learner_portal_account()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    consent_status VARCHAR(20);
    consent_learner UUID;
    consent_user UUID;
    consent_login VARCHAR(80);
    account_login VARCHAR(254);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Student portal accounts cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Student portal accounts must begin active';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        OLD.id <> NEW.id
        OR OLD.organisation_id <> NEW.organisation_id
        OR OLD.school_id <> NEW.school_id
        OR OLD.learner_id <> NEW.learner_id
        OR OLD.user_account_id <> NEW.user_account_id
        OR OLD.created_at <> NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Student portal account identity is immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'ENDED' AND NEW.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Ended student portal access may only reactivate through new consent';
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.current_consent_request_id IS DISTINCT FROM NEW.current_consent_request_id
       AND NOT (OLD.status = 'ENDED' AND NEW.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Current guardian consent can only change during reactivation';
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.activated_at IS DISTINCT FROM NEW.activated_at
       AND NOT (OLD.status = 'ENDED' AND NEW.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Activation time can only change during reactivation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND NEW.status NOT IN ('SUSPENDED', 'ENDED') THEN
        RAISE EXCEPTION 'Invalid active student portal transition';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'SUSPENDED' AND NEW.status NOT IN ('ACTIVE', 'ENDED') THEN
        RAISE EXCEPTION 'Invalid suspended student portal transition';
    END IF;
    SELECT request.status, request.learner_id, request.approved_user_account_id,
           request.proposed_login_identifier_normalized
    INTO consent_status, consent_learner, consent_user, consent_login
    FROM student_portal_consent_requests request
    WHERE request.id = NEW.current_consent_request_id
      AND request.organisation_id = NEW.organisation_id
      AND request.school_id = NEW.school_id;
    SELECT account.login_identifier_normalized INTO account_login
    FROM user_accounts account
    WHERE account.id = NEW.user_account_id;
    IF consent_status IS DISTINCT FROM 'APPROVED'
       OR consent_learner IS DISTINCT FROM NEW.learner_id
       OR consent_user IS DISTINCT FROM NEW.user_account_id
       OR account_login IS DISTINCT FROM consent_login THEN
        RAISE EXCEPTION 'Student portal account requires matching approved consent';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER learner_portal_accounts_guard
    BEFORE INSERT OR UPDATE OR DELETE ON learner_portal_accounts
    FOR EACH ROW EXECUTE FUNCTION guard_learner_portal_account();

CREATE OR REPLACE FUNCTION validate_learner_portal_history_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    stored_status VARCHAR(20);
    membership_user UUID;
    consent_learner UUID;
    account_learner UUID;
BEGIN
    SELECT account.status, account.learner_id
      INTO stored_status, account_learner
    FROM learner_portal_accounts account
    WHERE account.id = NEW.learner_portal_account_id
      AND account.organisation_id = NEW.organisation_id
      AND account.school_id = NEW.school_id;
    SELECT membership.user_id INTO membership_user
    FROM memberships membership
    WHERE membership.id = NEW.actor_membership_id
      AND membership.organisation_id = NEW.organisation_id
      AND membership.school_id = NEW.school_id;
    SELECT request.learner_id INTO consent_learner
    FROM student_portal_consent_requests request
    WHERE request.id = NEW.consent_request_id
      AND request.organisation_id = NEW.organisation_id
      AND request.school_id = NEW.school_id;

    IF stored_status IS DISTINCT FROM NEW.new_status
       OR membership_user IS DISTINCT FROM NEW.actor_user_id
       OR consent_learner IS DISTINCT FROM account_learner THEN
        RAISE EXCEPTION 'Student portal history must match the account, consent, and actor';
    END IF;
    IF NOT (
        (NEW.event_type = 'ACTIVATED' AND NEW.previous_status IS NULL AND NEW.new_status = 'ACTIVE')
        OR (NEW.event_type = 'REACTIVATED' AND NEW.previous_status = 'ENDED' AND NEW.new_status = 'ACTIVE')
        OR (NEW.event_type = 'SUSPENDED' AND NEW.previous_status = 'ACTIVE' AND NEW.new_status = 'SUSPENDED')
        OR (NEW.event_type = 'RESUMED' AND NEW.previous_status = 'SUSPENDED' AND NEW.new_status = 'ACTIVE')
        OR (NEW.event_type IN ('WITHDRAWN','ENDED')
            AND NEW.previous_status IN ('ACTIVE','SUSPENDED') AND NEW.new_status = 'ENDED')
    ) THEN
        RAISE EXCEPTION 'Student portal history transition does not match its event type';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER learner_portal_account_history_validate
    BEFORE INSERT ON learner_portal_account_history
    FOR EACH ROW EXECUTE FUNCTION validate_learner_portal_history_insert();

CREATE OR REPLACE FUNCTION reject_learner_portal_history_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Student portal account history is append-only';
END;
$$;

CREATE TRIGGER learner_portal_account_history_immutable
    BEFORE UPDATE OR DELETE ON learner_portal_account_history
    FOR EACH ROW EXECUTE FUNCTION reject_learner_portal_history_change();

ALTER TABLE student_portal_consent_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_portal_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_portal_account_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY student_portal_consent_requests_tenant_select ON student_portal_consent_requests
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY student_portal_consent_requests_tenant_insert ON student_portal_consent_requests
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY student_portal_consent_requests_tenant_update ON student_portal_consent_requests
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY learner_portal_accounts_tenant_select ON learner_portal_accounts
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY learner_portal_accounts_tenant_insert ON learner_portal_accounts
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_portal_accounts_tenant_update ON learner_portal_accounts
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY learner_portal_account_history_tenant_select ON learner_portal_account_history
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY learner_portal_account_history_tenant_insert ON learner_portal_account_history
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON COLUMN user_accounts.login_identifier_normalized IS
    'Normalized sign-in identifier. Existing accounts use email; learners may use a school-issued username without a fabricated email address.';
COMMENT ON TABLE student_portal_consent_requests IS
    'Time-limited, learner-specific guardian decisions. Only opaque adult-verification references are stored; raw identity documents and numbers do not belong here.';
COMMENT ON TABLE learner_portal_accounts IS
    'One learner-owned identity link, activated only by a matching approved guardian decision and never used as authority for another learner.';
COMMENT ON INDEX communication_recipients_student_unique IS
    'Frozen in-app student delivery is unique per announcement and own learner account; external child contact channels remain suppressed by service policy.';
