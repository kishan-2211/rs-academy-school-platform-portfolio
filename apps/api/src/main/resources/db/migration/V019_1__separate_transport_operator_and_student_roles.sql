DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM roles
        WHERE id = '00000000-0000-4000-8000-000000000010'
          AND organisation_id IS NULL
          AND code = 'transport_operator'
          AND system_role
    ) THEN
        RAISE EXCEPTION 'Expected the V019 transport operator role before separating role IDs';
    END IF;
    IF EXISTS (
        SELECT 1 FROM roles
        WHERE id = '00000000-0000-4000-8000-000000000011'
    ) THEN
        RAISE EXCEPTION 'The reserved transport operator role ID is already in use';
    END IF;
END;
$$;

UPDATE roles
SET code = 'transport_operator_role_id_migration',
    updated_at = CURRENT_TIMESTAMP,
    row_version = row_version + 1
WHERE id = '00000000-0000-4000-8000-000000000010'
  AND organisation_id IS NULL
  AND code = 'transport_operator';

INSERT INTO roles (
    id, organisation_id, code, display_name, description, system_role,
    created_at, updated_at, row_version
)
SELECT
    '00000000-0000-4000-8000-000000000011', organisation_id,
    'transport_operator', display_name, description, system_role,
    created_at, CURRENT_TIMESTAMP, 0
FROM roles
WHERE id = '00000000-0000-4000-8000-000000000010'
  AND organisation_id IS NULL
  AND code = 'transport_operator_role_id_migration';

INSERT INTO role_capabilities (role_id, capability_code)
SELECT '00000000-0000-4000-8000-000000000011', capability_code
FROM role_capabilities
WHERE role_id = '00000000-0000-4000-8000-000000000010';

UPDATE membership_roles
SET role_id = '00000000-0000-4000-8000-000000000011'
WHERE role_id = '00000000-0000-4000-8000-000000000010';

DELETE FROM roles
WHERE id = '00000000-0000-4000-8000-000000000010'
  AND organisation_id IS NULL
  AND code = 'transport_operator_role_id_migration';

COMMENT ON TABLE roles IS
    'System and school-defined roles. V019.1 reserves role 00000000-0000-4000-8000-000000000011 for transport_operator so V020 can use 00000000-0000-4000-8000-000000000010 for student without collision.';
