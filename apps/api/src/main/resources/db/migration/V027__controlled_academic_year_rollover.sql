INSERT INTO capabilities (code, description, risk_level) VALUES
    ('academic.rollover.manage',
     'Preview and execute a school-wide academic-year rollover with student promotion and immutable evidence',
     'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'academic.rollover.manage'),
    ('00000000-0000-4000-8000-000000000007', 'academic.rollover.manage')
ON CONFLICT DO NOTHING;

CREATE TABLE academic_rollover_batches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    source_session_id UUID NOT NULL,
    target_session_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    copy_teaching_allocations BOOLEAN NOT NULL,
    copy_published_timetable BOOLEAN NOT NULL,
    copy_fee_plans BOOLEAN NOT NULL,
    copy_calendar_policy BOOLEAN NOT NULL,
    promoted_count INTEGER NOT NULL,
    retained_count INTEGER NOT NULL,
    leaving_count INTEGER NOT NULL,
    section_count INTEGER NOT NULL,
    term_count INTEGER NOT NULL,
    resource_count INTEGER NOT NULL,
    offering_count INTEGER NOT NULL,
    allocation_count INTEGER NOT NULL,
    period_count INTEGER NOT NULL,
    timetable_plan_count INTEGER NOT NULL,
    timetable_entry_count INTEGER NOT NULL,
    fee_plan_count INTEGER NOT NULL,
    fee_item_count INTEGER NOT NULL,
    calendar_policy_count INTEGER NOT NULL,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    execution_reason VARCHAR(500) NOT NULL,
    executed_by_membership_id UUID NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'EXECUTED',
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT academic_rollover_batches_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT academic_rollover_batches_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT academic_rollover_batches_source_session_fk
        FOREIGN KEY (source_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_batches_target_session_fk
        FOREIGN KEY (target_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_batches_actor_fk
        FOREIGN KEY (executed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_batches_source_unique UNIQUE (source_session_id),
    CONSTRAINT academic_rollover_batches_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT academic_rollover_batches_sessions_differ CHECK (source_session_id <> target_session_id),
    CONSTRAINT academic_rollover_batches_hash_valid CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT academic_rollover_batches_reason_valid
        CHECK (length(btrim(execution_reason)) BETWEEN 3 AND 500),
    CONSTRAINT academic_rollover_batches_status_valid CHECK (status = 'EXECUTED'),
    CONSTRAINT academic_rollover_batches_warnings_valid CHECK (jsonb_typeof(warnings) = 'array'),
    CONSTRAINT academic_rollover_batches_counts_valid CHECK (
        promoted_count >= 0 AND retained_count >= 0 AND leaving_count >= 0
        AND section_count >= 0 AND term_count >= 0 AND resource_count >= 0
        AND offering_count >= 0 AND allocation_count >= 0 AND period_count >= 0
        AND timetable_plan_count >= 0 AND timetable_entry_count >= 0
        AND fee_plan_count >= 0 AND fee_item_count >= 0 AND calendar_policy_count >= 0
    )
);

ALTER TABLE student_enrolments
    DROP CONSTRAINT student_enrolments_exactly_one_origin,
    ADD COLUMN origin_rollover_batch_id UUID,
    ADD COLUMN prior_enrolment_id UUID,
    ADD CONSTRAINT student_enrolments_origin_rollover_batch_fk
        FOREIGN KEY (origin_rollover_batch_id, organisation_id, school_id)
        REFERENCES academic_rollover_batches(id, organisation_id, school_id),
    ADD CONSTRAINT student_enrolments_prior_enrolment_fk
        FOREIGN KEY (prior_enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    ADD CONSTRAINT student_enrolments_prior_enrolment_unique UNIQUE (prior_enrolment_id),
    ADD CONSTRAINT student_enrolments_exactly_one_origin CHECK (
        num_nonnulls(origin_application_id, origin_import_row_id, origin_rollover_batch_id) = 1
    ),
    ADD CONSTRAINT student_enrolments_rollover_origin_valid CHECK (
        (origin_rollover_batch_id IS NULL AND prior_enrolment_id IS NULL)
        OR (origin_rollover_batch_id IS NOT NULL AND prior_enrolment_id IS NOT NULL)
    );

DROP INDEX student_enrolments_school_admission_number_unique;

CREATE TABLE learner_admission_identifiers (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    admission_number VARCHAR(80) NOT NULL,
    admission_number_normalized VARCHAR(80) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT learner_admission_identifiers_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_admission_identifiers_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT learner_admission_identifiers_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_admission_identifiers_number_unique
        UNIQUE (school_id, admission_number_normalized),
    CONSTRAINT learner_admission_identifiers_learner_unique UNIQUE (school_id, learner_id),
    CONSTRAINT learner_admission_identifiers_normalized_valid CHECK (
        admission_number_normalized = lower(btrim(admission_number))
        AND length(admission_number_normalized) BETWEEN 1 AND 80
    )
);

INSERT INTO learner_admission_identifiers (
    id, organisation_id, school_id, learner_id, admission_number,
    admission_number_normalized, assigned_at
)
SELECT gen_random_uuid(), organisation_id, school_id, learner_id, admission_number,
       lower(btrim(admission_number)), created_at
FROM student_enrolments
WHERE admission_number IS NOT NULL;

CREATE INDEX student_enrolments_school_admission_number_idx
    ON student_enrolments (school_id, lower(admission_number))
    WHERE admission_number IS NOT NULL;

CREATE OR REPLACE FUNCTION register_student_admission_identifier()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    normalized_number VARCHAR(80);
    existing_learner UUID;
    existing_number VARCHAR(80);
BEGIN
    IF NEW.admission_number IS NULL THEN
        RETURN NEW;
    END IF;
    normalized_number := lower(btrim(NEW.admission_number));
    IF length(normalized_number) = 0 THEN
        RAISE EXCEPTION 'admission number cannot be blank';
    END IF;

    SELECT learner_id INTO existing_learner
    FROM learner_admission_identifiers
    WHERE school_id = NEW.school_id AND admission_number_normalized = normalized_number;
    IF existing_learner IS NOT NULL AND existing_learner <> NEW.learner_id THEN
        RAISE EXCEPTION 'admission number already belongs to another learner';
    END IF;

    SELECT admission_number_normalized INTO existing_number
    FROM learner_admission_identifiers
    WHERE school_id = NEW.school_id AND learner_id = NEW.learner_id;
    IF existing_number IS NOT NULL AND existing_number <> normalized_number THEN
        RAISE EXCEPTION 'learner admission number is immutable';
    END IF;

    INSERT INTO learner_admission_identifiers (
        id, organisation_id, school_id, learner_id, admission_number,
        admission_number_normalized, assigned_at
    ) VALUES (
        gen_random_uuid(), NEW.organisation_id, NEW.school_id, NEW.learner_id,
        btrim(NEW.admission_number), normalized_number, COALESCE(NEW.created_at, CURRENT_TIMESTAMP)
    )
    ON CONFLICT DO NOTHING;
    NEW.admission_number := btrim(NEW.admission_number);
    RETURN NEW;
END;
$$;

CREATE TRIGGER student_enrolments_register_admission_identifier
    BEFORE INSERT OR UPDATE OF admission_number, learner_id, school_id
    ON student_enrolments
    FOR EACH ROW EXECUTE FUNCTION register_student_admission_identifier();

CREATE TABLE academic_rollover_student_decisions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    rollover_batch_id UUID NOT NULL,
    source_enrolment_id UUID NOT NULL,
    source_placement_id UUID,
    learner_id UUID NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    target_grade_code VARCHAR(40),
    target_section_code VARCHAR(30),
    target_roll_number VARCHAR(30),
    leave_status VARCHAR(20),
    decision_reason VARCHAR(240) NOT NULL,
    target_enrolment_id UUID,
    target_placement_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT academic_rollover_decisions_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_batch_fk
        FOREIGN KEY (rollover_batch_id, organisation_id, school_id)
        REFERENCES academic_rollover_batches(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_source_enrolment_fk
        FOREIGN KEY (source_enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_source_placement_fk
        FOREIGN KEY (source_placement_id, organisation_id, school_id)
        REFERENCES student_section_placements(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_target_enrolment_fk
        FOREIGN KEY (target_enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_target_placement_fk
        FOREIGN KEY (target_placement_id, organisation_id, school_id)
        REFERENCES student_section_placements(id, organisation_id, school_id),
    CONSTRAINT academic_rollover_decisions_source_unique
        UNIQUE (rollover_batch_id, source_enrolment_id),
    CONSTRAINT academic_rollover_decisions_target_enrolment_unique UNIQUE (target_enrolment_id),
    CONSTRAINT academic_rollover_decisions_target_placement_unique UNIQUE (target_placement_id),
    CONSTRAINT academic_rollover_decisions_outcome_valid
        CHECK (outcome IN ('PROMOTE', 'RETAIN', 'LEAVE')),
    CONSTRAINT academic_rollover_decisions_leave_status_valid
        CHECK (leave_status IS NULL OR leave_status IN ('INACTIVE', 'ALUMNI')),
    CONSTRAINT academic_rollover_decisions_reason_valid
        CHECK (length(btrim(decision_reason)) BETWEEN 3 AND 240),
    CONSTRAINT academic_rollover_decisions_shape_valid CHECK (
        (outcome IN ('PROMOTE', 'RETAIN')
            AND target_grade_code IS NOT NULL AND target_section_code IS NOT NULL
            AND leave_status IS NULL
            AND target_enrolment_id IS NOT NULL AND target_placement_id IS NOT NULL)
        OR (outcome = 'LEAVE'
            AND target_grade_code IS NULL AND target_section_code IS NULL
            AND target_roll_number IS NULL
            AND leave_status IS NOT NULL
            AND target_enrolment_id IS NULL AND target_placement_id IS NULL)
    )
);

CREATE INDEX academic_rollover_batches_school_time_idx
    ON academic_rollover_batches (organisation_id, school_id, executed_at DESC);
CREATE INDEX academic_rollover_decisions_batch_idx
    ON academic_rollover_student_decisions (rollover_batch_id, outcome, learner_id);

CREATE OR REPLACE FUNCTION guard_academic_rollover_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'academic rollover evidence is immutable';
END;
$$;

CREATE TRIGGER academic_rollover_batches_immutable
    BEFORE UPDATE OR DELETE ON academic_rollover_batches
    FOR EACH ROW EXECUTE FUNCTION guard_academic_rollover_evidence();
CREATE TRIGGER academic_rollover_decisions_immutable
    BEFORE UPDATE OR DELETE ON academic_rollover_student_decisions
    FOR EACH ROW EXECUTE FUNCTION guard_academic_rollover_evidence();
CREATE TRIGGER learner_admission_identifiers_immutable
    BEFORE UPDATE OR DELETE ON learner_admission_identifiers
    FOR EACH ROW EXECUTE FUNCTION guard_academic_rollover_evidence();

ALTER TABLE academic_rollover_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE academic_rollover_student_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_admission_identifiers ENABLE ROW LEVEL SECURITY;

CREATE POLICY academic_rollover_batches_tenant_select ON academic_rollover_batches
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY academic_rollover_batches_tenant_insert ON academic_rollover_batches
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY academic_rollover_decisions_tenant_select ON academic_rollover_student_decisions
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY academic_rollover_decisions_tenant_insert ON academic_rollover_student_decisions
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY learner_admission_identifiers_tenant_select ON learner_admission_identifiers
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY learner_admission_identifiers_tenant_insert ON learner_admission_identifiers
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE academic_rollover_batches IS
    'Immutable evidence for one preview-bound, idempotent, atomic school academic-year rollover.';
COMMENT ON TABLE academic_rollover_student_decisions IS
    'Immutable per-learner promotion, retention, or leaving evidence produced by an executed rollover.';
COMMENT ON TABLE learner_admission_identifiers IS
    'Permanent school admission-number ownership, allowing the same learner identifier across historical enrolments without reuse by another learner.';
