CREATE TABLE organisations (
    id UUID PRIMARY KEY,
    slug VARCHAR(80) NOT NULL UNIQUE,
    legal_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    default_locale VARCHAR(20) NOT NULL DEFAULT 'en-IN',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT organisations_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$'),
    CONSTRAINT organisations_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE schools (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    slug VARCHAR(80) NOT NULL,
    legal_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    time_zone VARCHAR(60) NOT NULL DEFAULT 'Asia/Kolkata',
    currency_code CHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT schools_slug_unique UNIQUE (organisation_id, slug),
    CONSTRAINT schools_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE campuses (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    time_zone VARCHAR(60) NOT NULL DEFAULT 'Asia/Kolkata',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT campuses_code_unique UNIQUE (school_id, code),
    CONSTRAINT campuses_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE academic_sessions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    name VARCHAR(80) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT academic_sessions_dates_valid CHECK (ends_on > starts_on),
    CONSTRAINT academic_sessions_name_unique UNIQUE (school_id, name),
    CONSTRAINT academic_sessions_status_valid CHECK (status IN ('PLANNED', 'ACTIVE', 'CLOSED', 'ARCHIVED'))
);

CREATE TABLE user_accounts (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failed_login_count INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT user_accounts_email_normalized CHECK (
        email_normalized = lower(email_normalized)
        AND length(email_normalized) BETWEEN 3 AND 254
    ),
    CONSTRAINT user_accounts_status_valid CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT user_accounts_failed_login_valid CHECK (failed_login_count BETWEEN 0 AND 100)
);

CREATE TABLE user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent_summary VARCHAR(200),
    CONSTRAINT user_sessions_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT user_sessions_idle_expiry_valid CHECK (idle_expires_at <= expires_at)
);

CREATE INDEX user_sessions_active_user_idx ON user_sessions (user_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX user_sessions_expiry_idx ON user_sessions (expires_at);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id),
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    school_id UUID REFERENCES schools(id),
    campus_id UUID REFERENCES campuses(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT memberships_scope_unique UNIQUE NULLS NOT DISTINCT (user_id, organisation_id, school_id, campus_id),
    CONSTRAINT memberships_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT memberships_dates_valid CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    organisation_id UUID REFERENCES organisations(id),
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT roles_code_unique UNIQUE NULLS NOT DISTINCT (organisation_id, code)
);

CREATE TABLE capabilities (
    code VARCHAR(120) PRIMARY KEY,
    description VARCHAR(500) NOT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    CONSTRAINT capabilities_risk_valid CHECK (risk_level IN ('STANDARD', 'SENSITIVE', 'RESTRICTED'))
);

CREATE TABLE role_capabilities (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    capability_code VARCHAR(120) NOT NULL REFERENCES capabilities(code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, capability_code)
);

CREATE TABLE membership_roles (
    membership_id UUID NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (membership_id, role_id)
);

CREATE TABLE public_enquiries (
    id UUID PRIMARY KEY,
    school_id UUID REFERENCES schools(id),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    phone VARCHAR(30),
    topic VARCHAR(60),
    message VARCHAR(2000) NOT NULL,
    intent VARCHAR(20) NOT NULL,
    privacy_notice_version VARCHAR(60) NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    assigned_membership_id UUID REFERENCES memberships(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT public_enquiries_email_normalized CHECK (email_normalized = lower(email_normalized)),
    CONSTRAINT public_enquiries_intent_valid CHECK (intent IN ('GENERAL', 'VISIT', 'APPLICATION')),
    CONSTRAINT public_enquiries_status_valid CHECK (status IN ('NEW', 'OPEN', 'QUALIFIED', 'CLOSED', 'SPAM'))
);

CREATE INDEX public_enquiries_school_status_idx ON public_enquiries (school_id, status, created_at DESC);
CREATE INDEX public_enquiries_email_idx ON public_enquiries (school_id, email_normalized);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    organisation_id UUID REFERENCES organisations(id),
    school_id UUID REFERENCES schools(id),
    actor_user_id UUID REFERENCES user_accounts(id),
    actor_membership_id UUID REFERENCES memberships(id),
    request_id VARCHAR(100) NOT NULL,
    action VARCHAR(160) NOT NULL,
    subject_type VARCHAR(120),
    subject_id VARCHAR(120),
    outcome VARCHAR(20) NOT NULL,
    reason VARCHAR(500),
    safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    previous_hash CHAR(64),
    event_hash CHAR(64) NOT NULL,
    CONSTRAINT audit_events_outcome_valid CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED'))
);

CREATE INDEX audit_events_scope_time_idx ON audit_events (organisation_id, school_id, occurred_at DESC);
CREATE INDEX audit_events_actor_time_idx ON audit_events (actor_user_id, occurred_at DESC);
CREATE INDEX audit_events_subject_idx ON audit_events (subject_type, subject_id, occurred_at DESC);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(120),
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT outbox_events_attempts_valid CHECK (attempt_count BETWEEN 0 AND 1000)
);

CREATE INDEX outbox_events_ready_idx
    ON outbox_events (available_at, occurred_at)
    WHERE processed_at IS NULL;

INSERT INTO capabilities (code, description, risk_level) VALUES
    ('platform.configure', 'Configure organisation and platform settings', 'RESTRICTED'),
    ('identity.manage', 'Invite and manage users, roles, and sessions', 'RESTRICTED'),
    ('admissions.enquiry.read', 'Read admissions enquiries', 'SENSITIVE'),
    ('admissions.enquiry.manage', 'Assign and progress admissions enquiries', 'SENSITIVE'),
    ('audit.read', 'Read scoped audit events', 'RESTRICTED');
