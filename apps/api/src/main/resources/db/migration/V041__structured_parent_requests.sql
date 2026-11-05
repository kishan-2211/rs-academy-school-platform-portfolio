-- Structured family support replaces ungoverned chat with scoped, auditable school workflows.
INSERT INTO capabilities(code,description,risk_level) VALUES
    ('parent_requests.read','Read all parent requests within an authorized school','SENSITIVE'),
    ('parent_requests.respond','Respond to parent requests explicitly assigned to the staff member','SENSITIVE'),
    ('parent_requests.manage','Assign, prioritize, close, and reopen parent requests','RESTRICTED')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_capabilities(role_id,capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001','parent_requests.read'),
    ('00000000-0000-4000-8000-000000000001','parent_requests.respond'),
    ('00000000-0000-4000-8000-000000000001','parent_requests.manage'),
    ('00000000-0000-4000-8000-000000000007','parent_requests.read'),
    ('00000000-0000-4000-8000-000000000007','parent_requests.respond'),
    ('00000000-0000-4000-8000-000000000007','parent_requests.manage'),
    ('00000000-0000-4000-8000-000000000002','parent_requests.respond'),
    ('00000000-0000-4000-8000-000000000003','parent_requests.respond'),
    ('00000000-0000-4000-8000-000000000004','parent_requests.respond'),
    ('00000000-0000-4000-8000-000000000009','parent_requests.respond')
ON CONFLICT DO NOTHING;

CREATE TABLE parent_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    request_number VARCHAR(32) NOT NULL,
    learner_id UUID NOT NULL,
    opened_by_guardian_id UUID NOT NULL,
    learner_name_snapshot VARCHAR(160) NOT NULL,
    guardian_name_snapshot VARCHAR(120) NOT NULL,
    category VARCHAR(30) NOT NULL,
    subject VARCHAR(180) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assigned_staff_profile_id UUID,
    assigned_by_membership_id UUID,
    assigned_at TIMESTAMPTZ,
    resolved_by_membership_id UUID,
    resolved_at TIMESTAMPTZ,
    resolution_summary VARCHAR(1000),
    closed_by_membership_id UUID,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(500),
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT parent_requests_id_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT parent_requests_number_unique UNIQUE(school_id,request_number),
    CONSTRAINT parent_requests_retry_unique UNIQUE(school_id,opened_by_guardian_id,idempotency_key),
    CONSTRAINT parent_requests_school_fk FOREIGN KEY(school_id,organisation_id)
        REFERENCES schools(id,organisation_id),
    CONSTRAINT parent_requests_learner_fk FOREIGN KEY(learner_id,organisation_id,school_id)
        REFERENCES learners(id,organisation_id,school_id),
    CONSTRAINT parent_requests_guardian_fk FOREIGN KEY(opened_by_guardian_id,organisation_id,school_id)
        REFERENCES guardians(id,organisation_id,school_id),
    CONSTRAINT parent_requests_assignee_fk FOREIGN KEY(assigned_staff_profile_id,organisation_id,school_id)
        REFERENCES staff_profiles(id,organisation_id,school_id),
    CONSTRAINT parent_requests_assigner_fk FOREIGN KEY(assigned_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT parent_requests_resolver_fk FOREIGN KEY(resolved_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT parent_requests_closer_fk FOREIGN KEY(closed_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT parent_requests_category_valid CHECK(category IN
        ('FEES','ATTENDANCE','ACADEMICS','HOMEWORK','TRANSPORT','ADMISSION','CERTIFICATES','GENERAL')),
    CONSTRAINT parent_requests_priority_valid CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CONSTRAINT parent_requests_status_valid CHECK(status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED')),
    CONSTRAINT parent_requests_text_valid CHECK(
        length(btrim(request_number)) BETWEEN 8 AND 32
        AND length(btrim(idempotency_key)) BETWEEN 8 AND 100
        AND request_fingerprint ~ '^[0-9a-f]{64}$'
        AND length(btrim(learner_name_snapshot)) BETWEEN 1 AND 160
        AND length(btrim(guardian_name_snapshot)) BETWEEN 1 AND 120
        AND length(btrim(subject)) BETWEEN 3 AND 180),
    CONSTRAINT parent_requests_activity_valid CHECK(last_activity_at>=opened_at AND updated_at>=opened_at),
    CONSTRAINT parent_requests_lifecycle_valid CHECK(
        (status='OPEN' AND assigned_staff_profile_id IS NULL AND assigned_by_membership_id IS NULL
          AND assigned_at IS NULL AND resolved_by_membership_id IS NULL AND resolved_at IS NULL
          AND resolution_summary IS NULL AND closed_by_membership_id IS NULL AND closed_at IS NULL
          AND close_reason IS NULL)
        OR (status IN ('ASSIGNED','IN_PROGRESS') AND assigned_staff_profile_id IS NOT NULL
          AND assigned_by_membership_id IS NOT NULL AND assigned_at IS NOT NULL
          AND resolved_by_membership_id IS NULL AND resolved_at IS NULL AND resolution_summary IS NULL
          AND closed_by_membership_id IS NULL AND closed_at IS NULL AND close_reason IS NULL)
        OR (status='RESOLVED' AND assigned_staff_profile_id IS NOT NULL
          AND assigned_by_membership_id IS NOT NULL AND assigned_at IS NOT NULL
          AND resolved_by_membership_id IS NOT NULL AND resolved_at IS NOT NULL
          AND length(btrim(resolution_summary)) BETWEEN 3 AND 1000
          AND closed_by_membership_id IS NULL AND closed_at IS NULL AND close_reason IS NULL)
        OR (status='CLOSED' AND assigned_staff_profile_id IS NOT NULL
          AND assigned_by_membership_id IS NOT NULL AND assigned_at IS NOT NULL
          AND resolved_by_membership_id IS NOT NULL AND resolved_at IS NOT NULL
          AND length(btrim(resolution_summary)) BETWEEN 3 AND 1000
          AND closed_by_membership_id IS NOT NULL AND closed_at IS NOT NULL
          AND length(btrim(close_reason)) BETWEEN 3 AND 500))
);

CREATE INDEX parent_requests_queue_idx
    ON parent_requests(organisation_id,school_id,status,priority,last_activity_at DESC,id);
CREATE INDEX parent_requests_assignee_idx
    ON parent_requests(organisation_id,school_id,assigned_staff_profile_id,status,last_activity_at DESC,id)
    WHERE assigned_staff_profile_id IS NOT NULL;
CREATE INDEX parent_requests_family_idx
    ON parent_requests(organisation_id,school_id,learner_id,opened_by_guardian_id,last_activity_at DESC,id);

CREATE TABLE parent_request_messages (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    parent_request_id UUID NOT NULL,
    author_type VARCHAR(20) NOT NULL,
    author_guardian_id UUID,
    author_staff_profile_id UUID,
    author_membership_id UUID NOT NULL,
    author_name_snapshot VARCHAR(120) NOT NULL,
    body VARCHAR(8000) NOT NULL,
    internal_note BOOLEAN NOT NULL DEFAULT FALSE,
    idempotency_key VARCHAR(100) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT parent_request_messages_id_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT parent_request_messages_request_fk FOREIGN KEY(parent_request_id,organisation_id,school_id)
        REFERENCES parent_requests(id,organisation_id,school_id),
    CONSTRAINT parent_request_messages_guardian_fk FOREIGN KEY(author_guardian_id,organisation_id,school_id)
        REFERENCES guardians(id,organisation_id,school_id),
    CONSTRAINT parent_request_messages_staff_fk FOREIGN KEY(author_staff_profile_id,organisation_id,school_id)
        REFERENCES staff_profiles(id,organisation_id,school_id),
    CONSTRAINT parent_request_messages_membership_fk FOREIGN KEY(author_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT parent_request_messages_retry_unique UNIQUE(parent_request_id,author_membership_id,idempotency_key),
    CONSTRAINT parent_request_messages_author_valid CHECK(
        (author_type='GUARDIAN' AND author_guardian_id IS NOT NULL AND author_staff_profile_id IS NULL
          AND internal_note=FALSE)
        OR (author_type='STAFF' AND author_guardian_id IS NULL AND author_staff_profile_id IS NOT NULL)),
    CONSTRAINT parent_request_messages_text_valid CHECK(
        length(btrim(author_name_snapshot)) BETWEEN 1 AND 120
        AND length(btrim(body)) BETWEEN 1 AND 8000
        AND length(btrim(idempotency_key)) BETWEEN 8 AND 100
        AND content_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX parent_request_messages_thread_idx
    ON parent_request_messages(organisation_id,school_id,parent_request_id,created_at,id);

CREATE TABLE parent_request_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    parent_request_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    previous_priority VARCHAR(20),
    new_priority VARCHAR(20) NOT NULL,
    previous_assignee_id UUID,
    new_assignee_id UUID,
    reason VARCHAR(1000) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT parent_request_history_request_fk FOREIGN KEY(parent_request_id,organisation_id,school_id)
        REFERENCES parent_requests(id,organisation_id,school_id),
    CONSTRAINT parent_request_history_actor_fk FOREIGN KEY(actor_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT parent_request_history_previous_assignee_fk FOREIGN KEY(previous_assignee_id,organisation_id,school_id)
        REFERENCES staff_profiles(id,organisation_id,school_id),
    CONSTRAINT parent_request_history_new_assignee_fk FOREIGN KEY(new_assignee_id,organisation_id,school_id)
        REFERENCES staff_profiles(id,organisation_id,school_id),
    CONSTRAINT parent_request_history_event_valid CHECK(event_type IN
        ('OPENED','ASSIGNED','REASSIGNED','PRIORITY_CHANGED','STARTED','STAFF_REPLIED','PARENT_REPLIED','RESOLVED','CLOSED','REOPENED')),
    CONSTRAINT parent_request_history_status_valid CHECK(
        (previous_status IS NULL OR previous_status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED'))
        AND new_status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED')),
    CONSTRAINT parent_request_history_priority_valid CHECK(
        (previous_priority IS NULL OR previous_priority IN ('LOW','NORMAL','HIGH','URGENT'))
        AND new_priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CONSTRAINT parent_request_history_reason_valid CHECK(length(btrim(reason)) BETWEEN 1 AND 1000)
);
CREATE INDEX parent_request_history_timeline_idx
    ON parent_request_history(organisation_id,school_id,parent_request_id,occurred_at,id);

CREATE FUNCTION guard_parent_request_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'parent requests are retained and cannot be deleted'; END IF;
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(OLD)-ARRAY['priority','status','assigned_staff_profile_id','assigned_by_membership_id',
            'assigned_at','resolved_by_membership_id','resolved_at','resolution_summary',
            'closed_by_membership_id','closed_at','close_reason','last_activity_at','updated_at','row_version'])
          IS DISTINCT FROM
           (to_jsonb(NEW)-ARRAY['priority','status','assigned_staff_profile_id','assigned_by_membership_id',
            'assigned_at','resolved_by_membership_id','resolved_at','resolution_summary',
            'closed_by_membership_id','closed_at','close_reason','last_activity_at','updated_at','row_version'])
          OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at
          OR NOT ((OLD.status='OPEN' AND NEW.status IN ('OPEN','ASSIGNED'))
              OR (OLD.status='ASSIGNED' AND NEW.status IN ('ASSIGNED','IN_PROGRESS','RESOLVED'))
              OR (OLD.status='IN_PROGRESS' AND NEW.status IN ('IN_PROGRESS','RESOLVED'))
              OR (OLD.status='RESOLVED' AND NEW.status IN ('RESOLVED','ASSIGNED','CLOSED'))
              OR (OLD.status='CLOSED' AND NEW.status='ASSIGNED')) THEN
            RAISE EXCEPTION 'parent request change violates controlled workflow';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER parent_requests_controlled_change
    BEFORE UPDATE OR DELETE ON parent_requests FOR EACH ROW EXECUTE FUNCTION guard_parent_request_change();

CREATE FUNCTION protect_parent_request_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'parent request messages and history are append-only';
END;
$$;
CREATE TRIGGER parent_request_messages_append_only
    BEFORE UPDATE OR DELETE ON parent_request_messages FOR EACH ROW EXECUTE FUNCTION protect_parent_request_evidence();
CREATE TRIGGER parent_request_history_append_only
    BEFORE UPDATE OR DELETE ON parent_request_history FOR EACH ROW EXECUTE FUNCTION protect_parent_request_evidence();

ALTER TABLE parent_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE parent_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY parent_requests_organisation_isolation ON parent_requests
    USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
ALTER TABLE parent_request_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE parent_request_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY parent_request_messages_organisation_isolation ON parent_request_messages
    USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
ALTER TABLE parent_request_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE parent_request_history FORCE ROW LEVEL SECURITY;
CREATE POLICY parent_request_history_organisation_isolation ON parent_request_history
    USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());

COMMENT ON TABLE parent_requests IS
    'School-scoped family support cases. Families use verified learner relationships; staff access is capability and assignment scoped.';
COMMENT ON TABLE parent_request_messages IS
    'Append-only conversation evidence. internal_note is staff-only and must never appear in family projections.';
COMMENT ON TABLE parent_request_history IS
    'Append-only workflow and responsibility history for parent requests.';
