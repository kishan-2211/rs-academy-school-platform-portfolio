INSERT INTO capabilities (code, description, risk_level) VALUES
    ('communication.read', 'Read authorized school announcements and delivery evidence', 'SENSITIVE'),
    ('communication.approve', 'Approve or reject frozen-audience school communications', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (
    id, organisation_id, code, display_name, description, system_role,
    created_at, updated_at, row_version
) VALUES
    ('00000000-0000-4000-8000-000000000007', NULL, 'principal_director',
     'Principal or director',
     'School-wide operational oversight and controlled approvals without platform identity administration.',
     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('00000000-0000-4000-8000-000000000008', NULL, 'staff_operations',
     'Staff or operations',
     'Non-teaching operational access assigned through explicit capabilities and school scope.',
     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (organisation_id, code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'communication.read'),
    ('00000000-0000-4000-8000-000000000001', 'communication.approve'),
    ('00000000-0000-4000-8000-000000000002', 'communication.read'),
    ('00000000-0000-4000-8000-000000000003', 'communication.read'),
    ('00000000-0000-4000-8000-000000000004', 'communication.read'),
    ('00000000-0000-4000-8000-000000000007', 'school.configure'),
    ('00000000-0000-4000-8000-000000000007', 'student.read'),
    ('00000000-0000-4000-8000-000000000007', 'guardian.read'),
    ('00000000-0000-4000-8000-000000000007', 'academics.read'),
    ('00000000-0000-4000-8000-000000000007', 'academics.configure'),
    ('00000000-0000-4000-8000-000000000007', 'attendance.read'),
    ('00000000-0000-4000-8000-000000000007', 'attendance.correct'),
    ('00000000-0000-4000-8000-000000000007', 'assessment.read'),
    ('00000000-0000-4000-8000-000000000007', 'assessment.publish'),
    ('00000000-0000-4000-8000-000000000007', 'fees.read'),
    ('00000000-0000-4000-8000-000000000007', 'fees.approve'),
    ('00000000-0000-4000-8000-000000000007', 'communication.read'),
    ('00000000-0000-4000-8000-000000000007', 'communication.send'),
    ('00000000-0000-4000-8000-000000000007', 'communication.approve'),
    ('00000000-0000-4000-8000-000000000007', 'reports.read'),
    ('00000000-0000-4000-8000-000000000007', 'reports.export'),
    ('00000000-0000-4000-8000-000000000007', 'files.read'),
    ('00000000-0000-4000-8000-000000000008', 'communication.read')
ON CONFLICT DO NOTHING;

CREATE TABLE communication_templates (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    template_code VARCHAR(40) NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_template_id UUID,
    display_name VARCHAR(140) NOT NULL,
    category VARCHAR(30) NOT NULL,
    language_mode VARCHAR(20) NOT NULL,
    title_en VARCHAR(180),
    title_hi VARCHAR(180),
    body_en VARCHAR(8000),
    body_hi VARCHAR(8000),
    default_requires_acknowledgement BOOLEAN NOT NULL DEFAULT FALSE,
    default_email BOOLEAN NOT NULL DEFAULT FALSE,
    default_sms BOOLEAN NOT NULL DEFAULT FALSE,
    default_whatsapp BOOLEAN NOT NULL DEFAULT FALSE,
    default_push BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    activated_by_membership_id UUID,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_templates_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT communication_templates_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT communication_templates_supersedes_fk
        FOREIGN KEY (supersedes_template_id, organisation_id, school_id)
        REFERENCES communication_templates(id, organisation_id, school_id),
    CONSTRAINT communication_templates_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_templates_activator_fk
        FOREIGN KEY (activated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_templates_natural_key_unique
        UNIQUE (school_id, template_code, revision_number),
    CONSTRAINT communication_templates_revision_valid CHECK (revision_number BETWEEN 1 AND 1000),
    CONSTRAINT communication_templates_code_valid CHECK (template_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT communication_templates_category_valid CHECK (
        category IN ('GENERAL','ACADEMIC','ATTENDANCE','FEES','TRANSPORT','EVENT','EMERGENCY','OTHER')
    ),
    CONSTRAINT communication_templates_language_valid CHECK (
        (language_mode = 'ENGLISH' AND title_en IS NOT NULL AND body_en IS NOT NULL
            AND title_hi IS NULL AND body_hi IS NULL)
        OR (language_mode = 'HINDI' AND title_hi IS NOT NULL AND body_hi IS NOT NULL
            AND title_en IS NULL AND body_en IS NULL)
        OR (language_mode = 'BILINGUAL' AND title_en IS NOT NULL AND body_en IS NOT NULL
            AND title_hi IS NOT NULL AND body_hi IS NOT NULL)
    ),
    CONSTRAINT communication_templates_status_valid CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT communication_templates_activation_valid CHECK (
        (status = 'DRAFT' AND activated_by_membership_id IS NULL AND activated_at IS NULL)
        OR (status IN ('ACTIVE','RETIRED') AND activated_by_membership_id IS NOT NULL AND activated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX communication_templates_one_active_code_idx
    ON communication_templates (school_id, template_code) WHERE status = 'ACTIVE';

CREATE TABLE communication_provider_configs (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    environment VARCHAR(10) NOT NULL,
    provider_code VARCHAR(60) NOT NULL,
    sender_identity VARCHAR(120),
    principal_entity_id VARCHAR(120),
    credential_secret_reference VARCHAR(255),
    webhook_secret_reference VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    approved_by_membership_id UUID,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_provider_configs_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT communication_provider_configs_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT communication_provider_configs_approver_fk
        FOREIGN KEY (approved_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_provider_configs_unique UNIQUE (school_id, channel, environment),
    CONSTRAINT communication_provider_configs_channel_valid CHECK (channel IN ('EMAIL','SMS','WHATSAPP','PUSH')),
    CONSTRAINT communication_provider_configs_environment_valid CHECK (environment IN ('TEST','LIVE')),
    CONSTRAINT communication_provider_configs_status_valid CHECK (status IN ('INACTIVE','ACTIVE','SUSPENDED')),
    CONSTRAINT communication_provider_configs_secret_reference_valid CHECK (
        credential_secret_reference IS NULL
        OR credential_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,254}$'
    ),
    CONSTRAINT communication_provider_configs_webhook_reference_valid CHECK (
        webhook_secret_reference IS NULL
        OR webhook_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,254}$'
    ),
    CONSTRAINT communication_provider_configs_approval_valid CHECK (
        (status = 'INACTIVE' AND approved_by_membership_id IS NULL AND approved_at IS NULL)
        OR (status IN ('ACTIVE','SUSPENDED') AND approved_by_membership_id IS NOT NULL
            AND approved_at IS NOT NULL AND credential_secret_reference IS NOT NULL)
    )
);

CREATE TABLE communication_template_channel_registrations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_template_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    provider_config_id UUID,
    external_template_id VARCHAR(160),
    registration_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    rejection_reason VARCHAR(500),
    registered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_template_channels_template_fk
        FOREIGN KEY (communication_template_id, organisation_id, school_id)
        REFERENCES communication_templates(id, organisation_id, school_id),
    CONSTRAINT communication_template_channels_provider_fk
        FOREIGN KEY (provider_config_id, organisation_id, school_id)
        REFERENCES communication_provider_configs(id, organisation_id, school_id),
    CONSTRAINT communication_template_channels_unique UNIQUE (communication_template_id, channel),
    CONSTRAINT communication_template_channels_channel_valid CHECK (channel IN ('EMAIL','SMS','WHATSAPP','PUSH')),
    CONSTRAINT communication_template_channels_status_valid CHECK (
        registration_status IN ('DRAFT','PENDING','APPROVED','REJECTED')
    ),
    CONSTRAINT communication_template_channels_state_valid CHECK (
        (registration_status IN ('DRAFT','PENDING') AND registered_at IS NULL AND rejection_reason IS NULL)
        OR (registration_status = 'APPROVED' AND registered_at IS NOT NULL
            AND external_template_id IS NOT NULL AND rejection_reason IS NULL)
        OR (registration_status = 'REJECTED' AND registered_at IS NOT NULL
            AND rejection_reason IS NOT NULL)
    )
);

CREATE TABLE communication_contact_preferences (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    user_account_id UUID NOT NULL REFERENCES user_accounts(id),
    preferred_language VARCHAR(10) NOT NULL DEFAULT 'EN',
    time_zone VARCHAR(60) NOT NULL DEFAULT 'Asia/Kolkata',
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    allow_email BOOLEAN NOT NULL DEFAULT TRUE,
    allow_sms BOOLEAN NOT NULL DEFAULT TRUE,
    allow_whatsapp BOOLEAN NOT NULL DEFAULT FALSE,
    allow_push BOOLEAN NOT NULL DEFAULT FALSE,
    safe_contact_status VARCHAR(20) NOT NULL DEFAULT 'ALLOWED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_contact_preferences_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT communication_contact_preferences_unique UNIQUE (school_id, user_account_id),
    CONSTRAINT communication_contact_preferences_language_valid CHECK (preferred_language IN ('EN','HI')),
    CONSTRAINT communication_contact_preferences_quiet_valid CHECK (
        (quiet_hours_start IS NULL AND quiet_hours_end IS NULL)
        OR (quiet_hours_start IS NOT NULL AND quiet_hours_end IS NOT NULL
            AND quiet_hours_start <> quiet_hours_end)
    ),
    CONSTRAINT communication_contact_preferences_safe_valid CHECK (
        safe_contact_status IN ('ALLOWED','TRANSACTIONAL_ONLY','BLOCKED')
    )
);

CREATE TABLE communication_consent_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    user_account_id UUID NOT NULL REFERENCES user_accounts(id),
    channel VARCHAR(20) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    action VARCHAR(20) NOT NULL,
    source VARCHAR(40) NOT NULL,
    notice_version VARCHAR(80) NOT NULL,
    evidence_reference VARCHAR(240),
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id UUID REFERENCES user_accounts(id),
    CONSTRAINT communication_consent_events_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT communication_consent_events_channel_valid CHECK (channel IN ('EMAIL','SMS','WHATSAPP','PUSH')),
    CONSTRAINT communication_consent_events_action_valid CHECK (action IN ('GRANTED','REVOKED')),
    CONSTRAINT communication_consent_events_source_valid CHECK (
        source IN ('SELF_SERVICE','SCHOOL_RECORDED','DLT','PROVIDER_CALLBACK','MIGRATION')
    )
);

CREATE TABLE communication_announcements (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_template_id UUID,
    category VARCHAR(30) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    audience_kind VARCHAR(30) NOT NULL,
    audience_reference_id UUID,
    language_mode VARCHAR(20) NOT NULL,
    title_en VARCHAR(180),
    title_hi VARCHAR(180),
    body_en VARCHAR(8000),
    body_hi VARCHAR(8000),
    requires_acknowledgement BOOLEAN NOT NULL DEFAULT FALSE,
    request_email BOOLEAN NOT NULL DEFAULT FALSE,
    request_sms BOOLEAN NOT NULL DEFAULT FALSE,
    request_whatsapp BOOLEAN NOT NULL DEFAULT FALSE,
    request_push BOOLEAN NOT NULL DEFAULT FALSE,
    scheduled_for TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    cancelled_by_membership_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_announcements_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT communication_announcements_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT communication_announcements_template_fk
        FOREIGN KEY (communication_template_id, organisation_id, school_id)
        REFERENCES communication_templates(id, organisation_id, school_id),
    CONSTRAINT communication_announcements_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_announcements_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_announcements_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_announcements_canceller_fk
        FOREIGN KEY (cancelled_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_announcements_category_valid CHECK (
        category IN ('GENERAL','ACADEMIC','ATTENDANCE','FEES','TRANSPORT','EVENT','EMERGENCY','OTHER')
    ),
    CONSTRAINT communication_announcements_priority_valid CHECK (
        priority IN ('NORMAL','IMPORTANT','URGENT','EMERGENCY')
    ),
    CONSTRAINT communication_announcements_audience_valid CHECK (
        (audience_kind IN ('PUBLIC','ALL_STAFF','ALL_FAMILIES') AND audience_reference_id IS NULL)
        OR (audience_kind IN ('SECTION_STAFF','SECTION_FAMILIES','LEARNER_FAMILIES')
            AND audience_reference_id IS NOT NULL)
    ),
    CONSTRAINT communication_announcements_public_valid CHECK (
        audience_kind <> 'PUBLIC'
        OR (requires_acknowledgement = FALSE AND request_email = FALSE AND request_sms = FALSE
            AND request_whatsapp = FALSE AND request_push = FALSE)
    ),
    CONSTRAINT communication_announcements_language_valid CHECK (
        (language_mode = 'ENGLISH' AND title_en IS NOT NULL AND body_en IS NOT NULL
            AND title_hi IS NULL AND body_hi IS NULL)
        OR (language_mode = 'HINDI' AND title_hi IS NOT NULL AND body_hi IS NOT NULL
            AND title_en IS NULL AND body_en IS NULL)
        OR (language_mode = 'BILINGUAL' AND title_en IS NOT NULL AND body_en IS NOT NULL
            AND title_hi IS NOT NULL AND body_hi IS NOT NULL)
    ),
    CONSTRAINT communication_announcements_schedule_valid CHECK (
        expires_at IS NULL OR expires_at > scheduled_for
    ),
    CONSTRAINT communication_announcements_status_valid CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','CANCELLED')
    ),
    CONSTRAINT communication_announcements_state_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'PENDING_APPROVAL' AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL AND decided_by_membership_id <> created_by_membership_id
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_by_membership_id IS NOT NULL
            AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)
    )
);

CREATE INDEX communication_announcements_school_status_idx
    ON communication_announcements (organisation_id, school_id, status, scheduled_for DESC, id);
CREATE INDEX communication_announcements_public_idx
    ON communication_announcements (school_id, scheduled_for DESC, id)
    WHERE status = 'APPROVED' AND audience_kind = 'PUBLIC';

ALTER TABLE private_files ADD COLUMN communication_announcement_id UUID;
ALTER TABLE private_files ADD CONSTRAINT private_files_communication_fk
    FOREIGN KEY (communication_announcement_id, organisation_id, school_id)
    REFERENCES communication_announcements(id, organisation_id, school_id);
ALTER TABLE private_files DROP CONSTRAINT private_files_context_valid;
ALTER TABLE private_files ADD CONSTRAINT private_files_context_valid CHECK (
    (purpose = 'ASSIGNMENT_RESOURCE' AND assignment_id IS NOT NULL
        AND learner_assignment_submission_id IS NULL AND communication_announcement_id IS NULL)
    OR (purpose = 'SUBMISSION_EVIDENCE' AND assignment_id IS NULL
        AND learner_assignment_submission_id IS NOT NULL AND communication_announcement_id IS NULL)
    OR (purpose = 'COMMUNICATION_ATTACHMENT' AND assignment_id IS NULL
        AND learner_assignment_submission_id IS NULL AND communication_announcement_id IS NOT NULL)
);

CREATE TABLE communication_attachments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_announcement_id UUID NOT NULL,
    private_file_id UUID NOT NULL,
    display_label VARCHAR(180) NOT NULL,
    display_order SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT communication_attachments_announcement_fk
        FOREIGN KEY (communication_announcement_id, organisation_id, school_id)
        REFERENCES communication_announcements(id, organisation_id, school_id),
    CONSTRAINT communication_attachments_file_fk
        FOREIGN KEY (private_file_id, organisation_id, school_id)
        REFERENCES private_files(id, organisation_id, school_id),
    CONSTRAINT communication_attachments_file_unique UNIQUE (private_file_id),
    CONSTRAINT communication_attachments_order_unique
        UNIQUE (communication_announcement_id, display_order),
    CONSTRAINT communication_attachments_order_valid CHECK (display_order BETWEEN 0 AND 20)
);

CREATE OR REPLACE FUNCTION guard_communication_private_file_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    announcement_status VARCHAR(30);
BEGIN
    IF NEW.purpose <> 'COMMUNICATION_ATTACHMENT' THEN RETURN NEW; END IF;
    SELECT status INTO announcement_status
    FROM communication_announcements
    WHERE id = NEW.communication_announcement_id
      AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id;
    IF announcement_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'communication files can be added only to a draft announcement';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER private_files_communication_insert_guard
    BEFORE INSERT ON private_files
    FOR EACH ROW EXECUTE FUNCTION guard_communication_private_file_insert();

CREATE OR REPLACE FUNCTION guard_communication_attachment()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    attachment_row communication_attachments%ROWTYPE;
    announcement_status VARCHAR(30);
    file_announcement_id UUID;
    file_purpose VARCHAR(40);
BEGIN
    IF TG_OP = 'DELETE' THEN
        attachment_row := OLD;
    ELSE
        attachment_row := NEW;
    END IF;
    SELECT purpose, communication_announcement_id INTO file_purpose, file_announcement_id
    FROM private_files
    WHERE id = attachment_row.private_file_id
      AND organisation_id = attachment_row.organisation_id AND school_id = attachment_row.school_id;
    IF file_purpose IS DISTINCT FROM 'COMMUNICATION_ATTACHMENT'
       OR file_announcement_id IS DISTINCT FROM attachment_row.communication_announcement_id THEN
        RAISE EXCEPTION 'communication attachment file context does not match the announcement';
    END IF;
    SELECT status INTO announcement_status
    FROM communication_announcements
    WHERE id = attachment_row.communication_announcement_id
      AND organisation_id = attachment_row.organisation_id AND school_id = attachment_row.school_id;
    IF announcement_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'communication attachments are frozen after submission';
    END IF;
    IF TG_OP = 'UPDATE' AND (
       OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.communication_announcement_id <> NEW.communication_announcement_id
       OR OLD.private_file_id <> NEW.private_file_id OR OLD.created_at <> NEW.created_at
    ) THEN RAISE EXCEPTION 'communication attachment identity is immutable'; END IF;
    RETURN attachment_row;
END;
$$;

CREATE TRIGGER communication_attachments_guard
    BEFORE INSERT OR UPDATE OR DELETE ON communication_attachments
    FOR EACH ROW EXECUTE FUNCTION guard_communication_attachment();

CREATE TABLE communication_recipients (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_announcement_id UUID NOT NULL,
    recipient_kind VARCHAR(20) NOT NULL,
    membership_id UUID,
    guardian_id UUID,
    learner_id UUID,
    user_account_id UUID NOT NULL REFERENCES user_accounts(id),
    preferred_language VARCHAR(10) NOT NULL,
    contact_eligible BOOLEAN NOT NULL,
    suppression_reason VARCHAR(120),
    available_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_recipients_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT communication_recipients_announcement_fk
        FOREIGN KEY (communication_announcement_id, organisation_id, school_id)
        REFERENCES communication_announcements(id, organisation_id, school_id),
    CONSTRAINT communication_recipients_membership_fk
        FOREIGN KEY (membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_recipients_guardian_fk
        FOREIGN KEY (guardian_id, organisation_id, school_id)
        REFERENCES guardians(id, organisation_id, school_id),
    CONSTRAINT communication_recipients_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT communication_recipients_kind_valid CHECK (
        (recipient_kind = 'STAFF' AND membership_id IS NOT NULL
            AND guardian_id IS NULL AND learner_id IS NULL)
        OR (recipient_kind = 'GUARDIAN' AND membership_id IS NULL
            AND guardian_id IS NOT NULL AND learner_id IS NOT NULL)
    ),
    CONSTRAINT communication_recipients_language_valid CHECK (preferred_language IN ('EN','HI')),
    CONSTRAINT communication_recipients_suppression_valid CHECK (
        (contact_eligible = TRUE AND suppression_reason IS NULL)
        OR (contact_eligible = FALSE AND suppression_reason IS NOT NULL)
    ),
    CONSTRAINT communication_recipients_ack_valid CHECK (
        acknowledged_at IS NULL OR read_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX communication_recipients_staff_unique
    ON communication_recipients (communication_announcement_id, membership_id)
    WHERE recipient_kind = 'STAFF';
CREATE UNIQUE INDEX communication_recipients_guardian_unique
    ON communication_recipients (communication_announcement_id, guardian_id, learner_id)
    WHERE recipient_kind = 'GUARDIAN';
CREATE INDEX communication_recipients_user_inbox_idx
    ON communication_recipients (organisation_id, user_account_id, available_at DESC, id);

CREATE TABLE communication_deliveries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_recipient_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider_config_id UUID,
    provider_message_reference VARCHAR(240),
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    queued_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT communication_deliveries_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT communication_deliveries_recipient_fk
        FOREIGN KEY (communication_recipient_id, organisation_id, school_id)
        REFERENCES communication_recipients(id, organisation_id, school_id),
    CONSTRAINT communication_deliveries_provider_fk
        FOREIGN KEY (provider_config_id, organisation_id, school_id)
        REFERENCES communication_provider_configs(id, organisation_id, school_id),
    CONSTRAINT communication_deliveries_unique UNIQUE (communication_recipient_id, channel),
    CONSTRAINT communication_deliveries_channel_valid CHECK (channel IN ('IN_APP','EMAIL','SMS','WHATSAPP','PUSH')),
    CONSTRAINT communication_deliveries_status_valid CHECK (
        status IN ('READY','AWAITING_PROVIDER','SUPPRESSED','QUEUED','SENT','DELIVERED','FAILED','CANCELLED')
    ),
    CONSTRAINT communication_deliveries_attempt_valid CHECK (attempt_count BETWEEN 0 AND 100),
    CONSTRAINT communication_deliveries_provider_valid CHECK (
        (channel = 'IN_APP' AND provider_config_id IS NULL)
        OR (channel <> 'IN_APP' AND (
            status IN ('AWAITING_PROVIDER','SUPPRESSED','CANCELLED') OR provider_config_id IS NOT NULL))
    )
);

CREATE INDEX communication_deliveries_work_idx
    ON communication_deliveries (status, updated_at, id)
    WHERE status IN ('READY','AWAITING_PROVIDER','QUEUED','FAILED');

CREATE TABLE communication_delivery_attempts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_delivery_id UUID NOT NULL,
    attempt_number SMALLINT NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    provider_status_code VARCHAR(80),
    safe_error_code VARCHAR(100),
    attempted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT communication_delivery_attempts_delivery_fk
        FOREIGN KEY (communication_delivery_id, organisation_id, school_id)
        REFERENCES communication_deliveries(id, organisation_id, school_id),
    CONSTRAINT communication_delivery_attempts_unique UNIQUE (communication_delivery_id, attempt_number),
    CONSTRAINT communication_delivery_attempts_outcome_valid CHECK (
        outcome IN ('ACCEPTED','RETRYABLE_FAILURE','PERMANENT_FAILURE','CALLBACK_DELIVERED','CALLBACK_FAILED')
    )
);

CREATE TABLE communication_recipient_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_recipient_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES user_accounts(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT communication_recipient_events_recipient_fk
        FOREIGN KEY (communication_recipient_id, organisation_id, school_id)
        REFERENCES communication_recipients(id, organisation_id, school_id),
    CONSTRAINT communication_recipient_events_type_valid CHECK (event_type IN ('READ','ACKNOWLEDGED'))
);

CREATE UNIQUE INDEX communication_recipient_events_once_idx
    ON communication_recipient_events (communication_recipient_id, event_type);

CREATE TABLE communication_announcement_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_announcement_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT communication_announcement_history_announcement_fk
        FOREIGN KEY (communication_announcement_id, organisation_id, school_id)
        REFERENCES communication_announcements(id, organisation_id, school_id),
    CONSTRAINT communication_announcement_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT communication_announcement_history_event_valid CHECK (
        event_type IN ('CREATED','UPDATED','SUBMITTED','APPROVED','REJECTED','CANCELLED','RECIPIENTS_FROZEN')
    )
);

CREATE OR REPLACE FUNCTION guard_communication_template()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'communication templates cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.template_code <> NEW.template_code OR OLD.revision_number <> NEW.revision_number
       OR OLD.supersedes_template_id IS DISTINCT FROM NEW.supersedes_template_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'communication template identity is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT','ACTIVE') THEN
        RAISE EXCEPTION 'invalid communication template transition';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE','RETIRED') THEN
        RAISE EXCEPTION 'invalid communication template transition';
    END IF;
    IF OLD.status = 'RETIRED' THEN RAISE EXCEPTION 'retired communication templates are immutable'; END IF;
    IF OLD.status <> 'DRAFT' AND (
       OLD.display_name <> NEW.display_name OR OLD.category <> NEW.category
       OR OLD.language_mode <> NEW.language_mode OR OLD.title_en IS DISTINCT FROM NEW.title_en
       OR OLD.title_hi IS DISTINCT FROM NEW.title_hi OR OLD.body_en IS DISTINCT FROM NEW.body_en
       OR OLD.body_hi IS DISTINCT FROM NEW.body_hi
       OR OLD.default_requires_acknowledgement <> NEW.default_requires_acknowledgement
       OR OLD.default_email <> NEW.default_email OR OLD.default_sms <> NEW.default_sms
       OR OLD.default_whatsapp <> NEW.default_whatsapp OR OLD.default_push <> NEW.default_push
    ) THEN RAISE EXCEPTION 'active communication template content is immutable'; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER communication_templates_guard
    BEFORE UPDATE OR DELETE ON communication_templates
    FOR EACH ROW EXECUTE FUNCTION guard_communication_template();

CREATE OR REPLACE FUNCTION guard_communication_announcement()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'communication announcements cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'communication announcement identity is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT','PENDING_APPROVAL','CANCELLED') THEN
        RAISE EXCEPTION 'invalid draft communication transition';
    END IF;
    IF OLD.status = 'PENDING_APPROVAL' AND NEW.status NOT IN ('PENDING_APPROVAL','APPROVED','REJECTED','CANCELLED') THEN
        RAISE EXCEPTION 'invalid pending communication transition';
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED','CANCELLED') THEN
        RAISE EXCEPTION 'invalid approved communication transition';
    END IF;
    IF OLD.status IN ('REJECTED','CANCELLED') THEN
        RAISE EXCEPTION 'terminal communication announcements are immutable';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
       OLD.communication_template_id IS DISTINCT FROM NEW.communication_template_id
       OR OLD.category <> NEW.category OR OLD.priority <> NEW.priority
       OR OLD.audience_kind <> NEW.audience_kind
       OR OLD.audience_reference_id IS DISTINCT FROM NEW.audience_reference_id
       OR OLD.language_mode <> NEW.language_mode OR OLD.title_en IS DISTINCT FROM NEW.title_en
       OR OLD.title_hi IS DISTINCT FROM NEW.title_hi OR OLD.body_en IS DISTINCT FROM NEW.body_en
       OR OLD.body_hi IS DISTINCT FROM NEW.body_hi
       OR OLD.requires_acknowledgement <> NEW.requires_acknowledgement
       OR OLD.request_email <> NEW.request_email OR OLD.request_sms <> NEW.request_sms
       OR OLD.request_whatsapp <> NEW.request_whatsapp OR OLD.request_push <> NEW.request_push
       OR OLD.scheduled_for <> NEW.scheduled_for OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
    ) THEN RAISE EXCEPTION 'submitted communication content and audience are immutable'; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER communication_announcements_guard
    BEFORE UPDATE OR DELETE ON communication_announcements
    FOR EACH ROW EXECUTE FUNCTION guard_communication_announcement();

CREATE OR REPLACE FUNCTION guard_communication_recipient()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'communication recipients cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.communication_announcement_id <> NEW.communication_announcement_id
       OR OLD.recipient_kind <> NEW.recipient_kind OR OLD.membership_id IS DISTINCT FROM NEW.membership_id
       OR OLD.guardian_id IS DISTINCT FROM NEW.guardian_id OR OLD.learner_id IS DISTINCT FROM NEW.learner_id
       OR OLD.user_account_id <> NEW.user_account_id OR OLD.preferred_language <> NEW.preferred_language
       OR OLD.contact_eligible <> NEW.contact_eligible
       OR OLD.suppression_reason IS DISTINCT FROM NEW.suppression_reason
       OR OLD.available_at <> NEW.available_at OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'frozen communication recipient identity is immutable';
    END IF;
    IF OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at THEN
        RAISE EXCEPTION 'communication read evidence is immutable';
    END IF;
    IF OLD.acknowledged_at IS NOT NULL AND NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at THEN
        RAISE EXCEPTION 'communication acknowledgement evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER communication_recipients_guard
    BEFORE UPDATE OR DELETE ON communication_recipients
    FOR EACH ROW EXECUTE FUNCTION guard_communication_recipient();

CREATE OR REPLACE FUNCTION guard_communication_delivery()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'communication deliveries cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.communication_recipient_id <> NEW.communication_recipient_id
       OR OLD.channel <> NEW.channel OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'communication delivery identity is immutable';
    END IF;
    IF OLD.provider_config_id IS NOT NULL
       AND NEW.provider_config_id IS DISTINCT FROM OLD.provider_config_id THEN
        RAISE EXCEPTION 'assigned communication provider is immutable';
    END IF;
    IF OLD.provider_config_id IS NULL AND NEW.provider_config_id IS NOT NULL
       AND NOT (OLD.status = 'AWAITING_PROVIDER' AND NEW.status = 'READY') THEN
        RAISE EXCEPTION 'provider can be assigned only while activating an awaiting delivery';
    END IF;
    IF OLD.provider_message_reference IS NOT NULL
       AND NEW.provider_message_reference IS DISTINCT FROM OLD.provider_message_reference THEN
        RAISE EXCEPTION 'provider message reference is immutable';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'communication attempt count cannot decrease';
    END IF;
    IF (OLD.queued_at IS NOT NULL AND NEW.queued_at IS DISTINCT FROM OLD.queued_at)
       OR (OLD.sent_at IS NOT NULL AND NEW.sent_at IS DISTINCT FROM OLD.sent_at)
       OR (OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at)
       OR (OLD.failed_at IS NOT NULL AND NEW.failed_at IS DISTINCT FROM OLD.failed_at) THEN
        RAISE EXCEPTION 'communication delivery timestamps are immutable once recorded';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
       (OLD.status = 'AWAITING_PROVIDER' AND NEW.status IN ('READY','SUPPRESSED','CANCELLED'))
       OR (OLD.status = 'READY' AND NEW.status IN ('QUEUED','CANCELLED'))
       OR (OLD.status = 'READY' AND NEW.status = 'DELIVERED' AND OLD.channel = 'IN_APP')
       OR (OLD.status = 'QUEUED' AND NEW.status IN ('SENT','FAILED','CANCELLED'))
       OR (OLD.status = 'SENT' AND NEW.status IN ('DELIVERED','FAILED','CANCELLED'))
       OR (OLD.status = 'FAILED' AND NEW.status IN ('READY','QUEUED','CANCELLED'))
    ) THEN RAISE EXCEPTION 'invalid communication delivery transition'; END IF;
    IF OLD.status IN ('SUPPRESSED','DELIVERED','CANCELLED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'terminal communication deliveries are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER communication_deliveries_guard
    BEFORE UPDATE OR DELETE ON communication_deliveries
    FOR EACH ROW EXECUTE FUNCTION guard_communication_delivery();

CREATE OR REPLACE FUNCTION prevent_communication_evidence_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'communication evidence is append-only';
END;
$$;

CREATE TRIGGER communication_consent_events_append_only
    BEFORE UPDATE OR DELETE ON communication_consent_events
    FOR EACH ROW EXECUTE FUNCTION prevent_communication_evidence_change();
CREATE TRIGGER communication_delivery_attempts_append_only
    BEFORE UPDATE OR DELETE ON communication_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION prevent_communication_evidence_change();
CREATE TRIGGER communication_recipient_events_append_only
    BEFORE UPDATE OR DELETE ON communication_recipient_events
    FOR EACH ROW EXECUTE FUNCTION prevent_communication_evidence_change();
CREATE TRIGGER communication_announcement_history_append_only
    BEFORE UPDATE OR DELETE ON communication_announcement_history
    FOR EACH ROW EXECUTE FUNCTION prevent_communication_evidence_change();

ALTER TABLE communication_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_provider_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_template_channel_registrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_contact_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_consent_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_announcements ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_recipients ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_recipient_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_announcement_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY communication_templates_tenant_select ON communication_templates
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_templates_tenant_insert ON communication_templates
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_templates_tenant_update ON communication_templates
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_provider_configs_tenant_select ON communication_provider_configs
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_provider_configs_tenant_insert ON communication_provider_configs
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_provider_configs_tenant_update ON communication_provider_configs
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_template_channels_tenant_select ON communication_template_channel_registrations
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_template_channels_tenant_insert ON communication_template_channel_registrations
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_template_channels_tenant_update ON communication_template_channel_registrations
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_preferences_tenant_select ON communication_contact_preferences
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_preferences_tenant_insert ON communication_contact_preferences
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_preferences_tenant_update ON communication_contact_preferences
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_consent_tenant_select ON communication_consent_events
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_consent_tenant_insert ON communication_consent_events
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_announcements_tenant_select ON communication_announcements
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_announcements_tenant_insert ON communication_announcements
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_announcements_tenant_update ON communication_announcements
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_announcements_public_select ON communication_announcements
    FOR SELECT USING (
        status = 'APPROVED' AND audience_kind = 'PUBLIC' AND scheduled_for <= CURRENT_TIMESTAMP
        AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
        AND EXISTS (SELECT 1 FROM schools school
            WHERE school.id = communication_announcements.school_id AND school.status = 'ACTIVE')
    );

CREATE POLICY communication_attachments_tenant_select ON communication_attachments
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_attachments_tenant_insert ON communication_attachments
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_recipients_tenant_select ON communication_recipients
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_recipients_tenant_insert ON communication_recipients
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_recipients_tenant_update ON communication_recipients
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_deliveries_tenant_select ON communication_deliveries
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_deliveries_tenant_insert ON communication_deliveries
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY communication_deliveries_tenant_update ON communication_deliveries
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_delivery_attempts_tenant_select ON communication_delivery_attempts
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_delivery_attempts_tenant_insert ON communication_delivery_attempts
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_recipient_events_tenant_select ON communication_recipient_events
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_recipient_events_tenant_insert ON communication_recipient_events
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY communication_history_tenant_select ON communication_announcement_history
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY communication_history_tenant_insert ON communication_announcement_history
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE communication_announcements IS
    'Plain-text bilingual drafts whose content and resolved audience freeze before maker-checker approval.';
COMMENT ON TABLE communication_recipients IS
    'Relationship- or membership-scoped recipient evidence. Provider destinations are resolved only by an authorized worker.';
COMMENT ON TABLE communication_provider_configs IS
    'Provider-neutral channel metadata containing secret-manager references only, never live credentials.';
COMMENT ON TABLE communication_delivery_attempts IS
    'Append-only provider attempt and callback evidence without message bodies or contact destinations.';
