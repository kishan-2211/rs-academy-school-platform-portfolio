INSERT INTO capabilities (code, description, risk_level) VALUES
    ('calendar.manage', 'Configure session calendar notification policy and propose holiday or closure revisions', 'RESTRICTED'),
    ('calendar.approve', 'Independently approve or reject frozen holiday and closure revisions', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'calendar.manage'),
    ('00000000-0000-4000-8000-000000000001', 'calendar.approve'),
    ('00000000-0000-4000-8000-000000000007', 'calendar.manage'),
    ('00000000-0000-4000-8000-000000000007', 'calendar.approve'),
    ('00000000-0000-4000-8000-000000000008', 'calendar.manage')
ON CONFLICT DO NOTHING;

CREATE TABLE school_calendar_policy_revisions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_policy_id UUID,
    school_start_time TIME NOT NULL,
    same_day_cutoff_minutes SMALLINT NOT NULL,
    reminder_time TIME NOT NULL,
    time_zone VARCHAR(60) NOT NULL,
    guardian_push_enabled BOOLEAN NOT NULL,
    student_in_app_enabled BOOLEAN NOT NULL,
    emergency_ack_required BOOLEAN NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    revised_by_membership_id UUID NOT NULL,
    revised_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_calendar_policy_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_calendar_policy_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT school_calendar_policy_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT school_calendar_policy_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_policy_supersedes_fk
        FOREIGN KEY (supersedes_policy_id, organisation_id, school_id)
        REFERENCES school_calendar_policy_revisions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_policy_reviser_fk
        FOREIGN KEY (revised_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_calendar_policy_revision_unique
        UNIQUE (campus_id, academic_session_id, revision_number),
    CONSTRAINT school_calendar_policy_revision_valid CHECK (
        (revision_number = 1 AND supersedes_policy_id IS NULL)
        OR (revision_number BETWEEN 2 AND 1000 AND supersedes_policy_id IS NOT NULL)
    ),
    CONSTRAINT school_calendar_policy_cutoff_valid CHECK (
        same_day_cutoff_minutes BETWEEN 0 AND 240
    ),
    CONSTRAINT school_calendar_policy_timezone_valid CHECK (
        length(btrim(time_zone)) BETWEEN 1 AND 60
    ),
    CONSTRAINT school_calendar_policy_reason_valid CHECK (
        length(btrim(change_reason)) BETWEEN 3 AND 500
    )
);

CREATE INDEX school_calendar_policy_scope_idx
    ON school_calendar_policy_revisions
    (organisation_id, school_id, campus_id, academic_session_id, revision_number DESC);

CREATE TABLE school_calendar_notice_series (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    calendar_date DATE NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT school_calendar_notice_series_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_series_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT school_calendar_notice_series_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_series_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_series_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_series_slot_unique
        UNIQUE (campus_id, academic_session_id, calendar_date)
);

CREATE TABLE school_calendar_notice_revisions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    notice_series_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_revision_id UUID,
    day_type VARCHAR(20) NOT NULL,
    notice_kind VARCHAR(30) NOT NULL,
    title_en VARCHAR(180) NOT NULL,
    title_hi VARCHAR(180) NOT NULL,
    body_en VARCHAR(4000) NOT NULL,
    body_hi VARCHAR(4000) NOT NULL,
    source_reference VARCHAR(500),
    change_reason VARCHAR(500) NOT NULL,
    revised_by_membership_id UUID NOT NULL,
    revised_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_calendar_notice_revision_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_revision_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT school_calendar_notice_revision_series_fk
        FOREIGN KEY (notice_series_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_series(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_revision_supersedes_fk
        FOREIGN KEY (supersedes_revision_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_revisions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_revision_reviser_fk
        FOREIGN KEY (revised_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_revision_unique UNIQUE (notice_series_id, revision_number),
    CONSTRAINT school_calendar_notice_revision_number_valid CHECK (
        (revision_number = 1 AND supersedes_revision_id IS NULL)
        OR (revision_number BETWEEN 2 AND 1000 AND supersedes_revision_id IS NOT NULL)
    ),
    CONSTRAINT school_calendar_notice_day_type_valid CHECK (
        day_type IN ('HOLIDAY', 'CLOSURE', 'INSTRUCTIONAL')
    ),
    CONSTRAINT school_calendar_notice_kind_valid CHECK (
        notice_kind IN ('PLANNED_HOLIDAY', 'EMERGENCY_CLOSURE', 'GOVERNMENT_ORDER', 'REOPENING', 'OTHER')
    ),
    CONSTRAINT school_calendar_notice_kind_day_valid CHECK (
        (notice_kind = 'PLANNED_HOLIDAY' AND day_type = 'HOLIDAY')
        OR (notice_kind IN ('EMERGENCY_CLOSURE', 'GOVERNMENT_ORDER') AND day_type = 'CLOSURE')
        OR (notice_kind = 'REOPENING' AND day_type = 'INSTRUCTIONAL')
        OR notice_kind = 'OTHER'
    ),
    CONSTRAINT school_calendar_notice_text_valid CHECK (
        length(btrim(title_en)) BETWEEN 1 AND 180
        AND length(btrim(title_hi)) BETWEEN 1 AND 180
        AND length(btrim(body_en)) BETWEEN 1 AND 4000
        AND length(btrim(body_hi)) BETWEEN 1 AND 4000
        AND (source_reference IS NULL OR length(btrim(source_reference)) BETWEEN 1 AND 500)
        AND length(btrim(change_reason)) BETWEEN 3 AND 500
    )
);

CREATE INDEX school_calendar_notice_revision_scope_idx
    ON school_calendar_notice_revisions
    (organisation_id, school_id, notice_series_id, revision_number DESC);

CREATE TABLE school_calendar_notice_reviews (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    notice_series_id UUID NOT NULL,
    notice_revision_id UUID NOT NULL,
    policy_revision_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submission_reason VARCHAR(500) NOT NULL,
    submitted_by_membership_id UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_calendar_notice_review_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_review_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT school_calendar_notice_review_series_fk
        FOREIGN KEY (notice_series_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_series(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_review_revision_fk
        FOREIGN KEY (notice_revision_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_revisions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_review_policy_fk
        FOREIGN KEY (policy_revision_id, organisation_id, school_id)
        REFERENCES school_calendar_policy_revisions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_review_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_review_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notice_review_revision_unique UNIQUE (notice_revision_id),
    CONSTRAINT school_calendar_notice_review_status_valid CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT school_calendar_notice_review_submission_valid CHECK (
        length(btrim(submission_reason)) BETWEEN 3 AND 500
    ),
    CONSTRAINT school_calendar_notice_review_lifecycle_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('APPROVED', 'REJECTED')
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 1000)
    )
);

CREATE UNIQUE INDEX school_calendar_notice_one_pending_review_idx
    ON school_calendar_notice_reviews (notice_series_id) WHERE status = 'PENDING';
CREATE INDEX school_calendar_notice_review_scope_idx
    ON school_calendar_notice_reviews (organisation_id, school_id, submitted_at DESC, id);

ALTER TABLE communication_announcements
    ADD COLUMN source_type VARCHAR(50),
    ADD COLUMN source_id UUID,
    ADD CONSTRAINT communication_announcements_source_valid CHECK (
        (source_type IS NULL AND source_id IS NULL)
        OR (source_type ~ '^[A-Z][A-Z0-9_]{2,49}$' AND source_id IS NOT NULL)
    );

CREATE UNIQUE INDEX communication_announcements_source_audience_unique
    ON communication_announcements (school_id, source_type, source_id, audience_kind)
    WHERE source_type IS NOT NULL;

CREATE OR REPLACE FUNCTION guard_communication_announcement()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'communication announcements cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at
       OR OLD.source_type IS DISTINCT FROM NEW.source_type OR OLD.source_id IS DISTINCT FROM NEW.source_id THEN
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

CREATE TABLE school_calendar_notification_jobs (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    notice_series_id UUID NOT NULL,
    notice_revision_id UUID NOT NULL,
    notice_review_id UUID NOT NULL,
    audience_kind VARCHAR(30) NOT NULL,
    request_push BOOLEAN NOT NULL,
    requires_acknowledgement BOOLEAN NOT NULL,
    scheduled_for TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    announcement_id UUID,
    recipient_count INTEGER,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(120),
    processed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_calendar_notification_job_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_calendar_notification_job_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT school_calendar_notification_job_series_fk
        FOREIGN KEY (notice_series_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_series(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notification_job_revision_fk
        FOREIGN KEY (notice_revision_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_revisions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notification_job_review_fk
        FOREIGN KEY (notice_review_id, organisation_id, school_id)
        REFERENCES school_calendar_notice_reviews(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notification_job_announcement_fk
        FOREIGN KEY (announcement_id, organisation_id, school_id)
        REFERENCES communication_announcements(id, organisation_id, school_id),
    CONSTRAINT school_calendar_notification_job_unique UNIQUE (notice_revision_id, audience_kind),
    CONSTRAINT school_calendar_notification_job_audience_valid CHECK (
        audience_kind IN ('ALL_FAMILIES', 'ALL_STUDENTS')
    ),
    CONSTRAINT school_calendar_notification_job_student_channel_valid CHECK (
        audience_kind <> 'ALL_STUDENTS' OR request_push = FALSE
    ),
    CONSTRAINT school_calendar_notification_job_attempt_valid CHECK (
        attempt_count BETWEEN 0 AND 10
    ),
    CONSTRAINT school_calendar_notification_job_recipient_valid CHECK (
        recipient_count IS NULL OR recipient_count >= 0
    ),
    CONSTRAINT school_calendar_notification_job_lifecycle_valid CHECK (
        (status = 'PENDING' AND announcement_id IS NULL AND recipient_count IS NULL
            AND processed_at IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'MATERIALIZED' AND announcement_id IS NOT NULL AND recipient_count IS NOT NULL
            AND processed_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND announcement_id IS NULL AND recipient_count IS NULL
            AND processed_at IS NULL AND cancelled_at IS NOT NULL
            AND length(btrim(cancellation_reason)) BETWEEN 3 AND 500)
        OR (status = 'FAILED' AND announcement_id IS NULL AND recipient_count IS NULL
            AND processed_at IS NOT NULL AND last_error_code IS NOT NULL
            AND cancelled_at IS NULL AND cancellation_reason IS NULL)
    ),
    CONSTRAINT school_calendar_notification_job_status_valid CHECK (
        status IN ('PENDING', 'MATERIALIZED', 'CANCELLED', 'FAILED')
    )
);

CREATE INDEX school_calendar_notification_jobs_due_idx
    ON school_calendar_notification_jobs (next_attempt_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX school_calendar_notification_jobs_scope_idx
    ON school_calendar_notification_jobs (organisation_id, school_id, notice_series_id, created_at DESC);

ALTER TABLE school_calendar_days ADD COLUMN authority_notice_revision_id UUID;
ALTER TABLE school_calendar_days ADD CONSTRAINT school_calendar_days_authority_notice_fk
    FOREIGN KEY (authority_notice_revision_id, organisation_id, school_id)
    REFERENCES school_calendar_notice_revisions(id, organisation_id, school_id);

CREATE OR REPLACE FUNCTION prevent_school_calendar_evidence_rewrite()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'school calendar revisions are append-only';
END;
$$;

CREATE TRIGGER school_calendar_policy_prevent_update
    BEFORE UPDATE ON school_calendar_policy_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_school_calendar_evidence_rewrite();
CREATE TRIGGER school_calendar_policy_prevent_delete
    BEFORE DELETE ON school_calendar_policy_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_school_calendar_evidence_rewrite();
CREATE TRIGGER school_calendar_notice_series_prevent_update
    BEFORE UPDATE ON school_calendar_notice_series
    FOR EACH ROW EXECUTE FUNCTION prevent_school_calendar_evidence_rewrite();
CREATE TRIGGER school_calendar_notice_series_prevent_delete
    BEFORE DELETE ON school_calendar_notice_series
    FOR EACH ROW EXECUTE FUNCTION prevent_school_calendar_evidence_rewrite();
CREATE TRIGGER school_calendar_notice_revision_prevent_update
    BEFORE UPDATE ON school_calendar_notice_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_school_calendar_evidence_rewrite();
CREATE TRIGGER school_calendar_notice_revision_prevent_delete
    BEFORE DELETE ON school_calendar_notice_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_school_calendar_evidence_rewrite();

CREATE OR REPLACE FUNCTION guard_school_calendar_notice_review()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'school calendar reviews cannot be deleted'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.row_version <> 0 THEN
            RAISE EXCEPTION 'school calendar reviews must enter through the pending workflow';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.notice_series_id <> NEW.notice_series_id
       OR OLD.notice_revision_id <> NEW.notice_revision_id
       OR OLD.policy_revision_id <> NEW.policy_revision_id
       OR OLD.submission_reason <> NEW.submission_reason
       OR OLD.submitted_by_membership_id <> NEW.submitted_by_membership_id
       OR OLD.submitted_at <> NEW.submitted_at THEN
        RAISE EXCEPTION 'school calendar review evidence is immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED', 'REJECTED')
       OR current_setting('app.school_calendar_review_decision', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'school calendar reviews can only change through the approved decision workflow';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER school_calendar_notice_reviews_guard
    BEFORE INSERT OR UPDATE OR DELETE ON school_calendar_notice_reviews
    FOR EACH ROW EXECUTE FUNCTION guard_school_calendar_notice_review();

CREATE OR REPLACE FUNCTION guard_school_calendar_notification_job()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'school calendar notification jobs cannot be deleted'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.attempt_count <> 0 OR NEW.row_version <> 0 THEN
            RAISE EXCEPTION 'school calendar notification jobs must enter through the pending workflow';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.notice_series_id <> NEW.notice_series_id
       OR OLD.notice_revision_id <> NEW.notice_revision_id OR OLD.notice_review_id <> NEW.notice_review_id
       OR OLD.audience_kind <> NEW.audience_kind OR OLD.request_push <> NEW.request_push
       OR OLD.requires_acknowledgement <> NEW.requires_acknowledgement
       OR OLD.scheduled_for <> NEW.scheduled_for OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'school calendar notification job identity is immutable';
    END IF;
    IF OLD.status IN ('MATERIALIZED', 'CANCELLED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal school calendar notification jobs are immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('PENDING', 'MATERIALIZED', 'CANCELLED', 'FAILED') THEN
        RAISE EXCEPTION 'invalid school calendar notification job transition';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'school calendar notification attempts cannot decrease';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER school_calendar_notification_jobs_guard
    BEFORE INSERT OR UPDATE OR DELETE ON school_calendar_notification_jobs
    FOR EACH ROW EXECUTE FUNCTION guard_school_calendar_notification_job();

CREATE OR REPLACE FUNCTION guard_authority_calendar_day()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.authority_notice_revision_id IS NOT NULL THEN
            RAISE EXCEPTION 'authority-published calendar days cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.authority_notice_revision_id IS NOT NULL
       AND current_setting('app.school_calendar_sync', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'authority-published calendar days change only through an approved notice revision';
    END IF;
    IF TG_OP = 'INSERT' THEN RETURN NEW; END IF;
    IF (OLD.authority_notice_revision_id IS NOT NULL OR NEW.authority_notice_revision_id IS NOT NULL)
       AND current_setting('app.school_calendar_sync', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'authority-published calendar days change only through an approved notice revision';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER school_calendar_days_authority_guard
    BEFORE INSERT OR UPDATE OR DELETE ON school_calendar_days
    FOR EACH ROW EXECUTE FUNCTION guard_authority_calendar_day();

ALTER TABLE school_calendar_policy_revisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_calendar_policy_tenant_all ON school_calendar_policy_revisions
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE school_calendar_notice_series ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_calendar_notice_series_tenant_all ON school_calendar_notice_series
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE school_calendar_notice_revisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_calendar_notice_revisions_tenant_all ON school_calendar_notice_revisions
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE school_calendar_notice_reviews ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_calendar_notice_reviews_tenant_all ON school_calendar_notice_reviews
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE school_calendar_notification_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_calendar_notification_jobs_tenant_all ON school_calendar_notification_jobs
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE school_calendar_policy_revisions IS
    'Append-only campus/session notification policy revisions. School times, cut-offs, reminder time, channels, and acknowledgement policy remain authority-configured facts.';
COMMENT ON TABLE school_calendar_notice_revisions IS
    'Append-only bilingual holiday, closure, and reopening revisions; corrections supersede prior evidence and never rewrite it.';
COMMENT ON TABLE school_calendar_notice_reviews IS
    'Immutable maker-checker decisions over one exact school calendar notice revision.';
COMMENT ON TABLE school_calendar_notification_jobs IS
    'Retry-safe due-time audience materialization for approved school calendar notices. Provider delivery state remains in the communication domain.';
