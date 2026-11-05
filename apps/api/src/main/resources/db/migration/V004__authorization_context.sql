CREATE POLICY audit_events_platform_identity_read ON audit_events
    FOR SELECT
    USING (
        organisation_id IS NULL
        AND actor_user_id IS NOT NULL
        AND actor_user_id = current_app_user_id()
    );

CREATE POLICY audit_events_platform_identity_insert ON audit_events
    FOR INSERT
    WITH CHECK (
        organisation_id IS NULL
        AND actor_user_id IS NOT NULL
        AND actor_user_id = current_app_user_id()
    );

INSERT INTO capabilities (code, description, risk_level) VALUES
    ('school.configure', 'Configure school, campuses, academic sessions, and branding', 'RESTRICTED'),
    ('student.read', 'Read ordinary learner records within assigned scope', 'SENSITIVE'),
    ('student.manage', 'Create and update learner identity, enrolment, and placement', 'SENSITIVE'),
    ('student.sensitive.read', 'Read separately protected learner information', 'RESTRICTED'),
    ('guardian.read', 'Read guardian and household relationships within assigned scope', 'SENSITIVE'),
    ('guardian.manage', 'Manage guardian relationships, access, and contact rules', 'RESTRICTED'),
    ('academics.configure', 'Configure terms, classes, sections, subjects, and teaching allocation', 'SENSITIVE'),
    ('attendance.read', 'Read attendance within assigned scope', 'SENSITIVE'),
    ('attendance.record', 'Record attendance for assigned teaching groups', 'SENSITIVE'),
    ('attendance.correct', 'Approve or make attendance corrections after lock', 'RESTRICTED'),
    ('assessment.read', 'Read assessment definitions and permitted results', 'SENSITIVE'),
    ('assessment.record', 'Enter assessment evidence and marks for assigned groups', 'SENSITIVE'),
    ('assessment.publish', 'Approve and publish results or report cards', 'RESTRICTED'),
    ('fees.read', 'Read fee accounts and payment status', 'SENSITIVE'),
    ('fees.manage', 'Create fee plans, charges, concessions, and payment allocations', 'RESTRICTED'),
    ('fees.refund', 'Approve and execute refunds or financial reversals', 'RESTRICTED'),
    ('communication.send', 'Send messages to authorized school audiences', 'SENSITIVE'),
    ('communication.configure', 'Configure providers, templates, consent, and sending policy', 'RESTRICTED'),
    ('reports.read', 'Run authorized operational reports', 'SENSITIVE'),
    ('reports.export', 'Create controlled exports of school data', 'RESTRICTED'),
    ('files.read', 'Read private files attached to records the user may access', 'SENSITIVE'),
    ('files.manage', 'Upload, classify, replace, or expire private files', 'SENSITIVE'),
    ('health.read', 'Read protected student health information', 'RESTRICTED'),
    ('safeguarding.read', 'Read safeguarding records through the dedicated workflow', 'RESTRICTED'),
    ('safeguarding.manage', 'Create or update safeguarding records and emergency access', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (
    id, organisation_id, code, display_name, description, system_role,
    created_at, updated_at, row_version
) VALUES
    ('00000000-0000-4000-8000-000000000001', NULL, 'school_admin', 'School administrator', 'Full school configuration and operational administration, excluding dedicated safeguarding and health access.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('00000000-0000-4000-8000-000000000002', NULL, 'admissions_manager', 'Admissions manager', 'Admissions enquiry and applicant workflow access.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('00000000-0000-4000-8000-000000000003', NULL, 'teacher', 'Teacher', 'Assigned-class academics, attendance, assessment, and communication access.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('00000000-0000-4000-8000-000000000004', NULL, 'finance_officer', 'Finance officer', 'Fee operations and financial reporting without unrestricted identity administration.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('00000000-0000-4000-8000-000000000005', NULL, 'auditor', 'Read-only auditor', 'Scoped audit and reporting access without operational mutation rights.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (organisation_id, code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'platform.configure'),
    ('00000000-0000-4000-8000-000000000001', 'identity.manage'),
    ('00000000-0000-4000-8000-000000000001', 'school.configure'),
    ('00000000-0000-4000-8000-000000000001', 'admissions.enquiry.read'),
    ('00000000-0000-4000-8000-000000000001', 'admissions.enquiry.manage'),
    ('00000000-0000-4000-8000-000000000001', 'student.read'),
    ('00000000-0000-4000-8000-000000000001', 'student.manage'),
    ('00000000-0000-4000-8000-000000000001', 'guardian.read'),
    ('00000000-0000-4000-8000-000000000001', 'guardian.manage'),
    ('00000000-0000-4000-8000-000000000001', 'academics.configure'),
    ('00000000-0000-4000-8000-000000000001', 'attendance.read'),
    ('00000000-0000-4000-8000-000000000001', 'attendance.correct'),
    ('00000000-0000-4000-8000-000000000001', 'assessment.read'),
    ('00000000-0000-4000-8000-000000000001', 'assessment.publish'),
    ('00000000-0000-4000-8000-000000000001', 'fees.read'),
    ('00000000-0000-4000-8000-000000000001', 'fees.manage'),
    ('00000000-0000-4000-8000-000000000001', 'communication.send'),
    ('00000000-0000-4000-8000-000000000001', 'communication.configure'),
    ('00000000-0000-4000-8000-000000000001', 'reports.read'),
    ('00000000-0000-4000-8000-000000000001', 'reports.export'),
    ('00000000-0000-4000-8000-000000000001', 'files.read'),
    ('00000000-0000-4000-8000-000000000001', 'files.manage'),
    ('00000000-0000-4000-8000-000000000001', 'audit.read'),

    ('00000000-0000-4000-8000-000000000002', 'admissions.enquiry.read'),
    ('00000000-0000-4000-8000-000000000002', 'admissions.enquiry.manage'),
    ('00000000-0000-4000-8000-000000000002', 'student.read'),
    ('00000000-0000-4000-8000-000000000002', 'guardian.read'),
    ('00000000-0000-4000-8000-000000000002', 'files.read'),
    ('00000000-0000-4000-8000-000000000002', 'files.manage'),
    ('00000000-0000-4000-8000-000000000002', 'communication.send'),

    ('00000000-0000-4000-8000-000000000003', 'student.read'),
    ('00000000-0000-4000-8000-000000000003', 'guardian.read'),
    ('00000000-0000-4000-8000-000000000003', 'attendance.read'),
    ('00000000-0000-4000-8000-000000000003', 'attendance.record'),
    ('00000000-0000-4000-8000-000000000003', 'assessment.read'),
    ('00000000-0000-4000-8000-000000000003', 'assessment.record'),
    ('00000000-0000-4000-8000-000000000003', 'communication.send'),
    ('00000000-0000-4000-8000-000000000003', 'files.read'),

    ('00000000-0000-4000-8000-000000000004', 'student.read'),
    ('00000000-0000-4000-8000-000000000004', 'guardian.read'),
    ('00000000-0000-4000-8000-000000000004', 'fees.read'),
    ('00000000-0000-4000-8000-000000000004', 'fees.manage'),
    ('00000000-0000-4000-8000-000000000004', 'reports.read'),
    ('00000000-0000-4000-8000-000000000004', 'files.read'),

    ('00000000-0000-4000-8000-000000000005', 'audit.read'),
    ('00000000-0000-4000-8000-000000000005', 'reports.read')
ON CONFLICT DO NOTHING;

COMMENT ON COLUMN user_sessions.active_organisation_id IS
    'Server-selected only after validating an active membership; never copied from an untrusted request without verification.';
