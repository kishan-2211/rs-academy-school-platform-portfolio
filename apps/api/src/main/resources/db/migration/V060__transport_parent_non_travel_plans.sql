-- Parent plans are separate from immutable trip rosters and physical trip events.
-- Policies and changes append revisions; no existing transport record is rewritten.
CREATE TABLE transport_non_travel_policy_revisions (
  id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, campus_id UUID NOT NULL,
  revision BIGINT NOT NULL CHECK(revision>=0), enabled BOOLEAN NOT NULL,
  pickup_closes_at TIME, drop_closes_at TIME,
  reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500),
  actor_membership_id UUID NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
  UNIQUE(id,organisation_id,school_id), UNIQUE(campus_id,revision),
  FOREIGN KEY(campus_id,organisation_id,school_id) REFERENCES campuses(id,organisation_id,school_id),
  FOREIGN KEY(actor_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
  CHECK(NOT enabled OR (pickup_closes_at IS NOT NULL AND drop_closes_at IS NOT NULL))
);
CREATE TABLE transport_non_travel_changes (
  id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, campus_id UUID NOT NULL,
  learner_id UUID NOT NULL, service_date DATE NOT NULL, journeys VARCHAR(20) NOT NULL,
  not_travelling BOOLEAN NOT NULL, client_request_id UUID NOT NULL,
  actor_user_id UUID NOT NULL REFERENCES user_accounts(id), actor_membership_id UUID NOT NULL,
  policy_revision_id UUID NOT NULL, pickup_assignment_id UUID, drop_assignment_id UUID,
  previous_pickup_revision_id UUID, previous_drop_revision_id UUID,
  pickup_closes_at TIMESTAMPTZ, drop_closes_at TIMESTAMPTZ,
  request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
  occurred_at TIMESTAMPTZ NOT NULL,
  UNIQUE(id,organisation_id,school_id), UNIQUE(school_id,actor_user_id,client_request_id),
  FOREIGN KEY(campus_id,organisation_id,school_id) REFERENCES campuses(id,organisation_id,school_id),
  FOREIGN KEY(learner_id,organisation_id,school_id) REFERENCES learners(id,organisation_id,school_id),
  FOREIGN KEY(actor_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
  FOREIGN KEY(policy_revision_id,organisation_id,school_id) REFERENCES transport_non_travel_policy_revisions(id,organisation_id,school_id),
  FOREIGN KEY(pickup_assignment_id,organisation_id,school_id) REFERENCES learner_transport_assignments(id,organisation_id,school_id),
  FOREIGN KEY(drop_assignment_id,organisation_id,school_id) REFERENCES learner_transport_assignments(id,organisation_id,school_id),
  FOREIGN KEY(previous_pickup_revision_id,organisation_id,school_id) REFERENCES transport_non_travel_changes(id,organisation_id,school_id),
  FOREIGN KEY(previous_drop_revision_id,organisation_id,school_id) REFERENCES transport_non_travel_changes(id,organisation_id,school_id),
  CHECK(journeys IN('PICKUP','DROP','FULL_DAY')),
  CHECK((journeys IN('PICKUP','FULL_DAY') AND pickup_assignment_id IS NOT NULL AND pickup_closes_at IS NOT NULL)
    OR (journeys='DROP' AND pickup_assignment_id IS NULL AND previous_pickup_revision_id IS NULL AND pickup_closes_at IS NULL)),
  CHECK((journeys IN('DROP','FULL_DAY') AND drop_assignment_id IS NOT NULL AND drop_closes_at IS NOT NULL)
    OR (journeys='PICKUP' AND drop_assignment_id IS NULL AND previous_drop_revision_id IS NULL AND drop_closes_at IS NULL))
);
CREATE INDEX transport_non_travel_history_idx ON transport_non_travel_changes(school_id,learner_id,service_date DESC,occurred_at DESC,id DESC);
CREATE INDEX transport_non_travel_pickup_idx ON transport_non_travel_changes(school_id,learner_id,service_date,occurred_at DESC,id DESC) WHERE pickup_assignment_id IS NOT NULL;
CREATE INDEX transport_non_travel_drop_idx ON transport_non_travel_changes(school_id,learner_id,service_date,occurred_at DESC,id DESC) WHERE drop_assignment_id IS NOT NULL;

CREATE FUNCTION transport_non_travel_actor_allowed(actor UUID, account_id UUID, org UUID, school UUID, capability TEXT)
RETURNS BOOLEAN LANGUAGE sql AS $$
SELECT EXISTS(SELECT 1 FROM memberships member
 JOIN user_accounts account ON account.id=member.user_id AND account.status='ACTIVE'
 JOIN schools school_record ON school_record.id=member.school_id AND school_record.organisation_id=member.organisation_id AND school_record.status='ACTIVE'
 JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
 JOIN membership_roles mr ON mr.membership_id=member.id JOIN roles role ON role.id=mr.role_id
 JOIN role_capabilities rc ON rc.role_id=role.id
 WHERE member.id=actor AND member.user_id=account_id AND member.organisation_id=org AND member.school_id=school
   AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
   AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
   AND (role.organisation_id IS NULL OR role.organisation_id=org) AND rc.capability_code=capability);
$$;
CREATE FUNCTION guard_transport_non_travel_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'transport non-travel records are append-only'; END;
$$;
CREATE TRIGGER transport_non_travel_policy_immutable BEFORE UPDATE OR DELETE ON transport_non_travel_policy_revisions
FOR EACH ROW EXECUTE FUNCTION guard_transport_non_travel_append_only();
CREATE TRIGGER transport_non_travel_change_immutable BEFORE UPDATE OR DELETE ON transport_non_travel_changes
FOR EACH ROW EXECUTE FUNCTION guard_transport_non_travel_append_only();

CREATE FUNCTION guard_transport_non_travel_policy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior BIGINT;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('transport-non-travel-policy:'||NEW.campus_id,0));
 SELECT max(revision) INTO prior FROM transport_non_travel_policy_revisions WHERE campus_id=NEW.campus_id;
 IF NEW.revision<>coalesce(prior,-1)+1 OR NOT transport_non_travel_actor_allowed(NEW.actor_membership_id,
   (SELECT user_id FROM memberships WHERE id=NEW.actor_membership_id),NEW.organisation_id,NEW.school_id,'transport.configure')
   OR NOT EXISTS(SELECT 1 FROM campuses WHERE id=NEW.campus_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND status='ACTIVE')
 THEN RAISE EXCEPTION 'transport non-travel policy authority or revision is invalid'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER transport_non_travel_policy_guard BEFORE INSERT ON transport_non_travel_policy_revisions
FOR EACH ROW EXECUTE FUNCTION guard_transport_non_travel_policy();

CREATE FUNCTION guard_transport_non_travel_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE policy transport_non_travel_policy_revisions%ROWTYPE;
  zone TEXT; kind TEXT; assignment_id UUID; prior_id UUID; expected_id UUID; cutoff TIMESTAMPTZ;
  supplied_cutoff TIMESTAMPTZ; revision_id UUID; start_time TIME; policy_time TIME;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('transport-non-travel-policy:'||NEW.campus_id,0));
 PERFORM pg_advisory_xact_lock(hashtextextended('transport-non-travel-day:'||NEW.school_id||':'||NEW.learner_id||':'||NEW.service_date,0));
 SELECT * INTO policy FROM transport_non_travel_policy_revisions WHERE campus_id=NEW.campus_id ORDER BY revision DESC LIMIT 1;
 SELECT time_zone INTO zone FROM campuses WHERE id=NEW.campus_id AND status='ACTIVE';
 IF policy.id IS DISTINCT FROM NEW.policy_revision_id OR NOT coalesce(policy.enabled,false)
   OR policy.organisation_id<>NEW.organisation_id OR policy.school_id<>NEW.school_id OR zone IS NULL
   OR NEW.service_date<>(NEW.occurred_at AT TIME ZONE zone)::date
   OR NOT transport_non_travel_actor_allowed(NEW.actor_membership_id,NEW.actor_user_id,NEW.organisation_id,NEW.school_id,'portal.guardian')
   OR NOT EXISTS(SELECT 1 FROM guardians guardian
      JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
        AND relationship.organisation_id=guardian.organisation_id AND relationship.school_id=guardian.school_id
        AND relationship.portal_access_status='ACTIVE'
      JOIN learners learner ON learner.id=relationship.learner_id AND learner.organisation_id=relationship.organisation_id
        AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
      JOIN student_enrolments enrolment ON enrolment.learner_id=learner.id AND enrolment.organisation_id=learner.organisation_id
        AND enrolment.school_id=learner.school_id AND enrolment.campus_id=NEW.campus_id AND enrolment.status='ACTIVE'
        AND enrolment.starts_on<=NEW.service_date AND (enrolment.ends_on IS NULL OR enrolment.ends_on>=NEW.service_date)
      WHERE guardian.user_account_id=NEW.actor_user_id AND guardian.organisation_id=NEW.organisation_id
        AND guardian.school_id=NEW.school_id AND guardian.status='ACTIVE' AND learner.id=NEW.learner_id)
 THEN RAISE EXCEPTION 'transport non-travel family or policy authority is unavailable'; END IF;
 FOR kind IN SELECT unnest(CASE NEW.journeys WHEN 'FULL_DAY' THEN ARRAY['PICKUP','DROP'] ELSE ARRAY[NEW.journeys] END) LOOP
   assignment_id:=CASE kind WHEN 'PICKUP' THEN NEW.pickup_assignment_id ELSE NEW.drop_assignment_id END;
   expected_id:=CASE kind WHEN 'PICKUP' THEN NEW.previous_pickup_revision_id ELSE NEW.previous_drop_revision_id END;
   supplied_cutoff:=CASE kind WHEN 'PICKUP' THEN NEW.pickup_closes_at ELSE NEW.drop_closes_at END;
   policy_time:=CASE kind WHEN 'PICKUP' THEN policy.pickup_closes_at ELSE policy.drop_closes_at END;
   prior_id:=NULL;
   SELECT id INTO prior_id FROM transport_non_travel_changes
    WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND learner_id=NEW.learner_id AND service_date=NEW.service_date
      AND ((kind='PICKUP' AND pickup_assignment_id IS NOT NULL) OR (kind='DROP' AND drop_assignment_id IS NOT NULL))
    ORDER BY occurred_at DESC,id DESC LIMIT 1;
   IF prior_id IS DISTINCT FROM expected_id THEN RAISE EXCEPTION 'transport non-travel plan changed'; END IF;
   IF prior_id IS NOT NULL AND EXISTS(SELECT 1 FROM transport_non_travel_changes WHERE id=prior_id AND occurred_at>=NEW.occurred_at)
    THEN RAISE EXCEPTION 'transport non-travel revision time must increase'; END IF;
   IF NOT NEW.not_travelling AND (prior_id IS NULL OR NOT (SELECT not_travelling FROM transport_non_travel_changes WHERE id=prior_id))
    THEN RAISE EXCEPTION 'transport non-travel cancellation requires a current plan'; END IF;
   revision_id:=NULL;start_time:=NULL;
   SELECT revision.id,min(stop.scheduled_time) INTO revision_id,start_time
    FROM learner_transport_assignments assignment
    JOIN transport_route_revisions revision ON revision.id=assignment.transport_route_revision_id
      AND revision.organisation_id=assignment.organisation_id AND revision.school_id=assignment.school_id
    JOIN transport_routes route ON route.id=revision.transport_route_id AND route.organisation_id=revision.organisation_id
      AND route.school_id=revision.school_id AND route.status='ACTIVE' AND route.campus_id=NEW.campus_id
    JOIN transport_route_revision_stops stop ON stop.transport_route_revision_id=revision.id
      AND stop.organisation_id=revision.organisation_id AND stop.school_id=revision.school_id
    WHERE assignment.id=assignment_id AND assignment.organisation_id=NEW.organisation_id AND assignment.school_id=NEW.school_id
      AND assignment.learner_id=NEW.learner_id AND assignment.journey_kind=kind AND assignment.status='ACTIVE'
      AND assignment.starts_on<=NEW.service_date AND (assignment.ends_on IS NULL OR assignment.ends_on>=NEW.service_date)
      AND revision.journey_kind=kind AND revision.status IN('PUBLISHED','RETIRED') AND revision.effective_from<=NEW.service_date
      AND (revision.effective_until IS NULL OR revision.effective_until>=NEW.service_date)
      AND (ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN'])[extract(isodow FROM NEW.service_date)::int]=ANY(string_to_array(revision.operating_days,','))
    GROUP BY revision.id;
   IF revision_id IS NULL OR start_time IS NULL THEN RAISE EXCEPTION 'transport non-travel journey is unavailable'; END IF;
   PERFORM pg_advisory_xact_lock(hashtextextended('transport-trip:'||NEW.school_id||':'||revision_id||':'||NEW.service_date,0));
   PERFORM 1 FROM transport_trips WHERE transport_route_revision_id=revision_id AND service_date=NEW.service_date FOR SHARE;
   cutoff:=least((NEW.service_date+policy_time) AT TIME ZONE zone,(NEW.service_date+start_time) AT TIME ZONE zone);
   IF supplied_cutoff IS DISTINCT FROM cutoff OR NEW.occurred_at>=cutoff
     OR EXISTS(SELECT 1 FROM transport_trips WHERE transport_route_revision_id=revision_id AND service_date=NEW.service_date AND status<>'PLANNED')
     OR (prior_id IS NOT NULL AND NEW.occurred_at>=coalesce((SELECT CASE kind WHEN 'PICKUP' THEN pickup_closes_at ELSE drop_closes_at END FROM transport_non_travel_changes WHERE id=prior_id),cutoff))
    THEN RAISE EXCEPTION 'transport non-travel changes are closed'; END IF;
 END LOOP;
 RETURN NEW;
END;
$$;
CREATE TRIGGER transport_non_travel_change_guard BEFORE INSERT ON transport_non_travel_changes
FOR EACH ROW EXECUTE FUNCTION guard_transport_non_travel_change();

ALTER TABLE transport_non_travel_policy_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_non_travel_changes ENABLE ROW LEVEL SECURITY;
CREATE POLICY transport_non_travel_policy_select ON transport_non_travel_policy_revisions FOR SELECT USING(organisation_id=current_app_organisation_id());
CREATE POLICY transport_non_travel_policy_insert ON transport_non_travel_policy_revisions FOR INSERT WITH CHECK(organisation_id=current_app_organisation_id());
CREATE POLICY transport_non_travel_change_select ON transport_non_travel_changes FOR SELECT USING(organisation_id=current_app_organisation_id());
CREATE POLICY transport_non_travel_change_insert ON transport_non_travel_changes FOR INSERT WITH CHECK(organisation_id=current_app_organisation_id());
