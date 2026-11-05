-- Additive guards: preserve all existing templates, PDFs and historical decisions.
-- Distinct memberships are not necessarily distinct people.
CREATE FUNCTION official_document_actor_is_independent(
    actor_id UUID, maker_id UUID, org_id UUID, target_school_id UUID
) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM memberships actor
        JOIN memberships maker ON maker.id=maker_id
          AND maker.organisation_id=org_id AND maker.school_id=target_school_id
        WHERE actor.id=actor_id AND actor.organisation_id=org_id
          AND actor.school_id=target_school_id AND actor.user_id<>maker.user_id
    );
$$;

CREATE FUNCTION guard_official_template_independent_person()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status IN ('PUBLISHED','RETIRED') AND NOT official_document_actor_is_independent(
            NEW.published_by_membership_id,NEW.created_by_membership_id,
            NEW.organisation_id,NEW.school_id) THEN
            RAISE EXCEPTION 'official template publication requires an independent person';
        END IF;
    ELSIF OLD.status='DRAFT' AND NEW.status='PUBLISHED' THEN
        IF NOT official_document_actor_is_independent(NEW.published_by_membership_id,
            OLD.created_by_membership_id,OLD.organisation_id,OLD.school_id) THEN
            RAISE EXCEPTION 'official template publication requires an independent person';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER official_document_templates_guard_person
    BEFORE INSERT OR UPDATE ON official_document_templates
    FOR EACH ROW EXECUTE FUNCTION guard_official_template_independent_person();

CREATE FUNCTION guard_official_document_independent_person()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.approval_required_snapshot AND NEW.status IN ('ISSUED','REJECTED','CANCELLED','SUPERSEDED')
           AND NOT official_document_actor_is_independent(NEW.decided_by_membership_id,
                NEW.requested_by_membership_id,NEW.organisation_id,NEW.school_id) THEN
            RAISE EXCEPTION 'official document decision requires an independent person';
        END IF;
    ELSIF OLD.status='PENDING_APPROVAL' AND NEW.status IN ('ISSUED','REJECTED') THEN
        IF NOT official_document_actor_is_independent(NEW.decided_by_membership_id,
            OLD.requested_by_membership_id,OLD.organisation_id,OLD.school_id) THEN
            RAISE EXCEPTION 'official document decision requires an independent person';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER official_documents_guard_person
    BEFORE INSERT OR UPDATE ON official_documents
    FOR EACH ROW EXECUTE FUNCTION guard_official_document_independent_person();
