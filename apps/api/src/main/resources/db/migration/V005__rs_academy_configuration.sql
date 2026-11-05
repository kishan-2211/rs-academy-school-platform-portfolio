CREATE TABLE school_profiles (
    school_id UUID PRIMARY KEY REFERENCES schools(id) ON DELETE CASCADE,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    board_code VARCHAR(40) NOT NULL,
    grade_range VARCHAR(80) NOT NULL,
    motto VARCHAR(160),
    supported_languages VARCHAR(20)[] NOT NULL DEFAULT ARRAY['en-IN']::VARCHAR(20)[],
    public_email VARCHAR(254),
    public_phone VARCHAR(30),
    principal_display_name VARCHAR(120),
    address_line VARCHAR(240) NOT NULL,
    locality VARCHAR(120),
    city VARCHAR(120) NOT NULL,
    state_name VARCHAR(120) NOT NULL,
    country_code CHAR(2) NOT NULL DEFAULT 'IN',
    learner_count_min INTEGER,
    learner_count_max INTEGER,
    teaching_staff_estimate INTEGER,
    operations_staff_estimate INTEGER,
    verified_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT school_profiles_count_range CHECK (
        learner_count_min IS NULL
        OR learner_count_max IS NULL
        OR learner_count_max >= learner_count_min
    ),
    CONSTRAINT school_profiles_counts_nonnegative CHECK (
        coalesce(learner_count_min, 0) >= 0
        AND coalesce(learner_count_max, 0) >= 0
        AND coalesce(teaching_staff_estimate, 0) >= 0
        AND coalesce(operations_staff_estimate, 0) >= 0
    )
);

ALTER TABLE school_profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_profiles_tenant_isolation ON school_profiles
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

INSERT INTO organisations (
    id, slug, legal_name, display_name, status, default_locale,
    created_at, updated_at, row_version
) VALUES (
    '10000000-0000-4000-8000-000000000001',
    'rs-academy',
    'R.S. Academy',
    'R.S. Academy',
    'ACTIVE',
    'en-IN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO schools (
    id, organisation_id, slug, legal_name, display_name, time_zone,
    currency_code, status, created_at, updated_at, row_version
) VALUES (
    '10000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'rs-academy',
    'R.S. Academy',
    'R.S. Academy',
    'Asia/Kolkata',
    'INR',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO campuses (
    id, organisation_id, school_id, code, display_name, time_zone,
    status, created_at, updated_at, row_version
) VALUES (
    '10000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000002',
    'SUAA',
    'Suaari Campus',
    'Asia/Kolkata',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO school_profiles (
    school_id, organisation_id, board_code, grade_range, motto,
    supported_languages, public_email, public_phone, principal_display_name,
    address_line, locality, city, state_name, country_code,
    learner_count_min, learner_count_max, teaching_staff_estimate,
    operations_staff_estimate, verified_at, updated_at
) VALUES (
    '10000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'CBSE',
    'Nursery to Class X',
    'Knowledge is Strength',
    ARRAY['hi-IN', 'en-IN']::VARCHAR(20)[],
    'thersacademysuwari@gmail.com',
    '+919838002012',
    'Brijesh Kumar Singh',
    'R.S. Academy, Suaari, Danganj',
    'Suaari, Danganj',
    'Varanasi',
    'Uttar Pradesh',
    'IN',
    700,
    800,
    20,
    10,
    TIMESTAMPTZ '2026-08-20 00:00:00+05:30',
    CURRENT_TIMESTAMP
) ON CONFLICT (school_id) DO UPDATE SET
    board_code = EXCLUDED.board_code,
    grade_range = EXCLUDED.grade_range,
    motto = EXCLUDED.motto,
    supported_languages = EXCLUDED.supported_languages,
    public_email = EXCLUDED.public_email,
    public_phone = EXCLUDED.public_phone,
    principal_display_name = EXCLUDED.principal_display_name,
    address_line = EXCLUDED.address_line,
    locality = EXCLUDED.locality,
    city = EXCLUDED.city,
    state_name = EXCLUDED.state_name,
    country_code = EXCLUDED.country_code,
    learner_count_min = EXCLUDED.learner_count_min,
    learner_count_max = EXCLUDED.learner_count_max,
    teaching_staff_estimate = EXCLUDED.teaching_staff_estimate,
    operations_staff_estimate = EXCLUDED.operations_staff_estimate,
    verified_at = EXCLUDED.verified_at,
    updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE school_profiles IS
    'Public and operational school identity verified with the school owner; affiliation numbers and unverified facilities remain unset.';
