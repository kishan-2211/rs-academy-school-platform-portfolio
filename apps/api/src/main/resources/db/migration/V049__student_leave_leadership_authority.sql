-- Preserve V048 as applied. The established leadership role uses principal_director.
INSERT INTO role_capabilities(role_id,capability_code)
SELECT id,'student_leave.manage' FROM roles
WHERE system_role AND organisation_id IS NULL AND code='principal_director'
ON CONFLICT DO NOTHING;
