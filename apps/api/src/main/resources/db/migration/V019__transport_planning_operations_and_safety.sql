INSERT INTO capabilities (code, description, risk_level) VALUES
    ('transport.read', 'Read school transport configuration and operational rosters within authorized scope', 'SENSITIVE'),
    ('transport.configure', 'Configure stops, routes, vehicles, duties, and learner transport assignments', 'RESTRICTED'),
    ('transport.operate', 'Create trips and record boarding, alighting, absence, and operational exceptions', 'RESTRICTED'),
    ('transport.safety', 'Review vehicle readiness evidence and restricted transport incidents', 'RESTRICTED'),
    ('transport.publish', 'Approve versioned route plans for family and operational use', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (
    id, organisation_id, code, display_name, description, system_role,
    created_at, updated_at, row_version
) VALUES
    ('00000000-0000-4000-8000-000000000009', NULL, 'transport_manager',
     'Transport manager',
     'Configures school transport and runs trips with restricted safety access; route publication remains separately controlled.',
     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('00000000-0000-4000-8000-000000000010', NULL, 'transport_operator',
     'Driver or transport operator',
     'Runs assigned transport trips and records roster events without configuration or broad learner access.',
     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (organisation_id, code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'transport.read'),
    ('00000000-0000-4000-8000-000000000001', 'transport.configure'),
    ('00000000-0000-4000-8000-000000000001', 'transport.operate'),
    ('00000000-0000-4000-8000-000000000001', 'transport.safety'),
    ('00000000-0000-4000-8000-000000000001', 'transport.publish'),
    ('00000000-0000-4000-8000-000000000007', 'transport.read'),
    ('00000000-0000-4000-8000-000000000007', 'transport.configure'),
    ('00000000-0000-4000-8000-000000000007', 'transport.operate'),
    ('00000000-0000-4000-8000-000000000007', 'transport.safety'),
    ('00000000-0000-4000-8000-000000000007', 'transport.publish'),
    ('00000000-0000-4000-8000-000000000009', 'transport.read'),
    ('00000000-0000-4000-8000-000000000009', 'transport.configure'),
    ('00000000-0000-4000-8000-000000000009', 'transport.operate'),
    ('00000000-0000-4000-8000-000000000009', 'transport.safety'),
    ('00000000-0000-4000-8000-000000000010', 'transport.read'),
    ('00000000-0000-4000-8000-000000000010', 'transport.operate')
ON CONFLICT DO NOTHING;

ALTER TABLE staff_designations
    ADD COLUMN eligible_as_transport_driver BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN eligible_as_transport_attendant BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT staff_designations_transport_driver_category_valid
        CHECK (NOT eligible_as_transport_driver OR staff_category = 'TRANSPORT');

CREATE OR REPLACE FUNCTION guard_staff_designation_usage()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.status IS DISTINCT FROM NEW.status
        OR OLD.staff_category IS DISTINCT FROM NEW.staff_category
        OR OLD.cbse_staff_category IS DISTINCT FROM NEW.cbse_staff_category
        OR OLD.teaching_role IS DISTINCT FROM NEW.teaching_role
        OR OLD.eligible_as_transport_driver IS DISTINCT FROM NEW.eligible_as_transport_driver
        OR OLD.eligible_as_transport_attendant IS DISTINCT FROM NEW.eligible_as_transport_attendant)
       AND EXISTS (SELECT 1 FROM staff_assignments assignment
                   WHERE assignment.staff_designation_id = OLD.id
                     AND assignment.status IN ('PLANNED','ACTIVE')) THEN
        RAISE EXCEPTION 'designation meaning is still used by an open staff assignment';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN staff_designations.eligible_as_transport_driver IS
    'School-controlled eligibility flag; category alone never proves driver authorization or licensing.';
COMMENT ON COLUMN staff_designations.eligible_as_transport_attendant IS
    'School-controlled eligibility flag for the responsible adult assigned to a learner transport duty.';

CREATE TABLE transport_stops (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(140) NOT NULL,
    family_label VARCHAR(140) NOT NULL,
    locality VARCHAR(180),
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    accessibility_note VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_stops_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_stops_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT transport_stops_campus_fk FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT transport_stops_creator_fk FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_stops_code_unique UNIQUE (school_id, code),
    CONSTRAINT transport_stops_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT transport_stops_coordinates_valid CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
    CONSTRAINT transport_stops_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE transport_routes (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(140) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_routes_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_routes_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT transport_routes_campus_fk FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT transport_routes_creator_fk FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_routes_code_unique UNIQUE (school_id, code),
    CONSTRAINT transport_routes_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT transport_routes_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE transport_route_revisions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_route_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_revision_id UUID,
    journey_kind VARCHAR(20) NOT NULL,
    service_label VARCHAR(140) NOT NULL,
    operating_days VARCHAR(40) NOT NULL,
    effective_from DATE NOT NULL,
    effective_until DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    published_at TIMESTAMPTZ,
    retired_by_membership_id UUID,
    retired_at TIMESTAMPTZ,
    retirement_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_route_revisions_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_route_revision_scope_unique
        UNIQUE (id, transport_route_id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_route_fk
        FOREIGN KEY (transport_route_id, organisation_id, school_id)
        REFERENCES transport_routes(id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_supersedes_fk
        FOREIGN KEY (supersedes_revision_id, organisation_id, school_id)
        REFERENCES transport_route_revisions(id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_retirer_fk
        FOREIGN KEY (retired_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_route_revisions_number_unique UNIQUE (transport_route_id, revision_number),
    CONSTRAINT transport_route_revisions_number_valid CHECK (revision_number BETWEEN 1 AND 1000),
    CONSTRAINT transport_route_revisions_journey_valid CHECK (journey_kind IN ('PICKUP','DROP')),
    CONSTRAINT transport_route_revisions_days_valid CHECK (
        operating_days ~ '^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*$'),
    CONSTRAINT transport_route_revisions_dates_valid CHECK (
        effective_until IS NULL OR effective_until >= effective_from),
    CONSTRAINT transport_route_revisions_status_valid CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','REJECTED','RETIRED')),
    CONSTRAINT transport_route_revisions_state_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND published_at IS NULL
            AND retired_by_membership_id IS NULL AND retired_at IS NULL AND retirement_reason IS NULL)
        OR (status = 'PENDING_APPROVAL' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL AND published_at IS NULL
            AND retired_by_membership_id IS NULL AND retired_at IS NULL AND retirement_reason IS NULL)
        OR (status = 'PUBLISHED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NOT NULL
            AND decided_at IS NOT NULL AND published_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id <> created_by_membership_id
            AND retired_by_membership_id IS NULL AND retired_at IS NULL AND retirement_reason IS NULL)
        OR (status = 'REJECTED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NOT NULL
            AND decided_at IS NOT NULL AND published_at IS NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id <> created_by_membership_id
            AND retired_by_membership_id IS NULL AND retired_at IS NULL AND retirement_reason IS NULL)
        OR (status = 'RETIRED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NOT NULL
            AND decided_at IS NOT NULL AND published_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id <> created_by_membership_id
            AND retired_by_membership_id IS NOT NULL AND retired_at IS NOT NULL
            AND length(btrim(retirement_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX transport_route_revisions_one_published_idx
    ON transport_route_revisions (transport_route_id, journey_kind)
    WHERE status = 'PUBLISHED';

CREATE TABLE transport_route_revision_stops (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_route_revision_id UUID NOT NULL,
    transport_stop_id UUID NOT NULL,
    sequence_number SMALLINT NOT NULL,
    scheduled_time TIME NOT NULL,
    boarding_window_minutes SMALLINT NOT NULL DEFAULT 5,
    stop_code_snapshot VARCHAR(40) NOT NULL,
    stop_name_snapshot VARCHAR(140) NOT NULL,
    family_label_snapshot VARCHAR(140) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_route_stops_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_route_stops_revision_scope_unique
        UNIQUE (id, transport_route_revision_id, organisation_id, school_id),
    CONSTRAINT transport_route_stops_revision_fk
        FOREIGN KEY (transport_route_revision_id, organisation_id, school_id)
        REFERENCES transport_route_revisions(id, organisation_id, school_id),
    CONSTRAINT transport_route_stops_stop_fk
        FOREIGN KEY (transport_stop_id, organisation_id, school_id)
        REFERENCES transport_stops(id, organisation_id, school_id),
    CONSTRAINT transport_route_stops_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_route_stops_sequence_unique
        UNIQUE (transport_route_revision_id, sequence_number),
    CONSTRAINT transport_route_stops_stop_unique
        UNIQUE (transport_route_revision_id, transport_stop_id),
    CONSTRAINT transport_route_stops_sequence_valid CHECK (sequence_number BETWEEN 1 AND 200),
    CONSTRAINT transport_route_stops_window_valid CHECK (boarding_window_minutes BETWEEN 0 AND 60)
);

CREATE TABLE transport_vehicles (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    vehicle_code VARCHAR(40) NOT NULL,
    display_label VARCHAR(120) NOT NULL,
    registration_number VARCHAR(30) NOT NULL,
    ownership_kind VARCHAR(20) NOT NULL,
    vendor_name VARCHAR(160),
    learner_capacity SMALLINT NOT NULL,
    wheelchair_accessible BOOLEAN NOT NULL DEFAULT FALSE,
    gps_provider_reference VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_vehicles_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_vehicles_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT transport_vehicles_campus_fk FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT transport_vehicles_creator_fk FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_vehicles_code_unique UNIQUE (school_id, vehicle_code),
    CONSTRAINT transport_vehicles_registration_unique UNIQUE (school_id, registration_number),
    CONSTRAINT transport_vehicles_code_valid CHECK (vehicle_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT transport_vehicles_ownership_valid CHECK (ownership_kind IN ('SCHOOL','CONTRACTED')),
    CONSTRAINT transport_vehicles_vendor_valid CHECK (
        (ownership_kind = 'SCHOOL' AND vendor_name IS NULL)
        OR (ownership_kind = 'CONTRACTED' AND length(btrim(vendor_name)) BETWEEN 2 AND 160)),
    CONSTRAINT transport_vehicles_capacity_valid CHECK (learner_capacity BETWEEN 1 AND 100),
    CONSTRAINT transport_vehicles_status_valid CHECK (status IN ('ACTIVE','MAINTENANCE','INACTIVE','RETIRED'))
);

CREATE TABLE transport_vehicle_compliance_types (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    display_name VARCHAR(140) NOT NULL,
    description VARCHAR(500),
    validity_months SMALLINT,
    required_before_service BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_vehicle_compliance_types_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_compliance_types_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT transport_vehicle_compliance_types_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_compliance_types_code_unique UNIQUE (school_id, code),
    CONSTRAINT transport_vehicle_compliance_types_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT transport_vehicle_compliance_types_validity_valid CHECK (
        validity_months IS NULL OR validity_months BETWEEN 1 AND 240),
    CONSTRAINT transport_vehicle_compliance_types_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE transport_vehicle_compliance_checks (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_vehicle_id UUID NOT NULL,
    compliance_type_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    completed_on DATE,
    valid_until DATE,
    safe_reference VARCHAR(160),
    restricted_note VARCHAR(1000),
    created_by_membership_id UUID NOT NULL,
    verified_by_membership_id UUID,
    verified_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_vehicle_checks_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_checks_vehicle_fk
        FOREIGN KEY (transport_vehicle_id, organisation_id, school_id)
        REFERENCES transport_vehicles(id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_checks_type_fk
        FOREIGN KEY (compliance_type_id, organisation_id, school_id)
        REFERENCES transport_vehicle_compliance_types(id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_checks_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_checks_verifier_fk
        FOREIGN KEY (verified_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_vehicle_checks_status_valid CHECK (
        status IN ('PENDING','VERIFIED','REJECTED')),
    CONSTRAINT transport_vehicle_checks_dates_valid CHECK (
        valid_until IS NULL OR (completed_on IS NOT NULL AND valid_until >= completed_on)),
    CONSTRAINT transport_vehicle_checks_decision_valid CHECK (
        (status = 'PENDING' AND verified_by_membership_id IS NULL AND verified_at IS NULL
            AND decision_reason IS NULL)
        OR (status IN ('VERIFIED','REJECTED') AND verified_by_membership_id IS NOT NULL
            AND verified_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND verified_by_membership_id <> created_by_membership_id))
);

CREATE INDEX transport_vehicle_checks_vehicle_idx
    ON transport_vehicle_compliance_checks
    (organisation_id, school_id, transport_vehicle_id, created_at DESC, id);

CREATE TABLE transport_duty_assignments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_route_revision_id UUID NOT NULL,
    transport_vehicle_id UUID NOT NULL,
    driver_staff_profile_id UUID NOT NULL,
    attendant_staff_profile_id UUID NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    ending_reason VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    ended_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_duties_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_duties_route_scope_unique
        UNIQUE (id, transport_route_revision_id, organisation_id, school_id),
    CONSTRAINT transport_duties_route_fk
        FOREIGN KEY (transport_route_revision_id, organisation_id, school_id)
        REFERENCES transport_route_revisions(id, organisation_id, school_id),
    CONSTRAINT transport_duties_vehicle_fk
        FOREIGN KEY (transport_vehicle_id, organisation_id, school_id)
        REFERENCES transport_vehicles(id, organisation_id, school_id),
    CONSTRAINT transport_duties_driver_fk
        FOREIGN KEY (driver_staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT transport_duties_attendant_fk
        FOREIGN KEY (attendant_staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT transport_duties_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_duties_ender_fk
        FOREIGN KEY (ended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_duties_people_distinct CHECK (driver_staff_profile_id <> attendant_staff_profile_id),
    CONSTRAINT transport_duties_dates_valid CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT transport_duties_status_valid CHECK (status IN ('PLANNED','ACTIVE','ENDED','CANCELLED')),
    CONSTRAINT transport_duties_lifecycle_valid CHECK (
        (status IN ('PLANNED','ACTIVE') AND ending_reason IS NULL AND ended_by_membership_id IS NULL)
        OR (status IN ('ENDED','CANCELLED') AND ends_on IS NOT NULL
            AND length(btrim(ending_reason)) BETWEEN 3 AND 500 AND ended_by_membership_id IS NOT NULL))
);

CREATE UNIQUE INDEX transport_duties_one_active_route_idx
    ON transport_duty_assignments (transport_route_revision_id)
    WHERE status = 'ACTIVE';

CREATE TABLE learner_transport_assignments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    transport_route_revision_id UUID NOT NULL,
    transport_route_stop_id UUID NOT NULL,
    journey_kind VARCHAR(20) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ending_reason VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    ended_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT learner_transport_assignments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_transport_assignments_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_transport_assignments_route_fk
        FOREIGN KEY (transport_route_revision_id, organisation_id, school_id)
        REFERENCES transport_route_revisions(id, organisation_id, school_id),
    CONSTRAINT learner_transport_assignments_stop_fk
        FOREIGN KEY (transport_route_stop_id, transport_route_revision_id, organisation_id, school_id)
        REFERENCES transport_route_revision_stops(id, transport_route_revision_id, organisation_id, school_id),
    CONSTRAINT learner_transport_assignments_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_transport_assignments_ender_fk
        FOREIGN KEY (ended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_transport_assignments_journey_valid CHECK (journey_kind IN ('PICKUP','DROP')),
    CONSTRAINT learner_transport_assignments_dates_valid CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT learner_transport_assignments_status_valid CHECK (status IN ('PLANNED','ACTIVE','ENDED','CANCELLED')),
    CONSTRAINT learner_transport_assignments_lifecycle_valid CHECK (
        (status IN ('PLANNED','ACTIVE') AND ending_reason IS NULL AND ended_by_membership_id IS NULL)
        OR (status IN ('ENDED','CANCELLED') AND ends_on IS NOT NULL
            AND length(btrim(ending_reason)) BETWEEN 3 AND 500 AND ended_by_membership_id IS NOT NULL))
);

CREATE UNIQUE INDEX learner_transport_assignments_one_active_journey_idx
    ON learner_transport_assignments (learner_id, journey_kind)
    WHERE status = 'ACTIVE';

CREATE TABLE transport_trips (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_route_revision_id UUID NOT NULL,
    transport_duty_assignment_id UUID NOT NULL,
    service_date DATE NOT NULL,
    journey_kind VARCHAR(20) NOT NULL,
    route_code_snapshot VARCHAR(40) NOT NULL,
    route_name_snapshot VARCHAR(140) NOT NULL,
    service_label_snapshot VARCHAR(140) NOT NULL,
    vehicle_label_snapshot VARCHAR(120) NOT NULL,
    vehicle_registration_snapshot VARCHAR(30) NOT NULL,
    driver_name_snapshot VARCHAR(120) NOT NULL,
    attendant_name_snapshot VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    boarding_opened_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    updated_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_trips_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_trips_route_fk
        FOREIGN KEY (transport_route_revision_id, organisation_id, school_id)
        REFERENCES transport_route_revisions(id, organisation_id, school_id),
    CONSTRAINT transport_trips_duty_fk
        FOREIGN KEY (transport_duty_assignment_id, transport_route_revision_id, organisation_id, school_id)
        REFERENCES transport_duty_assignments(id, transport_route_revision_id, organisation_id, school_id),
    CONSTRAINT transport_trips_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_trips_updater_fk
        FOREIGN KEY (updated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_trips_unique_service UNIQUE (transport_route_revision_id, service_date),
    CONSTRAINT transport_trips_journey_valid CHECK (journey_kind IN ('PICKUP','DROP')),
    CONSTRAINT transport_trips_status_valid CHECK (
        status IN ('PLANNED','BOARDING','IN_PROGRESS','COMPLETED','CANCELLED')),
    CONSTRAINT transport_trips_state_valid CHECK (
        (status = 'PLANNED' AND boarding_opened_at IS NULL AND started_at IS NULL
            AND completed_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'BOARDING' AND boarding_opened_at IS NOT NULL AND started_at IS NULL
            AND completed_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'IN_PROGRESS' AND boarding_opened_at IS NOT NULL AND started_at IS NOT NULL
            AND started_at >= boarding_opened_at
            AND completed_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'COMPLETED' AND boarding_opened_at IS NOT NULL
            AND started_at IS NOT NULL AND started_at >= boarding_opened_at
            AND completed_at IS NOT NULL AND completed_at >= started_at
            AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND completed_at IS NOT NULL
            AND (started_at IS NULL OR (boarding_opened_at IS NOT NULL
                AND started_at >= boarding_opened_at))
            AND completed_at >= COALESCE(started_at, boarding_opened_at, completed_at)
            AND length(btrim(cancellation_reason)) BETWEEN 3 AND 500))
);

CREATE TABLE transport_trip_roster_entries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_trip_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    learner_transport_assignment_id UUID NOT NULL,
    transport_route_stop_id UUID NOT NULL,
    learner_name_snapshot VARCHAR(160) NOT NULL,
    learner_reference_snapshot VARCHAR(40) NOT NULL,
    stop_sequence_snapshot SMALLINT NOT NULL,
    stop_family_label_snapshot VARCHAR(140) NOT NULL,
    scheduled_time_snapshot TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT transport_trip_roster_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_trip_roster_trip_scope_unique
        UNIQUE (id, transport_trip_id, organisation_id, school_id),
    CONSTRAINT transport_trip_roster_trip_fk
        FOREIGN KEY (transport_trip_id, organisation_id, school_id)
        REFERENCES transport_trips(id, organisation_id, school_id),
    CONSTRAINT transport_trip_roster_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT transport_trip_roster_assignment_fk
        FOREIGN KEY (learner_transport_assignment_id, organisation_id, school_id)
        REFERENCES learner_transport_assignments(id, organisation_id, school_id),
    CONSTRAINT transport_trip_roster_stop_fk
        FOREIGN KEY (transport_route_stop_id, organisation_id, school_id)
        REFERENCES transport_route_revision_stops(id, organisation_id, school_id),
    CONSTRAINT transport_trip_roster_unique_learner UNIQUE (transport_trip_id, learner_id),
    CONSTRAINT transport_trip_roster_sequence_valid CHECK (stop_sequence_snapshot BETWEEN 1 AND 200)
);

CREATE TABLE transport_trip_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_trip_id UUID NOT NULL,
    roster_entry_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    safe_note VARCHAR(500),
    actor_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT transport_trip_events_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_trip_events_trip_fk
        FOREIGN KEY (transport_trip_id, organisation_id, school_id)
        REFERENCES transport_trips(id, organisation_id, school_id),
    CONSTRAINT transport_trip_events_roster_fk
        FOREIGN KEY (roster_entry_id, transport_trip_id, organisation_id, school_id)
        REFERENCES transport_trip_roster_entries(id, transport_trip_id, organisation_id, school_id),
    CONSTRAINT transport_trip_events_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_trip_events_type_valid CHECK (event_type IN ('BOARDED','ALIGHTED','ABSENT','EXCEPTION'))
);

CREATE UNIQUE INDEX transport_trip_events_one_lifecycle_event_idx
    ON transport_trip_events (roster_entry_id, event_type)
    WHERE event_type IN ('BOARDED','ALIGHTED','ABSENT');

CREATE TABLE transport_incidents (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    transport_trip_id UUID,
    learner_id UUID,
    category VARCHAR(30) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    restricted_details VARCHAR(4000) NOT NULL,
    immediate_action VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    reported_by_membership_id UUID NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL,
    resolved_by_membership_id UUID,
    resolved_at TIMESTAMPTZ,
    resolution_summary VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT transport_incidents_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT transport_incidents_trip_fk
        FOREIGN KEY (transport_trip_id, organisation_id, school_id)
        REFERENCES transport_trips(id, organisation_id, school_id),
    CONSTRAINT transport_incidents_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT transport_incidents_reporter_fk
        FOREIGN KEY (reported_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_incidents_resolver_fk
        FOREIGN KEY (resolved_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT transport_incidents_category_valid CHECK (
        category IN ('DELAY','VEHICLE','ROUTE','BEHAVIOUR','HANDOVER','MEDICAL','ACCIDENT','OTHER')),
    CONSTRAINT transport_incidents_severity_valid CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT transport_incidents_status_valid CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT transport_incidents_state_valid CHECK (
        (status = 'OPEN' AND resolved_by_membership_id IS NULL AND resolved_at IS NULL
            AND resolution_summary IS NULL)
        OR (status = 'RESOLVED' AND resolved_by_membership_id IS NOT NULL
            AND resolved_by_membership_id <> reported_by_membership_id
            AND resolved_at IS NOT NULL AND length(btrim(resolution_summary)) BETWEEN 3 AND 2000))
);

CREATE TABLE transport_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    subject_type VARCHAR(40) NOT NULL,
    subject_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30),
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT transport_history_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT transport_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id)
);

CREATE INDEX transport_history_subject_idx
    ON transport_history (organisation_id, school_id, subject_type, subject_id, occurred_at DESC, id);

CREATE OR REPLACE FUNCTION guard_transport_route_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND ROW(
        NEW.transport_route_id, NEW.revision_number, NEW.supersedes_revision_id,
        NEW.journey_kind, NEW.service_label, NEW.operating_days,
        NEW.effective_from, NEW.effective_until
    ) IS DISTINCT FROM ROW(
        OLD.transport_route_id, OLD.revision_number, OLD.supersedes_revision_id,
        OLD.journey_kind, OLD.service_label, OLD.operating_days,
        OLD.effective_from, OLD.effective_until
    ) THEN
        RAISE EXCEPTION 'submitted or published route content is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT','PENDING_APPROVAL') THEN
        RAISE EXCEPTION 'route draft must be submitted before decision';
    ELSIF OLD.status = 'PENDING_APPROVAL' AND NEW.status NOT IN ('PUBLISHED','REJECTED') THEN
        RAISE EXCEPTION 'pending route must be published or rejected';
    ELSIF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED','RETIRED') THEN
        RAISE EXCEPTION 'published route can only be retired';
    ELSIF OLD.status IN ('REJECTED','RETIRED') THEN
        RAISE EXCEPTION 'terminal route revisions are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transport_route_revisions_guard
BEFORE UPDATE ON transport_route_revisions
FOR EACH ROW EXECUTE FUNCTION guard_transport_route_revision();

CREATE OR REPLACE FUNCTION guard_transport_route_stop()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE revision_status VARCHAR(30);
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO revision_status FROM transport_route_revisions
        WHERE id = OLD.transport_route_revision_id;
    ELSE
        SELECT status INTO revision_status FROM transport_route_revisions
        WHERE id = NEW.transport_route_revision_id;
    END IF;
    IF revision_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'route stops are editable only while the revision is draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transport_route_stops_guard
BEFORE INSERT OR UPDATE OR DELETE ON transport_route_revision_stops
FOR EACH ROW EXECUTE FUNCTION guard_transport_route_stop();

CREATE OR REPLACE FUNCTION guard_transport_vehicle_check()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'decided vehicle readiness evidence is immutable';
    END IF;
    IF NEW.status NOT IN ('VERIFIED','REJECTED') THEN
        RAISE EXCEPTION 'vehicle readiness decision is invalid';
    END IF;
    IF NEW.verified_by_membership_id = OLD.created_by_membership_id THEN
        RAISE EXCEPTION 'vehicle readiness evidence requires independent review';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transport_vehicle_checks_guard
BEFORE UPDATE ON transport_vehicle_compliance_checks
FOR EACH ROW EXECUTE FUNCTION guard_transport_vehicle_check();

CREATE OR REPLACE FUNCTION guard_transport_duty()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('ENDED','CANCELLED') THEN
        RAISE EXCEPTION 'ended transport duties are immutable';
    END IF;
    IF ROW(NEW.transport_route_revision_id,NEW.transport_vehicle_id,
        NEW.driver_staff_profile_id,NEW.attendant_staff_profile_id,NEW.starts_on)
       IS DISTINCT FROM ROW(OLD.transport_route_revision_id,OLD.transport_vehicle_id,
        OLD.driver_staff_profile_id,OLD.attendant_staff_profile_id,OLD.starts_on) THEN
        RAISE EXCEPTION 'transport duty identity is immutable; end and replace it';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transport_duties_guard
BEFORE UPDATE ON transport_duty_assignments
FOR EACH ROW EXECUTE FUNCTION guard_transport_duty();

CREATE OR REPLACE FUNCTION guard_learner_transport_assignment()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('ENDED','CANCELLED') THEN
        RAISE EXCEPTION 'ended learner transport assignments are immutable';
    END IF;
    IF ROW(NEW.learner_id,NEW.transport_route_revision_id,NEW.transport_route_stop_id,
        NEW.journey_kind,NEW.starts_on)
       IS DISTINCT FROM ROW(OLD.learner_id,OLD.transport_route_revision_id,OLD.transport_route_stop_id,
        OLD.journey_kind,OLD.starts_on) THEN
        RAISE EXCEPTION 'learner transport assignment identity is immutable; end and replace it';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER learner_transport_assignments_guard
BEFORE UPDATE ON learner_transport_assignments
FOR EACH ROW EXECUTE FUNCTION guard_learner_transport_assignment();

CREATE OR REPLACE FUNCTION guard_transport_trip()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.transport_route_revision_id,NEW.transport_duty_assignment_id,NEW.service_date,
        NEW.journey_kind,NEW.route_code_snapshot,NEW.route_name_snapshot,NEW.service_label_snapshot,
        NEW.vehicle_label_snapshot,NEW.vehicle_registration_snapshot,
        NEW.driver_name_snapshot,NEW.attendant_name_snapshot)
       IS DISTINCT FROM ROW(OLD.transport_route_revision_id,OLD.transport_duty_assignment_id,OLD.service_date,
        OLD.journey_kind,OLD.route_code_snapshot,OLD.route_name_snapshot,OLD.service_label_snapshot,
        OLD.vehicle_label_snapshot,OLD.vehicle_registration_snapshot,
        OLD.driver_name_snapshot,OLD.attendant_name_snapshot) THEN
        RAISE EXCEPTION 'trip identity and safety snapshots are immutable';
    END IF;
    IF OLD.status = 'PLANNED' AND NEW.status NOT IN ('BOARDING','CANCELLED') THEN
        RAISE EXCEPTION 'planned trip must enter boarding or be cancelled';
    ELSIF OLD.status = 'BOARDING' AND NEW.status NOT IN ('IN_PROGRESS','CANCELLED') THEN
        RAISE EXCEPTION 'boarding trip must start or be cancelled';
    ELSIF OLD.status = 'IN_PROGRESS' AND NEW.status NOT IN ('COMPLETED','CANCELLED') THEN
        RAISE EXCEPTION 'active trip must complete or be cancelled';
    ELSIF OLD.status IN ('COMPLETED','CANCELLED') THEN
        RAISE EXCEPTION 'completed or cancelled trips are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transport_trips_guard
BEFORE UPDATE ON transport_trips
FOR EACH ROW EXECUTE FUNCTION guard_transport_trip();

CREATE OR REPLACE FUNCTION guard_transport_roster_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'frozen transport roster entries are append-only';
END;
$$;

CREATE TRIGGER transport_trip_roster_append_only
BEFORE UPDATE OR DELETE ON transport_trip_roster_entries
FOR EACH ROW EXECUTE FUNCTION guard_transport_roster_append_only();

CREATE OR REPLACE FUNCTION guard_transport_event_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'transport trip events are append-only';
END;
$$;

CREATE TRIGGER transport_trip_events_append_only
BEFORE UPDATE OR DELETE ON transport_trip_events
FOR EACH ROW EXECUTE FUNCTION guard_transport_event_append_only();

CREATE OR REPLACE FUNCTION guard_transport_incident()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'resolved transport incidents are immutable';
    END IF;
    IF ROW(NEW.transport_trip_id,NEW.learner_id,NEW.category,NEW.severity,
        NEW.summary,NEW.restricted_details,NEW.immediate_action,NEW.reported_by_membership_id,NEW.reported_at)
       IS DISTINCT FROM ROW(OLD.transport_trip_id,OLD.learner_id,OLD.category,OLD.severity,
        OLD.summary,OLD.restricted_details,OLD.immediate_action,OLD.reported_by_membership_id,OLD.reported_at) THEN
        RAISE EXCEPTION 'reported incident evidence is immutable; resolve with a separate summary';
    END IF;
    IF NEW.status <> 'RESOLVED' THEN
        RAISE EXCEPTION 'open incident can only be resolved';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transport_incidents_guard
BEFORE UPDATE ON transport_incidents
FOR EACH ROW EXECUTE FUNCTION guard_transport_incident();

CREATE OR REPLACE FUNCTION guard_transport_history_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'transport history is append-only';
END;
$$;

CREATE TRIGGER transport_history_append_only
BEFORE UPDATE OR DELETE ON transport_history
FOR EACH ROW EXECUTE FUNCTION guard_transport_history_append_only();

ALTER TABLE transport_stops ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_routes ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_route_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_route_revision_stops ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicle_compliance_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicle_compliance_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_duty_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_transport_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_trip_roster_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_trip_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY transport_stops_tenant_select ON transport_stops FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_stops_tenant_insert ON transport_stops FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_stops_tenant_update ON transport_stops FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_routes_tenant_select ON transport_routes FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_routes_tenant_insert ON transport_routes FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_routes_tenant_update ON transport_routes FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_route_revisions_tenant_select ON transport_route_revisions FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_route_revisions_tenant_insert ON transport_route_revisions FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_route_revisions_tenant_update ON transport_route_revisions FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_route_stops_tenant_select ON transport_route_revision_stops FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_route_stops_tenant_insert ON transport_route_revision_stops FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_route_stops_tenant_update ON transport_route_revision_stops FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_route_stops_tenant_delete ON transport_route_revision_stops FOR DELETE
    USING (organisation_id = current_app_organisation_id());

CREATE POLICY transport_vehicles_tenant_select ON transport_vehicles FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_vehicles_tenant_insert ON transport_vehicles FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_vehicles_tenant_update ON transport_vehicles FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_vehicle_types_tenant_select ON transport_vehicle_compliance_types FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_vehicle_types_tenant_insert ON transport_vehicle_compliance_types FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_vehicle_types_tenant_update ON transport_vehicle_compliance_types FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_vehicle_checks_tenant_select ON transport_vehicle_compliance_checks FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_vehicle_checks_tenant_insert ON transport_vehicle_compliance_checks FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_vehicle_checks_tenant_update ON transport_vehicle_compliance_checks FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_duties_tenant_select ON transport_duty_assignments FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_duties_tenant_insert ON transport_duty_assignments FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_duties_tenant_update ON transport_duty_assignments FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY learner_transport_assignments_tenant_select ON learner_transport_assignments FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY learner_transport_assignments_tenant_insert ON learner_transport_assignments FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_transport_assignments_tenant_update ON learner_transport_assignments FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_trips_tenant_select ON transport_trips FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_trips_tenant_insert ON transport_trips FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_trips_tenant_update ON transport_trips FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_trip_roster_tenant_select ON transport_trip_roster_entries FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_trip_roster_tenant_insert ON transport_trip_roster_entries FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_trip_events_tenant_select ON transport_trip_events FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_trip_events_tenant_insert ON transport_trip_events FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_incidents_tenant_select ON transport_incidents FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_incidents_tenant_insert ON transport_incidents FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY transport_incidents_tenant_update ON transport_incidents FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY transport_history_tenant_select ON transport_history FOR SELECT
    USING (organisation_id = current_app_organisation_id());
CREATE POLICY transport_history_tenant_insert ON transport_history FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON COLUMN transport_stops.latitude IS
    'Restricted operational coordinate. Family responses use the reviewed family_label snapshot and never expose coordinates.';
COMMENT ON COLUMN transport_vehicles.gps_provider_reference IS
    'Optional external provider reference only. It is not a location feed and is never returned to families.';
COMMENT ON COLUMN transport_vehicles.learner_capacity IS
    'Maximum learner seats after reserving required driver, attendant, accessibility, and other non-learner seating.';
COMMENT ON TABLE transport_trip_roster_entries IS
    'Frozen per-trip learner roster. Later route or learner assignment changes must not rewrite operated-trip evidence.';
COMMENT ON TABLE transport_trip_events IS
    'Append-only boarding, alighting, absence, and exception evidence recorded by an authorized school membership.';
COMMENT ON TABLE transport_incidents IS
    'Restricted transport safety record. Family notification belongs in the reviewed communication workflow, not this table.';
