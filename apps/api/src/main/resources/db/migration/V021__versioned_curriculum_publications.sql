CREATE TABLE curriculum_publications (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    grade_code VARCHAR(40) NOT NULL,
    publication_version INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    supersedes_publication_id UUID,
    created_by_membership_id UUID NOT NULL,
    published_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT curriculum_publications_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT curriculum_publications_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT curriculum_publications_created_by_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT curriculum_publications_published_by_fk
        FOREIGN KEY (published_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT curriculum_publications_supersedes_fk
        FOREIGN KEY (supersedes_publication_id, organisation_id, school_id)
        REFERENCES curriculum_publications(id, organisation_id, school_id),
    CONSTRAINT curriculum_publications_natural_version_unique
        UNIQUE (school_id, academic_session_id, grade_code, publication_version),
    CONSTRAINT curriculum_publications_version_valid CHECK (publication_version > 0),
    CONSTRAINT curriculum_publications_status_valid
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED', 'CANCELLED')),
    CONSTRAINT curriculum_publications_lifecycle_valid CHECK (
        (status = 'DRAFT' AND published_by_membership_id IS NULL AND published_at IS NULL
            AND archived_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'PUBLISHED' AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL
            AND archived_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'ARCHIVED' AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL
            AND archived_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND published_by_membership_id IS NULL AND published_at IS NULL
            AND archived_at IS NULL AND cancelled_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX curriculum_publications_one_draft_idx
    ON curriculum_publications (school_id, academic_session_id, grade_code)
    WHERE status = 'DRAFT';

CREATE UNIQUE INDEX curriculum_publications_one_published_idx
    ON curriculum_publications (school_id, academic_session_id, grade_code)
    WHERE status = 'PUBLISHED';

CREATE INDEX curriculum_publications_scope_history_idx
    ON curriculum_publications (
        organisation_id, school_id, academic_session_id, grade_code,
        publication_version DESC, created_at DESC
    );

CREATE TABLE curriculum_publication_items (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    curriculum_publication_id UUID NOT NULL,
    source_resource_id UUID NOT NULL,
    subject_code_snapshot VARCHAR(30) NOT NULL,
    subject_name_snapshot VARCHAR(120) NOT NULL,
    subject_short_name_snapshot VARCHAR(40),
    resource_type_snapshot VARCHAR(20) NOT NULL,
    title_snapshot VARCHAR(200) NOT NULL,
    description_snapshot VARCHAR(1000),
    author_snapshot VARCHAR(160),
    publisher_snapshot VARCHAR(160),
    edition_snapshot VARCHAR(80),
    isbn_snapshot VARCHAR(32),
    source_url_snapshot VARCHAR(1000),
    display_order_snapshot SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT curriculum_publication_items_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT curriculum_publication_items_publication_fk
        FOREIGN KEY (curriculum_publication_id, organisation_id, school_id)
        REFERENCES curriculum_publications(id, organisation_id, school_id),
    CONSTRAINT curriculum_publication_items_resource_fk
        FOREIGN KEY (source_resource_id, organisation_id, school_id)
        REFERENCES academic_subject_resources(id, organisation_id, school_id),
    CONSTRAINT curriculum_publication_items_source_unique
        UNIQUE (curriculum_publication_id, source_resource_id),
    CONSTRAINT curriculum_publication_items_type_valid CHECK (
        resource_type_snapshot IN ('SYLLABUS', 'TEXTBOOK', 'WORKBOOK', 'REFERENCE', 'DIGITAL')
    ),
    CONSTRAINT curriculum_publication_items_order_valid
        CHECK (display_order_snapshot BETWEEN 0 AND 1000)
);

CREATE INDEX curriculum_publication_items_scope_idx
    ON curriculum_publication_items (
        organisation_id, school_id, curriculum_publication_id,
        subject_name_snapshot, display_order_snapshot, title_snapshot
    );

CREATE OR REPLACE FUNCTION guard_curriculum_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    superseded_session UUID;
    superseded_grade VARCHAR(40);
    superseded_version INTEGER;
    superseded_status VARCHAR(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Curriculum publications cannot be deleted';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        OLD.id <> NEW.id
        OR OLD.organisation_id <> NEW.organisation_id
        OR OLD.school_id <> NEW.school_id
        OR OLD.academic_session_id <> NEW.academic_session_id
        OR OLD.grade_code <> NEW.grade_code
        OR OLD.publication_version <> NEW.publication_version
        OR OLD.supersedes_publication_id IS DISTINCT FROM NEW.supersedes_publication_id
        OR OLD.created_by_membership_id <> NEW.created_by_membership_id
        OR OLD.created_at <> NEW.created_at
        OR (OLD.published_by_membership_id IS DISTINCT FROM NEW.published_by_membership_id
            AND OLD.status <> 'DRAFT')
        OR (OLD.published_at IS DISTINCT FROM NEW.published_at AND OLD.status <> 'DRAFT')
    ) THEN
        RAISE EXCEPTION 'Curriculum publication identity and evidence are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status IN ('PUBLISHED', 'CANCELLED'))
        OR (OLD.status = 'PUBLISHED' AND NEW.status = 'ARCHIVED')
    ) THEN
        RAISE EXCEPTION 'Invalid curriculum publication transition';
    END IF;
    IF NEW.supersedes_publication_id IS NOT NULL THEN
        SELECT academic_session_id, grade_code, publication_version, status
          INTO superseded_session, superseded_grade, superseded_version, superseded_status
        FROM curriculum_publications
        WHERE id = NEW.supersedes_publication_id
          AND organisation_id = NEW.organisation_id
          AND school_id = NEW.school_id;
        IF superseded_session IS DISTINCT FROM NEW.academic_session_id
           OR superseded_grade IS DISTINCT FROM NEW.grade_code
           OR superseded_version >= NEW.publication_version
           OR superseded_status NOT IN ('PUBLISHED', 'ARCHIVED') THEN
            RAISE EXCEPTION 'Curriculum supersession must reference an earlier release for the same session and grade';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER curriculum_publications_guard
    BEFORE INSERT OR UPDATE OR DELETE ON curriculum_publications
    FOR EACH ROW EXECUTE FUNCTION guard_curriculum_publication();

CREATE OR REPLACE FUNCTION guard_curriculum_publication_item()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    publication_status VARCHAR(20);
    publication_organisation UUID;
    publication_school UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'Curriculum publication items are immutable';
    END IF;
    SELECT status, organisation_id, school_id
      INTO publication_status, publication_organisation, publication_school
    FROM curriculum_publications
    WHERE id = NEW.curriculum_publication_id;
    IF publication_status IS DISTINCT FROM 'DRAFT'
       OR publication_organisation IS DISTINCT FROM NEW.organisation_id
       OR publication_school IS DISTINCT FROM NEW.school_id THEN
        RAISE EXCEPTION 'Curriculum items can only be captured inside a same-school draft';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER curriculum_publication_items_guard
    BEFORE INSERT OR UPDATE OR DELETE ON curriculum_publication_items
    FOR EACH ROW EXECUTE FUNCTION guard_curriculum_publication_item();

ALTER TABLE curriculum_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE curriculum_publication_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY curriculum_publications_tenant_select ON curriculum_publications
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY curriculum_publications_tenant_insert ON curriculum_publications
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY curriculum_publications_tenant_update ON curriculum_publications
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY curriculum_publication_items_tenant_select ON curriculum_publication_items
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY curriculum_publication_items_tenant_insert ON curriculum_publication_items
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE curriculum_publications IS
    'Session-and-grade curriculum releases. Corrections publish a superseding immutable snapshot instead of rewriting family history.';
COMMENT ON TABLE curriculum_publication_items IS
    'Append-only snapshots of authority-confirmed syllabus, book, workbook, reference, and digital-resource catalogue entries.';
