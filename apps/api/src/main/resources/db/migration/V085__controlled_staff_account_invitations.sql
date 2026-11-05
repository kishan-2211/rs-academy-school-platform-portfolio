-- Staff identity setup extends the existing membership/role and People boundaries.
-- It neither provisions a first administrator nor changes existing grants/passwords.
CREATE TABLE staff_account_invitations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    staff_version BIGINT NOT NULL CHECK (staff_version >= 0),
    staff_name VARCHAR(120) NOT NULL,
    school_name VARCHAR(200) NOT NULL,
    target_email_normalized VARCHAR(254) NOT NULL,
    staff_language VARCHAR(2) NOT NULL CHECK (staff_language IN ('en','hi')),
    staff_joined_on DATE NOT NULL,
    role_id UUID NOT NULL REFERENCES roles(id),
    role_code VARCHAR(80) NOT NULL,
    role_name VARCHAR(120) NOT NULL,
    role_capabilities TEXT[] NOT NULL,
    role_capabilities_hash CHAR(64) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    token_ciphertext VARCHAR(200),
    token_nonce VARCHAR(60),
    status VARCHAR(20) NOT NULL,
    delivery_status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES user_accounts(id),
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    creation_reason VARCHAR(240) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    handoff_at TIMESTAMPTZ,
    handoff_by_membership_id UUID,
    accepted_at TIMESTAMPTZ,
    accepted_user_id UUID REFERENCES user_accounts(id),
    accepted_membership_id UUID,
    revoked_at TIMESTAMPTZ,
    revoked_by_membership_id UUID,
    revoked_reason VARCHAR(240),
    CONSTRAINT staff_account_invitations_scope_unique UNIQUE (id,organisation_id,school_id),
    CONSTRAINT staff_account_invitations_request_unique UNIQUE (organisation_id,request_id),
    CONSTRAINT staff_account_invitations_school_fk FOREIGN KEY (school_id,organisation_id) REFERENCES schools(id,organisation_id),
    CONSTRAINT staff_account_invitations_staff_fk FOREIGN KEY (staff_profile_id,organisation_id,school_id) REFERENCES staff_profiles(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitations_creator_fk FOREIGN KEY (created_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitations_handoff_fk FOREIGN KEY (handoff_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitations_acceptor_fk FOREIGN KEY (accepted_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitations_revoker_fk FOREIGN KEY (revoked_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitations_hashes CHECK (request_fingerprint ~ '^[a-f0-9]{64}$' AND token_hash ~ '^[a-f0-9]{64}$' AND role_capabilities_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT staff_account_invitations_email CHECK (target_email_normalized=lower(btrim(target_email_normalized)) AND length(target_email_normalized) BETWEEN 3 AND 254 AND target_email_normalized !~ '[[:cntrl:]]'),
    CONSTRAINT staff_account_invitations_role_allowlist CHECK (role_code IN ('school_admin','admissions_manager','teacher','finance_officer','principal_director','auditor','staff_operations')),
    CONSTRAINT staff_account_invitations_ttl CHECK (expires_at>=created_at+INTERVAL '1 hour' AND expires_at<=created_at+INTERVAL '168 hours'),
    CONSTRAINT staff_account_invitations_reason CHECK (length(btrim(creation_reason)) BETWEEN 3 AND 240 AND creation_reason !~ '[[:cntrl:]]'),
    CONSTRAINT staff_account_invitations_handoff CHECK ((handoff_at IS NULL AND handoff_by_membership_id IS NULL) OR (handoff_at IS NOT NULL AND handoff_by_membership_id IS NOT NULL AND handoff_at>=created_at)),
    CONSTRAINT staff_account_invitations_delivery CHECK (
        (delivery_status='AWAITING_HANDOFF' AND status='ISSUED' AND token_ciphertext IS NOT NULL AND token_nonce IS NOT NULL AND handoff_at IS NULL)
        OR (delivery_status='HANDOFF_REVEALED' AND status IN ('ISSUED','ACCEPTED') AND token_ciphertext IS NULL AND token_nonce IS NULL AND handoff_at IS NOT NULL)
        OR (delivery_status='CANCELLED' AND status IN ('REVOKED','EXPIRED') AND token_ciphertext IS NULL AND token_nonce IS NULL)),
    CONSTRAINT staff_account_invitations_state CHECK (
        (status='ISSUED' AND accepted_at IS NULL AND accepted_user_id IS NULL AND accepted_membership_id IS NULL AND revoked_at IS NULL AND revoked_by_membership_id IS NULL AND revoked_reason IS NULL)
        OR (status='ACCEPTED' AND accepted_at IS NOT NULL AND accepted_user_id IS NOT NULL AND accepted_membership_id IS NOT NULL AND accepted_at>=created_at AND accepted_at<expires_at AND revoked_at IS NULL AND revoked_by_membership_id IS NULL AND revoked_reason IS NULL)
        OR (status='REVOKED' AND accepted_at IS NULL AND accepted_user_id IS NULL AND accepted_membership_id IS NULL AND revoked_at IS NOT NULL AND revoked_by_membership_id IS NOT NULL AND length(btrim(revoked_reason)) BETWEEN 3 AND 240 AND revoked_reason !~ '[[:cntrl:]]')
        OR (status='EXPIRED' AND accepted_at IS NULL AND accepted_user_id IS NULL AND accepted_membership_id IS NULL AND revoked_at IS NULL AND revoked_by_membership_id IS NULL AND revoked_reason IS NULL))
);
CREATE UNIQUE INDEX staff_account_invitations_one_open_staff ON staff_account_invitations(organisation_id,school_id,staff_profile_id) WHERE status='ISSUED';
CREATE INDEX staff_account_invitations_history ON staff_account_invitations(organisation_id,school_id,staff_profile_id,created_at DESC,id DESC);
CREATE INDEX staff_account_invitations_expiry ON staff_account_invitations(expires_at) WHERE status='ISSUED';

CREATE TABLE staff_account_invitation_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    invitation_id UUID NOT NULL,
    request_id UUID,
    request_fingerprint CHAR(64),
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('ISSUED','REVOKED','EXPIRED','HANDOFF_REVEALED','ACCEPTED')),
    actor_user_id UUID NOT NULL REFERENCES user_accounts(id),
    actor_membership_id UUID NOT NULL,
    reason VARCHAR(240) NOT NULL CHECK (length(btrim(reason)) BETWEEN 3 AND 240 AND reason !~ '[[:cntrl:]]'),
    occurred_at TIMESTAMPTZ NOT NULL,
    invitation_version BIGINT NOT NULL CHECK (invitation_version>=0),
    CONSTRAINT staff_account_invitation_events_invitation_fk FOREIGN KEY (invitation_id,organisation_id,school_id) REFERENCES staff_account_invitations(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitation_events_actor_fk FOREIGN KEY (actor_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT staff_account_invitation_events_request UNIQUE (organisation_id,request_id),
    CONSTRAINT staff_account_invitation_events_version UNIQUE (invitation_id,invitation_version),
    CONSTRAINT staff_account_invitation_events_request_pair CHECK ((request_id IS NULL AND request_fingerprint IS NULL) OR (request_id IS NOT NULL AND request_fingerprint ~ '^[a-f0-9]{64}$'))
);

CREATE FUNCTION guard_staff_account_invitation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_capabilities TEXT[]; current_capabilities_hash TEXT;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_immutable',MESSAGE='Staff invitation history cannot be deleted'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'ISSUED' OR NEW.delivery_status<>'AWAITING_HANDOFF' OR NEW.row_version<>0 OR NEW.updated_at<>NEW.created_at THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_initial_state',MESSAGE='Initial staff invitation state is invalid';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM staff_profiles staff JOIN schools school ON school.id=staff.school_id AND school.organisation_id=staff.organisation_id
            WHERE staff.id=NEW.staff_profile_id AND staff.organisation_id=NEW.organisation_id AND staff.school_id=NEW.school_id
              AND staff.status='ACTIVE' AND staff.user_account_id IS NULL AND staff.ended_on IS NULL AND staff.joined_on=NEW.staff_joined_on
              AND staff.row_version=NEW.staff_version AND staff.display_name=NEW.staff_name AND staff.work_email_normalized=NEW.target_email_normalized
              AND lower(staff.primary_language)=NEW.staff_language AND school.display_name=NEW.school_name) THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_staff_snapshot',MESSAGE='Staff invitation candidate changed';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM memberships WHERE id=NEW.created_by_membership_id AND user_id=NEW.created_by_user_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_creator',MESSAGE='Staff invitation creator scope is invalid';
        END IF;
    ELSE
        IF ROW(NEW.id,NEW.organisation_id,NEW.school_id,NEW.staff_profile_id,NEW.request_id,NEW.request_fingerprint,NEW.staff_version,NEW.staff_name,NEW.school_name,
              NEW.target_email_normalized,NEW.staff_language,NEW.staff_joined_on,NEW.role_id,NEW.role_code,NEW.role_name,NEW.role_capabilities,NEW.role_capabilities_hash,
              NEW.token_hash,NEW.expires_at,NEW.created_by_user_id,NEW.created_by_membership_id,NEW.created_at,NEW.creation_reason)
           IS DISTINCT FROM ROW(OLD.id,OLD.organisation_id,OLD.school_id,OLD.staff_profile_id,OLD.request_id,OLD.request_fingerprint,OLD.staff_version,OLD.staff_name,OLD.school_name,
              OLD.target_email_normalized,OLD.staff_language,OLD.staff_joined_on,OLD.role_id,OLD.role_code,OLD.role_name,OLD.role_capabilities,OLD.role_capabilities_hash,
              OLD.token_hash,OLD.expires_at,OLD.created_by_user_id,OLD.created_by_membership_id,OLD.created_at,OLD.creation_reason)
           OR OLD.status<>'ISSUED' OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_immutable',MESSAGE='Staff invitation immutable facts cannot change';
        END IF;
        IF NOT (
            (NEW.status='ISSUED' AND OLD.delivery_status='AWAITING_HANDOFF' AND NEW.delivery_status='HANDOFF_REVEALED' AND NEW.handoff_at IS NOT NULL)
            OR (NEW.status IN ('REVOKED','EXPIRED') AND NEW.delivery_status='CANCELLED' AND ROW(NEW.handoff_at,NEW.handoff_by_membership_id) IS NOT DISTINCT FROM ROW(OLD.handoff_at,OLD.handoff_by_membership_id))
            OR (NEW.status='ACCEPTED' AND OLD.delivery_status='HANDOFF_REVEALED' AND NEW.delivery_status='HANDOFF_REVEALED' AND ROW(NEW.handoff_at,NEW.handoff_by_membership_id) IS NOT DISTINCT FROM ROW(OLD.handoff_at,OLD.handoff_by_membership_id))) THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_transition',MESSAGE='Staff invitation transition is invalid';
        END IF;
        IF NEW.status='EXPIRED' AND NEW.updated_at<NEW.expires_at THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_expiry',MESSAGE='Staff invitation has not expired';
        END IF;
        IF NEW.status='ACCEPTED' AND NOT EXISTS(SELECT 1 FROM memberships member JOIN user_accounts account ON account.id=member.user_id
            JOIN staff_profiles staff ON staff.id=NEW.staff_profile_id AND staff.organisation_id=NEW.organisation_id AND staff.school_id=NEW.school_id
            WHERE member.id=NEW.accepted_membership_id AND member.user_id=NEW.accepted_user_id AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id
              AND member.status='ACTIVE' AND member.valid_from<=NEW.accepted_at AND (member.valid_until IS NULL OR member.valid_until>NEW.accepted_at)
              AND account.status='ACTIVE' AND account.email_normalized=NEW.target_email_normalized
              AND staff.user_account_id=NEW.accepted_user_id AND staff.row_version=NEW.staff_version+1
              AND (SELECT count(*) FROM membership_roles WHERE membership_id=member.id)=1
              AND EXISTS(SELECT 1 FROM membership_roles WHERE membership_id=member.id AND role_id=NEW.role_id)) THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_acceptance_link',MESSAGE='Staff invitation requires its exact accepted membership and staff link';
        END IF;
    END IF;
    IF TG_OP='INSERT' OR NEW.status='ACCEPTED' THEN
        SELECT COALESCE(array_agg(mapping.capability_code ORDER BY mapping.capability_code),ARRAY[]::TEXT[]) INTO current_capabilities
          FROM role_capabilities mapping WHERE mapping.role_id=NEW.role_id;
        current_capabilities_hash:=encode(sha256(convert_to(array_to_string(current_capabilities,E'\n'),'UTF8')),'hex');
        IF NOT EXISTS(SELECT 1 FROM roles WHERE id=NEW.role_id AND code=NEW.role_code AND display_name=NEW.role_name AND organisation_id IS NULL AND system_role)
            OR current_capabilities IS DISTINCT FROM NEW.role_capabilities OR current_capabilities_hash<>NEW.role_capabilities_hash THEN
            RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_role_snapshot',MESSAGE='Staff invitation system role changed';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER staff_account_invitations_guard BEFORE INSERT OR UPDATE OR DELETE ON staff_account_invitations FOR EACH ROW EXECUTE FUNCTION guard_staff_account_invitation();

CREATE FUNCTION guard_staff_account_invitation_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_event_immutable',MESSAGE='Staff invitation events are append-only'; END IF;
    IF NOT EXISTS(SELECT 1 FROM staff_account_invitations invitation JOIN memberships actor ON actor.id=NEW.actor_membership_id
        WHERE invitation.id=NEW.invitation_id AND invitation.organisation_id=NEW.organisation_id AND invitation.school_id=NEW.school_id
          AND invitation.row_version=NEW.invitation_version AND actor.organisation_id=NEW.organisation_id AND actor.school_id=NEW.school_id AND actor.user_id=NEW.actor_user_id
          AND (NEW.event_type=invitation.status OR (NEW.event_type='HANDOFF_REVEALED' AND invitation.status='ISSUED' AND invitation.delivery_status='HANDOFF_REVEALED'))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='staff_account_invitation_event_coupling',MESSAGE='Staff invitation event does not match the retained transition';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER staff_account_invitation_events_guard BEFORE INSERT OR UPDATE OR DELETE ON staff_account_invitation_events FOR EACH ROW EXECUTE FUNCTION guard_staff_account_invitation_event();

ALTER TABLE staff_account_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_account_invitation_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY staff_account_invitations_scope_select ON staff_account_invitations FOR SELECT USING (organisation_id=current_app_organisation_id());
CREATE POLICY staff_account_invitations_scope_insert ON staff_account_invitations FOR INSERT WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY staff_account_invitations_scope_update ON staff_account_invitations FOR UPDATE USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY staff_account_invitations_token_discovery ON staff_account_invitations FOR SELECT USING (status='ISSUED' AND token_hash=NULLIF(current_setting('app.current_staff_invitation_token_hash',true),''));
CREATE POLICY staff_account_invitation_events_scope_select ON staff_account_invitation_events FOR SELECT USING (organisation_id=current_app_organisation_id());
CREATE POLICY staff_account_invitation_events_scope_insert ON staff_account_invitation_events FOR INSERT WITH CHECK (organisation_id=current_app_organisation_id());
-- No UPDATE/DELETE policy exists for events, and triggers also protect privileged operators.
COMMENT ON TABLE staff_account_invitations IS 'Controlled staff setup: exact frozen employee and system-role grants, expiring one-use token, private handoff only; never an authentication session or password reset.';
COMMENT ON COLUMN staff_account_invitations.token_ciphertext IS 'Purpose-bound encrypted handoff material; cleared at the first reveal or terminal transition. Unknown reveal results require revoke/reissue.';
COMMENT ON TABLE staff_account_invitation_events IS 'Append-only scoped invitation transition evidence without raw token, password, email destination or secret URL.';
