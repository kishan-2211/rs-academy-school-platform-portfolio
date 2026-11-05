-- Student leave is separate from retrospective attendance explanations and staff payroll leave.
INSERT INTO capabilities(code,description,risk_level) VALUES
 ('student_leave.manage','Configure, review and independently decide school-scoped student leave','RESTRICTED')
ON CONFLICT(code) DO NOTHING;
INSERT INTO role_capabilities(role_id,capability_code)
SELECT id,'student_leave.manage' FROM roles WHERE system_role AND code IN ('school_admin','principal')
ON CONFLICT DO NOTHING;

CREATE TABLE student_leave_policies (
 id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
 academic_session_id UUID NOT NULL, max_calendar_days SMALLINT NOT NULL,
 retrospective_days SMALLINT NOT NULL, attendance_state VARCHAR(20) NOT NULL,
 enabled BOOLEAN NOT NULL, updated_by_membership_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(id,organisation_id,school_id), UNIQUE(school_id,academic_session_id),
 FOREIGN KEY(school_id,organisation_id) REFERENCES schools(id,organisation_id),
 FOREIGN KEY(academic_session_id,organisation_id,school_id) REFERENCES academic_sessions(id,organisation_id,school_id),
 FOREIGN KEY(updated_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
 CHECK(max_calendar_days BETWEEN 1 AND 366), CHECK(retrospective_days BETWEEN 0 AND 90),
 CHECK(attendance_state IN ('EXCUSED','ABSENT')), CHECK(row_version>=0), CHECK(updated_at>=created_at)
);

CREATE TABLE student_leave_requests (
 id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
 academic_session_id UUID NOT NULL, campus_id UUID NOT NULL, enrolment_id UUID NOT NULL,
 learner_id UUID NOT NULL, guardian_id UUID NOT NULL, requested_by_user_id UUID NOT NULL REFERENCES user_accounts(id),
 requested_by_membership_id UUID NOT NULL, policy_id UUID NOT NULL, policy_snapshot JSONB NOT NULL,
 starts_on DATE NOT NULL, ends_on DATE NOT NULL, leave_type VARCHAR(20) NOT NULL,
 request_reason VARCHAR(1000) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 request_fingerprint CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
 action_by_membership_id UUID NOT NULL, action_reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(id,organisation_id,school_id), UNIQUE(school_id,guardian_id,idempotency_key),
 FOREIGN KEY(school_id,organisation_id) REFERENCES schools(id,organisation_id),
 FOREIGN KEY(academic_session_id,organisation_id,school_id) REFERENCES academic_sessions(id,organisation_id,school_id),
 FOREIGN KEY(campus_id,organisation_id,school_id) REFERENCES campuses(id,organisation_id,school_id),
 FOREIGN KEY(enrolment_id,organisation_id,school_id) REFERENCES student_enrolments(id,organisation_id,school_id),
 FOREIGN KEY(learner_id,organisation_id,school_id) REFERENCES learners(id,organisation_id,school_id),
 FOREIGN KEY(guardian_id,organisation_id,school_id) REFERENCES guardians(id,organisation_id,school_id),
 FOREIGN KEY(requested_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
 FOREIGN KEY(action_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
 FOREIGN KEY(policy_id,organisation_id,school_id) REFERENCES student_leave_policies(id,organisation_id,school_id),
 CHECK(ends_on>=starts_on AND ends_on-starts_on<366),
 CHECK(leave_type IN ('PERSONAL','ILLNESS','FAMILY','OTHER')),
 CHECK(status IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN','REVOKED')),
 CHECK(length(btrim(request_reason)) BETWEEN 3 AND 1000),
 CHECK(length(btrim(action_reason)) BETWEEN 3 AND 1000),
 CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 100),
 CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
 CHECK(jsonb_typeof(policy_snapshot)='object'), CHECK(row_version>=0), CHECK(updated_at>=created_at)
);
CREATE INDEX student_leave_family_idx ON student_leave_requests(organisation_id,school_id,guardian_id,learner_id,created_at DESC,id);
CREATE INDEX student_leave_review_idx ON student_leave_requests(organisation_id,school_id,status,starts_on,id);

-- Operational reservations, not historical evidence. Unique dates serialize concurrent
-- overlapping submissions, including requests from different guardians of one child.
CREATE TABLE student_leave_date_claims (
 organisation_id UUID NOT NULL, school_id UUID NOT NULL, learner_id UUID NOT NULL,
 calendar_date DATE NOT NULL, request_id UUID NOT NULL,
 PRIMARY KEY(organisation_id,school_id,learner_id,calendar_date),
 FOREIGN KEY(request_id,organisation_id,school_id) REFERENCES student_leave_requests(id,organisation_id,school_id),
 FOREIGN KEY(learner_id,organisation_id,school_id) REFERENCES learners(id,organisation_id,school_id)
);
CREATE INDEX student_leave_claim_request_idx ON student_leave_date_claims(request_id);

CREATE TABLE student_leave_history (
 id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, request_id UUID NOT NULL,
 previous_status VARCHAR(20), new_status VARCHAR(20) NOT NULL,
 actor_membership_id UUID NOT NULL, reason VARCHAR(1000) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL, request_version BIGINT NOT NULL,
 UNIQUE(request_id,request_version),
 FOREIGN KEY(request_id,organisation_id,school_id) REFERENCES student_leave_requests(id,organisation_id,school_id),
 FOREIGN KEY(actor_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
 CHECK(length(btrim(reason)) BETWEEN 3 AND 1000), CHECK(request_version>=0),
 CHECK(new_status IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN','REVOKED')),
 CHECK(previous_status IS NULL OR previous_status IN ('SUBMITTED','APPROVED'))
);

CREATE FUNCTION guard_student_leave_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actor_user UUID;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Student leave evidence cannot be deleted'; END IF;
 SELECT user_id INTO actor_user FROM memberships WHERE id=NEW.action_by_membership_id
   AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'SUBMITTED' OR NEW.row_version<>0
      OR NEW.action_by_membership_id<>NEW.requested_by_membership_id
      OR actor_user IS DISTINCT FROM NEW.requested_by_user_id
      OR NOT EXISTS(SELECT 1 FROM guardians WHERE id=NEW.guardian_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
        AND user_account_id=NEW.requested_by_user_id)
      OR NOT EXISTS(SELECT 1 FROM student_enrolments WHERE id=NEW.enrolment_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
        AND learner_id=NEW.learner_id AND campus_id=NEW.campus_id
        AND academic_session_id=NEW.academic_session_id)
      OR NOT EXISTS(SELECT 1 FROM student_leave_policies WHERE id=NEW.policy_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
        AND academic_session_id=NEW.academic_session_id) THEN
     RAISE EXCEPTION 'Student leave source identity is invalid';
   END IF;
 ELSE
   IF (to_jsonb(OLD)-ARRAY['status','action_by_membership_id','action_reason','updated_at','row_version'])
      IS DISTINCT FROM
      (to_jsonb(NEW)-ARRAY['status','action_by_membership_id','action_reason','updated_at','row_version'])
      OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at
      OR NOT ((OLD.status='SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED','WITHDRAWN'))
        OR (OLD.status='APPROVED' AND NEW.status IN ('WITHDRAWN','REVOKED'))) THEN
     RAISE EXCEPTION 'Student leave change violates controlled workflow';
   END IF;
   IF NEW.status='WITHDRAWN' AND actor_user IS DISTINCT FROM NEW.requested_by_user_id THEN
     RAISE EXCEPTION 'Only the requesting guardian can withdraw leave';
   END IF;
   IF NEW.status IN ('APPROVED','REJECTED','REVOKED') AND (
      actor_user IS NOT DISTINCT FROM NEW.requested_by_user_id OR EXISTS(
        SELECT 1 FROM guardians g JOIN learner_guardian_relationships relationship
          ON relationship.guardian_id=g.id AND relationship.organisation_id=g.organisation_id
          AND relationship.school_id=g.school_id
        WHERE g.user_account_id=actor_user AND relationship.learner_id=NEW.learner_id
          AND relationship.organisation_id=NEW.organisation_id AND relationship.school_id=NEW.school_id)) THEN
     RAISE EXCEPTION 'Student leave requires an independent decision maker';
   END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER student_leave_controlled_change BEFORE INSERT OR UPDATE OR DELETE ON student_leave_requests
FOR EACH ROW EXECUTE FUNCTION guard_student_leave_request();

CREATE FUNCTION record_student_leave_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   INSERT INTO student_leave_date_claims(organisation_id,school_id,learner_id,calendar_date,request_id)
   SELECT NEW.organisation_id,NEW.school_id,NEW.learner_id,NEW.starts_on+offset_day,NEW.id
   FROM generate_series(0,NEW.ends_on-NEW.starts_on) AS offset_day;
 ELSIF NEW.status IN ('REJECTED','WITHDRAWN','REVOKED') THEN
   DELETE FROM student_leave_date_claims WHERE request_id=NEW.id
     AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
 END IF;
 INSERT INTO student_leave_history(id,organisation_id,school_id,request_id,previous_status,new_status,
   actor_membership_id,reason,occurred_at,request_version)
 VALUES(gen_random_uuid(),NEW.organisation_id,NEW.school_id,NEW.id,
   CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.status END,NEW.status,
   NEW.action_by_membership_id,NEW.action_reason,NEW.updated_at,NEW.row_version);
 RETURN NEW;
END;
$$;
CREATE TRIGGER student_leave_record_change AFTER INSERT OR UPDATE ON student_leave_requests
FOR EACH ROW EXECUTE FUNCTION record_student_leave_change();

CREATE FUNCTION protect_student_leave_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Student leave history is append-only'; END;
$$;
CREATE TRIGGER student_leave_history_immutable BEFORE UPDATE OR DELETE ON student_leave_history
FOR EACH ROW EXECUTE FUNCTION protect_student_leave_history();

ALTER TABLE student_leave_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_leave_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY student_leave_policy_scope ON student_leave_policies
USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
ALTER TABLE student_leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_leave_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY student_leave_request_scope ON student_leave_requests
USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
ALTER TABLE student_leave_date_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_leave_date_claims FORCE ROW LEVEL SECURITY;
CREATE POLICY student_leave_claim_scope ON student_leave_date_claims
USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
ALTER TABLE student_leave_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_leave_history FORCE ROW LEVEL SECURITY;
CREATE POLICY student_leave_history_scope ON student_leave_history
USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
