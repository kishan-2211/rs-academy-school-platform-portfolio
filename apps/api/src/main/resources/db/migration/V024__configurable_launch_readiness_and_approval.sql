INSERT INTO capabilities (code, description, risk_level) VALUES
    ('launch.configure', 'Maintain school-scoped launch requirements and submit frozen readiness evidence', 'RESTRICTED'),
    ('launch.approve', 'Independently approve or reject a frozen school launch review', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'launch.configure'),
    ('00000000-0000-4000-8000-000000000001', 'launch.approve'),
    ('00000000-0000-4000-8000-000000000007', 'launch.configure'),
    ('00000000-0000-4000-8000-000000000007', 'launch.approve')
ON CONFLICT DO NOTHING;

CREATE TABLE school_launch_readiness_items (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    control_code VARCHAR(60) NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_item_id UUID,
    system_control BOOLEAN NOT NULL DEFAULT FALSE,
    category VARCHAR(40) NOT NULL,
    title VARCHAR(180) NOT NULL,
    guidance VARCHAR(1000) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    evidence_reference VARCHAR(500),
    owner_role_label VARCHAR(120),
    review_note VARCHAR(1000),
    revised_by_membership_id UUID,
    revised_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_launch_items_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_launch_items_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT school_launch_items_supersedes_fk
        FOREIGN KEY (supersedes_item_id, organisation_id, school_id)
        REFERENCES school_launch_readiness_items(id, organisation_id, school_id),
    CONSTRAINT school_launch_items_reviser_fk
        FOREIGN KEY (revised_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_launch_items_revision_unique
        UNIQUE (school_id, control_code, revision_number),
    CONSTRAINT school_launch_items_code_valid CHECK (control_code ~ '^[A-Z][A-Z0-9_]{2,59}$'),
    CONSTRAINT school_launch_items_revision_valid CHECK (
        (revision_number = 1 AND supersedes_item_id IS NULL)
        OR (revision_number BETWEEN 2 AND 1000 AND supersedes_item_id IS NOT NULL)
    ),
    CONSTRAINT school_launch_items_category_valid CHECK (category IN (
        'SCHOOL_GOVERNANCE', 'PRIVACY_SAFEGUARDING', 'IDENTITY_ACCESS',
        'ACADEMICS', 'FINANCE', 'DATA_MIGRATION', 'PROVIDERS',
        'DEPLOYMENT_SECURITY', 'RELIABILITY', 'ACCESSIBILITY_EXPERIENCE',
        'TRAINING_SUPPORT', 'OTHER'
    )),
    CONSTRAINT school_launch_items_text_valid CHECK (
        length(btrim(title)) BETWEEN 1 AND 180
        AND length(btrim(guidance)) BETWEEN 1 AND 1000
        AND (evidence_reference IS NULL OR length(btrim(evidence_reference)) BETWEEN 1 AND 500)
        AND (owner_role_label IS NULL OR length(btrim(owner_role_label)) BETWEEN 1 AND 120)
        AND (review_note IS NULL OR length(btrim(review_note)) BETWEEN 1 AND 1000)
    ),
    CONSTRAINT school_launch_items_status_valid CHECK (status IN (
        'NOT_STARTED', 'IN_PROGRESS', 'READY', 'BLOCKED', 'NOT_APPLICABLE'
    )),
    CONSTRAINT school_launch_items_evidence_valid CHECK (
        status <> 'READY' OR evidence_reference IS NOT NULL
    ),
    CONSTRAINT school_launch_items_block_valid CHECK (
        status <> 'BLOCKED' OR review_note IS NOT NULL
    ),
    CONSTRAINT school_launch_items_applicability_valid CHECK (
        status <> 'NOT_APPLICABLE' OR (system_control = FALSE AND required = FALSE)
    ),
    CONSTRAINT school_launch_items_actor_valid CHECK (
        (system_control = TRUE AND revision_number = 1 AND revised_by_membership_id IS NULL)
        OR revised_by_membership_id IS NOT NULL
    )
);

CREATE INDEX school_launch_items_scope_code_idx
    ON school_launch_readiness_items (organisation_id, school_id, control_code, revision_number DESC);

CREATE TABLE school_launch_reviews (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    review_number INTEGER NOT NULL,
    supersedes_review_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    item_count INTEGER NOT NULL,
    required_count INTEGER NOT NULL,
    ready_count INTEGER NOT NULL,
    blocked_count INTEGER NOT NULL,
    readiness_snapshot JSONB NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    submission_statement VARCHAR(1000) NOT NULL,
    submission_reason VARCHAR(500) NOT NULL,
    submitted_by_membership_id UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_launch_reviews_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_launch_reviews_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT school_launch_reviews_supersedes_fk
        FOREIGN KEY (supersedes_review_id, organisation_id, school_id)
        REFERENCES school_launch_reviews(id, organisation_id, school_id),
    CONSTRAINT school_launch_reviews_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_launch_reviews_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT school_launch_reviews_number_unique UNIQUE (school_id, review_number),
    CONSTRAINT school_launch_reviews_number_valid CHECK (
        (review_number = 1 AND supersedes_review_id IS NULL)
        OR (review_number BETWEEN 2 AND 1000 AND supersedes_review_id IS NOT NULL)
    ),
    CONSTRAINT school_launch_reviews_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT school_launch_reviews_counts_valid CHECK (
        item_count > 0 AND required_count > 0 AND ready_count >= required_count
        AND ready_count <= item_count AND blocked_count = 0
    ),
    CONSTRAINT school_launch_reviews_snapshot_valid CHECK (
        jsonb_typeof(readiness_snapshot) = 'object'
        AND snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT school_launch_reviews_submission_valid CHECK (
        length(btrim(submission_statement)) BETWEEN 1 AND 1000
        AND length(btrim(submission_reason)) BETWEEN 1 AND 500
    ),
    CONSTRAINT school_launch_reviews_lifecycle_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL
          AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('APPROVED', 'REJECTED')
          AND decided_by_membership_id IS NOT NULL
          AND decided_by_membership_id <> submitted_by_membership_id
          AND decided_at IS NOT NULL
          AND length(btrim(decision_reason)) BETWEEN 1 AND 1000)
    )
);

CREATE UNIQUE INDEX school_launch_reviews_one_pending_idx
    ON school_launch_reviews (school_id) WHERE status = 'PENDING';
CREATE INDEX school_launch_reviews_scope_history_idx
    ON school_launch_reviews (organisation_id, school_id, review_number DESC);

CREATE OR REPLACE FUNCTION prevent_launch_readiness_rewrite()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'launch readiness revisions are append-only';
END; $$;

CREATE TRIGGER school_launch_items_prevent_update
    BEFORE UPDATE ON school_launch_readiness_items
    FOR EACH ROW EXECUTE FUNCTION prevent_launch_readiness_rewrite();
CREATE TRIGGER school_launch_items_prevent_delete
    BEFORE DELETE ON school_launch_readiness_items
    FOR EACH ROW EXECUTE FUNCTION prevent_launch_readiness_rewrite();

CREATE OR REPLACE FUNCTION guard_school_launch_review()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.review_number <> NEW.review_number
       OR OLD.supersedes_review_id IS DISTINCT FROM NEW.supersedes_review_id
       OR OLD.item_count <> NEW.item_count OR OLD.required_count <> NEW.required_count
       OR OLD.ready_count <> NEW.ready_count OR OLD.blocked_count <> NEW.blocked_count
       OR OLD.readiness_snapshot <> NEW.readiness_snapshot
       OR OLD.snapshot_sha256 <> NEW.snapshot_sha256
       OR OLD.submission_statement <> NEW.submission_statement
       OR OLD.submission_reason <> NEW.submission_reason
       OR OLD.submitted_by_membership_id <> NEW.submitted_by_membership_id
       OR OLD.submitted_at <> NEW.submitted_at THEN
        RAISE EXCEPTION 'launch review evidence is immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED', 'REJECTED')
       OR current_setting('app.launch_review_decision', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'launch reviews can only change through the approved decision workflow';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER school_launch_reviews_guard
    BEFORE UPDATE ON school_launch_reviews
    FOR EACH ROW EXECUTE FUNCTION guard_school_launch_review();
CREATE TRIGGER school_launch_reviews_prevent_delete
    BEFORE DELETE ON school_launch_reviews
    FOR EACH ROW EXECUTE FUNCTION prevent_launch_readiness_rewrite();

CREATE OR REPLACE FUNCTION seed_school_launch_controls_for(
    target_organisation_id UUID,
    target_school_id UUID
) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO school_launch_readiness_items (
        id, organisation_id, school_id, control_code, revision_number,
        supersedes_item_id, system_control, category, title, guidance,
        required, status, revised_by_membership_id, revised_at, row_version
    )
    SELECT gen_random_uuid(), target_organisation_id, target_school_id,
        template.control_code, 1, NULL, TRUE, template.category,
        template.title, template.guidance, TRUE, 'NOT_STARTED', NULL,
        CURRENT_TIMESTAMP, 0
    FROM (VALUES
        ('SCHOOL_POLICY_SIGNOFF', 'SCHOOL_GOVERNANCE', 'School facts and policies approved',
         'Named school leadership confirms identity, roles, policies, public claims, and operating ownership.'),
        ('PRIVACY_SAFEGUARDING_SIGNOFF', 'PRIVACY_SAFEGUARDING', 'Privacy and safeguarding approved',
         'The school approves child-data handling, safeguarding escalation, retention, consent, and rights procedures.'),
        ('PRIVILEGED_ACCESS_REHEARSAL', 'IDENTITY_ACCESS', 'Privileged access and recovery rehearsed',
         'Least-privilege roles, MFA, recovery, session revocation, emergency access, and offboarding are tested.'),
        ('ACADEMIC_ACCEPTANCE', 'ACADEMICS', 'Academic cycle accepted',
         'School users complete a representative timetable, attendance, learning, assessment, result, and correction cycle.'),
        ('FINANCE_ACCEPTANCE', 'FINANCE', 'Finance controls accepted',
         'Finance owners reconcile plans, receipts, concessions, reversals, ageing, daily close, and approval separation.'),
        ('DATA_MIGRATION_RECONCILIATION', 'DATA_MIGRATION', 'Migration totals reconciled',
         'Pilot import counts, duplicates, balances, enrolments, and source-to-platform reconciliation are signed off.'),
        ('DEPLOYMENT_SECURITY_REVIEW', 'DEPLOYMENT_SECURITY', 'Production infrastructure secured',
         'Domains, TLS, private data services, secrets, origin restrictions, scans, and high-risk findings are approved.'),
        ('BACKUP_RESTORE_DRILL', 'RELIABILITY', 'Backup and restore demonstrated',
         'A timed restore proves database, private objects, configuration, checksums, monitoring, RPO, and RTO.'),
        ('ACCESSIBILITY_PERFORMANCE_ACCEPTANCE', 'ACCESSIBILITY_EXPERIENCE', 'Accessibility and performance accepted',
         'WCAG 2.2 AA, keyboard, assistive technology, mobile, low-bandwidth, bilingual, and peak-load journeys pass.'),
        ('TRAINING_INCIDENT_LAUNCH_PLAN', 'TRAINING_SUPPORT', 'Training, incident, and launch plan approved',
         'Pilot users, support owners, escalation, rollback, maintenance communication, and post-launch monitoring are ready.')
    ) AS template(control_code, category, title, guidance)
    ON CONFLICT (school_id, control_code, revision_number) DO NOTHING;
END; $$;

SELECT seed_school_launch_controls_for(organisation_id, id) FROM schools;

CREATE OR REPLACE FUNCTION seed_school_launch_controls_on_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM seed_school_launch_controls_for(NEW.organisation_id, NEW.id);
    RETURN NEW;
END; $$;

CREATE TRIGGER schools_seed_launch_controls
    AFTER INSERT ON schools
    FOR EACH ROW EXECUTE FUNCTION seed_school_launch_controls_on_insert();

ALTER TABLE school_launch_readiness_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_launch_items_tenant_all ON school_launch_readiness_items
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE school_launch_reviews ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_launch_reviews_tenant_all ON school_launch_reviews
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE school_launch_readiness_items IS
    'Append-only configurable school launch controls. System baselines are required safety gates; authority revisions carry operational evidence references, never credentials or raw private data.';
COMMENT ON TABLE school_launch_reviews IS
    'Immutable maker-checker launch reviews over a frozen readiness snapshot. Approval records evidence only and never deploys or changes production state automatically.';
