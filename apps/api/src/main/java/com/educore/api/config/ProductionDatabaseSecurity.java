package com.educore.api.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks the actual database login, not a configurable assertion that it is safe. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDatabaseSecurity implements InitializingBean {
    private final JdbcTemplate jdbc;

    public ProductionDatabaseSecurity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Bean
    FlywayMigrationStrategy productionApiDoesNotMigrate() {
        return flyway -> {
            throw new IllegalStateException("Production API cannot run migrations; use the separate DatabaseMigrationTool before startup");
        };
    }

    @Override
    public void afterPropertiesSet() {
        verify(jdbc);
    }

    public static void verify(JdbcTemplate jdbc) {
        Boolean safe = jdbc.queryForObject("""
                SELECT current_user = session_user
                  AND current_setting('row_security') = 'on'
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_roles role
                    WHERE pg_has_role(session_user, role.oid, 'MEMBER')
                      AND (role.rolsuper OR role.rolbypassrls OR role.rolcreaterole
                        OR role.rolcreatedb OR role.rolreplication
                        OR role.rolname IN ('pg_read_server_files', 'pg_write_server_files',
                          'pg_execute_server_program', 'pg_read_all_data', 'pg_write_all_data', 'pg_maintain'))
                  )
                  AND NOT has_database_privilege(current_database(), 'CREATE')
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_namespace schema
                    WHERE schema.nspname !~ '^pg_' AND schema.nspname <> 'information_schema'
                      AND (has_schema_privilege(schema.oid, 'CREATE')
                        OR pg_has_role(session_user, schema.nspowner, 'MEMBER'))
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_database database
                    WHERE database.datname = current_database()
                      AND pg_has_role(session_user, database.datdba, 'MEMBER')
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_class relation JOIN pg_namespace schema ON schema.oid=relation.relnamespace
                    WHERE schema.nspname='public' AND relation.relkind IN ('r','p','v','m','S')
                      AND (pg_has_role(session_user, relation.relowner, 'MEMBER')
                        OR (relation.relkind IN ('r','p')
                          AND has_table_privilege(relation.oid, 'TRUNCATE,REFERENCES,TRIGGER')))
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_proc function JOIN pg_namespace schema ON schema.oid=function.pronamespace
                    WHERE schema.nspname='public'
                      AND (pg_has_role(session_user, function.proowner, 'MEMBER')
                        OR (function.prosecdef AND has_function_privilege(function.oid, 'EXECUTE')))
                  )
                  AND NOT has_table_privilege('public.flyway_schema_history', 'INSERT,UPDATE,DELETE,TRUNCATE')
                  AND NOT has_table_privilege('public.audit_events', 'UPDATE,DELETE,TRUNCATE')
                  AND (SELECT count(*)=4 FROM pg_class relation
                    WHERE relation.oid IN ('public.schools'::regclass, 'public.memberships'::regclass,
                      'public.learners'::regclass, 'public.audit_events'::regclass)
                      AND relation.relrowsecurity AND row_security_active(relation.oid))
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_class relation JOIN pg_namespace schema ON schema.oid=relation.relnamespace
                    WHERE schema.nspname='public' AND relation.relkind IN ('r','p')
                      AND EXISTS (SELECT 1 FROM pg_attribute attribute
                        WHERE attribute.attrelid=relation.oid AND attribute.attname='organisation_id'
                          AND NOT attribute.attisdropped)
                      AND (NOT relation.relrowsecurity OR NOT row_security_active(relation.oid))
                  )
                """, Boolean.class);
        if (!Boolean.TRUE.equals(safe)) {
            throw new IllegalStateException("Production database requires a non-owner, non-privileged runtime login with active tenant RLS and no DDL or migration-write privileges");
        }
    }
}
