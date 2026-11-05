ALTER TABLE schools
    ADD CONSTRAINT schools_id_organisation_unique UNIQUE (id, organisation_id);

ALTER TABLE memberships
    ADD CONSTRAINT memberships_id_organisation_school_unique UNIQUE (id, organisation_id, school_id);

ALTER TABLE public_enquiries
    ADD COLUMN organisation_id UUID,
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'WEBSITE',
    ADD COLUMN follow_up_at TIMESTAMPTZ,
    ADD COLUMN last_contacted_at TIMESTAMPTZ,
    ADD COLUMN closed_at TIMESTAMPTZ;

UPDATE public_enquiries AS enquiry
SET organisation_id = school.organisation_id
FROM schools AS school
WHERE school.id = enquiry.school_id;

UPDATE public_enquiries
SET closed_at = updated_at
WHERE status = 'CLOSED' AND closed_at IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public_enquiries
        WHERE school_id IS NULL OR organisation_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Every existing public enquiry must reference a configured school before V006 can be applied';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public_enquiries AS enquiry
        JOIN memberships AS assignee ON assignee.id = enquiry.assigned_membership_id
        WHERE assignee.organisation_id <> enquiry.organisation_id
           OR assignee.school_id IS DISTINCT FROM enquiry.school_id
    ) THEN
        RAISE EXCEPTION 'Existing enquiry assignments must belong to the same organisation and school before V006 can be applied';
    END IF;
END;
$$;

ALTER TABLE public_enquiries
    ALTER COLUMN school_id SET NOT NULL,
    ALTER COLUMN organisation_id SET NOT NULL,
    DROP CONSTRAINT public_enquiries_assigned_membership_id_fkey,
    ADD CONSTRAINT public_enquiries_school_organisation_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    ADD CONSTRAINT public_enquiries_assignee_scope_fk
        FOREIGN KEY (assigned_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    ADD CONSTRAINT public_enquiries_id_organisation_school_unique
        UNIQUE (id, organisation_id, school_id),
    ADD CONSTRAINT public_enquiries_source_valid
        CHECK (source IN ('WEBSITE', 'PHONE', 'WALK_IN', 'REFERRAL', 'OTHER')),
    ADD CONSTRAINT public_enquiries_closed_state_valid
        CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));

ALTER TABLE public_enquiries
    DROP CONSTRAINT public_enquiries_idempotency_key_key;

ALTER TABLE public_enquiries
    ADD CONSTRAINT public_enquiries_school_idempotency_unique UNIQUE (school_id, idempotency_key);

CREATE INDEX public_enquiries_school_follow_up_idx
    ON public_enquiries (school_id, follow_up_at, created_at)
    WHERE status NOT IN ('CLOSED', 'SPAM') AND follow_up_at IS NOT NULL;

CREATE INDEX public_enquiries_organisation_school_status_idx
    ON public_enquiries (organisation_id, school_id, status, created_at DESC);

CREATE TABLE admissions_enquiry_notes (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    enquiry_id UUID NOT NULL,
    author_membership_id UUID NOT NULL,
    body VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT admissions_enquiry_notes_enquiry_fk
        FOREIGN KEY (enquiry_id, organisation_id, school_id)
        REFERENCES public_enquiries(id, organisation_id, school_id),
    CONSTRAINT admissions_enquiry_notes_author_fk
        FOREIGN KEY (author_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT admissions_enquiry_notes_body_valid CHECK (length(btrim(body)) BETWEEN 1 AND 1000)
);

CREATE INDEX admissions_enquiry_notes_enquiry_time_idx
    ON admissions_enquiry_notes (enquiry_id, created_at DESC);

ALTER TABLE public_enquiries ENABLE ROW LEVEL SECURITY;

CREATE POLICY public_enquiries_tenant_select ON public_enquiries
    FOR SELECT
    USING (organisation_id = current_app_organisation_id());

CREATE POLICY public_enquiries_tenant_insert ON public_enquiries
    FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY public_enquiries_tenant_update ON public_enquiries
    FOR UPDATE
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE admissions_enquiry_notes ENABLE ROW LEVEL SECURITY;

CREATE POLICY admissions_enquiry_notes_tenant_select ON admissions_enquiry_notes
    FOR SELECT
    USING (organisation_id = current_app_organisation_id());

CREATE POLICY admissions_enquiry_notes_tenant_insert ON admissions_enquiry_notes
    FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE admissions_enquiry_notes IS
    'Private admissions notes. Notes are append-only in application workflows and never included in public responses.';
COMMENT ON COLUMN public_enquiries.source IS
    'Server-controlled intake channel; public website submissions use WEBSITE.';
