-- Operator-owned, idempotent grants; not a historical migration. Run as the
-- migration owner AFTER migration, in the same connection as the role setting.
-- SELECT set_config('educore.runtime_role', '<dedicated-login>', false);
DO $$
DECLARE
    runtime_role name := nullif(current_setting('educore.runtime_role', true), '');
    relation record;
BEGIN
    IF runtime_role IS NULL OR runtime_role = current_user THEN
        RAISE EXCEPTION 'A separate existing runtime login must be specified';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=runtime_role AND rolcanlogin
      AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication) THEN
        RAISE EXCEPTION 'Runtime login is missing or privileged';
    END IF;
    IF pg_has_role(runtime_role, current_user, 'MEMBER') THEN
        RAISE EXCEPTION 'Runtime login must not be a member of the migration owner';
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', runtime_role);
    -- Shared role/capability definitions and migration evidence are read-only.
    FOR relation IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
        EXECUTE format('GRANT SELECT ON TABLE public.%I TO %I', relation.tablename, runtime_role);
        IF relation.tablename = 'audit_events' THEN
            EXECUTE format('GRANT INSERT ON TABLE public.audit_events TO %I', runtime_role);
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.audit_events FROM %I', runtime_role);
        ELSIF relation.tablename NOT IN ('flyway_schema_history','capabilities','roles','role_capabilities','organisations') THEN
            EXECUTE format('GRANT INSERT, UPDATE ON TABLE public.%I TO %I', relation.tablename, runtime_role);
        ELSE
            EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.%I FROM %I', relation.tablename, runtime_role);
        END IF;
    END LOOP;
    -- Source-proof readers are security-invoker views: they need their own
    -- read-only grant as well as the underlying tenant-RLS table grants above.
    -- Do not grant arbitrary future/security-definer views by default.
    FOR relation IN SELECT unnest(ARRAY[
        'parent_request_notification_sources', 'student_leave_notification_sources',
        'student_leave_submission_notification_sources', 'student_leave_submission_notification_reviewers',
        'learning_publication_notification_sources', 'learning_publication_notification_recipients'
    ]) AS view_name LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class view JOIN pg_namespace schema ON schema.oid=view.relnamespace
            WHERE schema.nspname='public' AND view.relname=relation.view_name AND view.relkind='v'
              AND view.reloptions @> ARRAY['security_invoker=true']) THEN
            RAISE EXCEPTION 'Expected security-invoker notification proof view is missing';
        END IF;
        EXECUTE format('GRANT SELECT ON TABLE public.%I TO %I', relation.view_name, runtime_role);
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public.%I FROM %I',
            relation.view_name, runtime_role);
    END LOOP;
    -- Only existing replaceable DRAFT mappings have a legitimate DELETE path.
    -- Their current publication/history triggers and tenant RLS remain intact.
    EXECUTE format('GRANT DELETE ON TABLE membership_roles, timetable_entries, fee_plan_items,
          fee_accounting_head_mappings, fee_accounting_tender_mappings,
          payroll_accounting_component_mappings, payroll_accounting_tender_mappings,
          payroll_employee_advance_installments, staff_salary_profile_lines,
          transport_route_revision_stops TO %I', runtime_role);
    EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO %I', runtime_role);
    -- No ALL/DDL/TRUNCATE/grant-option privileges; no automatic future grants.
    -- Re-run after each release migration to review that release's real tables.
END $$;
