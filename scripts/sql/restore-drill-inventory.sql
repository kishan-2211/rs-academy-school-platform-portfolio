-- Read-only, synthetic-only inventory. No row values leave PostgreSQL.
-- Imported/exported transaction snapshot and UTC settings are supplied by the caller.
WITH user_namespaces AS (
    SELECT * FROM pg_namespace
    WHERE nspname NOT IN ('pg_catalog', 'information_schema') AND nspname NOT LIKE 'pg_%'
), relations AS (
    SELECT c.*, n.nspname FROM pg_class c JOIN user_namespaces n ON n.oid = c.relnamespace
), catalog_items AS (
    SELECT jsonb_build_array('schema', nspname, pg_get_userbyid(nspowner),
        (SELECT array_agg(a::text ORDER BY a::text) FROM unnest(nspacl) a))::text item
    FROM user_namespaces
    UNION ALL
    SELECT jsonb_build_array('relation', nspname, relname, relkind, pg_get_userbyid(relowner),
        relrowsecurity, relforcerowsecurity, reloptions,
        (SELECT array_agg(a::text ORDER BY a::text) FROM unnest(relacl) a))::text FROM relations
    WHERE relkind IN ('r', 'p', 'v', 'S')
    UNION ALL
    SELECT jsonb_build_array('column', r.nspname, r.relname, a.attnum, a.attname,
        format_type(a.atttypid, a.atttypmod), a.attnotnull, a.attidentity, a.attgenerated,
        (SELECT array_agg(acl::text ORDER BY acl::text) FROM unnest(a.attacl) acl),
        pg_get_expr(d.adbin, d.adrelid), col.collname)::text
    FROM relations r JOIN pg_attribute a ON a.attrelid = r.oid
    LEFT JOIN pg_attrdef d ON d.adrelid = r.oid AND d.adnum = a.attnum
    LEFT JOIN pg_collation col ON col.oid = a.attcollation
    WHERE a.attnum > 0 AND NOT a.attisdropped
    UNION ALL
    SELECT jsonb_build_array('constraint', r.nspname, r.relname, c.conname,
        c.contype, c.convalidated, c.condeferrable, c.condeferred,
        pg_get_constraintdef(c.oid, false))::text
    FROM pg_constraint c JOIN relations r ON r.oid = c.conrelid
    UNION ALL
    SELECT jsonb_build_array('index', r.nspname, r.relname, i.indisvalid,
        i.indisready, i.indisunique, i.indisprimary, i.indisexclusion,
        i.indimmediate, i.indnullsnotdistinct, pg_get_indexdef(i.indexrelid))::text
    FROM pg_index i JOIN relations r ON r.oid = i.indexrelid
    UNION ALL
    SELECT jsonb_build_array('function', n.nspname, p.proname,
        pg_get_function_identity_arguments(p.oid), pg_get_functiondef(p.oid),
        pg_get_userbyid(p.proowner),
        (SELECT array_agg(a::text ORDER BY a::text) FROM unnest(p.proacl) a))::text
    FROM pg_proc p JOIN user_namespaces n ON n.oid = p.pronamespace WHERE p.prokind IN ('f', 'p')
    UNION ALL
    SELECT jsonb_build_array('trigger', r.nspname, r.relname, t.tgname,
        t.tgenabled, jsonb_build_object(
            'eventType', t.tgtype, 'deferrable', t.tgdeferrable, 'initiallyDeferred', t.tginitdeferred,
            'functionSchema', fn.nspname, 'functionName', f.proname,
            'functionArguments', pg_get_function_identity_arguments(f.oid),
            'functionSecurityDefiner', f.prosecdef,
            'argumentCount', t.tgnargs, 'argumentBytes', encode(t.tgargs, 'hex'),
            'columns', coalesce((SELECT array_agg(a.attname ORDER BY selected.ordinality)
                FROM unnest(t.tgattr::smallint[]) WITH ORDINALITY selected(number, ordinality)
                JOIN pg_attribute a ON a.attrelid=t.tgrelid AND a.attnum=selected.number), ARRAY[]::name[]),
            'whenPresent', t.tgqual IS NOT NULL, 'oldTransitionTable', t.tgoldtable, 'newTransitionTable', t.tgnewtable,
            'constraintName', tc.conname, 'constraintType', tc.contype,
            'constraintRelation', CASE WHEN cr.oid IS NOT NULL THEN jsonb_build_array(crn.nspname, cr.relname) END,
            'constraintIndex', CASE WHEN ci.oid IS NOT NULL THEN jsonb_build_array(cin.nspname, ci.relname) END,
            'parentTrigger', CASE WHEN parent.oid IS NOT NULL THEN jsonb_build_array(prn.nspname, pr.relname, parent.tgname) END),
        pg_get_triggerdef(t.oid, false))::text
    FROM pg_trigger t JOIN relations r ON r.oid=t.tgrelid
    JOIN pg_proc f ON f.oid=t.tgfoid JOIN pg_namespace fn ON fn.oid=f.pronamespace
    LEFT JOIN pg_constraint tc ON tc.oid=t.tgconstraint
    LEFT JOIN pg_class cr ON cr.oid=t.tgconstrrelid LEFT JOIN pg_namespace crn ON crn.oid=cr.relnamespace
    LEFT JOIN pg_class ci ON ci.oid=t.tgconstrindid LEFT JOIN pg_namespace cin ON cin.oid=ci.relnamespace
    LEFT JOIN pg_trigger parent ON parent.oid=t.tgparentid
    LEFT JOIN pg_class pr ON pr.oid=parent.tgrelid LEFT JOIN pg_namespace prn ON prn.oid=pr.relnamespace
    WHERE NOT t.tgisinternal
    UNION ALL
    SELECT jsonb_build_array('policy', r.nspname, r.relname, p.polname,
        p.polcmd, p.polpermissive, p.polroles,
        pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid))::text
    FROM pg_policy p JOIN relations r ON r.oid = p.polrelid
    UNION ALL
    SELECT jsonb_build_array('view', nspname, relname, pg_get_viewdef(oid, false))::text
    FROM relations WHERE relkind = 'v'
    UNION ALL
    SELECT jsonb_build_array('enum', n.nspname, t.typname, e.enumsortorder, e.enumlabel)::text
    FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
    JOIN user_namespaces n ON n.oid = t.typnamespace
    UNION ALL
    SELECT jsonb_build_array('extension', e.extname, e.extversion, n.nspname,
        pg_get_userbyid(e.extowner))::text
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    UNION ALL
    SELECT jsonb_build_array('default-acl', pg_get_userbyid(d.defaclrole),
        coalesce(n.nspname, ''), d.defaclobjtype,
        (SELECT array_agg(a::text ORDER BY a::text) FROM unnest(d.defaclacl) a))::text
    FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace
), table_checks AS (
    SELECT r.nspname, r.relname, x.rows, x.digest
    FROM relations r
    CROSS JOIN LATERAL XMLTABLE('/row' PASSING query_to_xml(format($query$
        SELECT count(*) AS rows,
            encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text,
                chr(10) ORDER BY to_jsonb(row_data)::text), ''), 'UTF8')), 'hex') AS digest
        FROM %I.%I row_data
    $query$, r.nspname, r.relname), false, true, '')
        COLUMNS rows bigint PATH 'rows', digest text PATH 'digest') x
    WHERE r.relkind IN ('r', 'p')
), sequence_checks AS (
    SELECT s.*, x.last_value AS restored_last_value, x.is_called
    FROM pg_sequences s JOIN user_namespaces n ON n.nspname = s.schemaname
    CROSS JOIN LATERAL XMLTABLE('/row' PASSING query_to_xml(
        format('SELECT last_value, is_called FROM %I.%I', s.schemaname, s.sequencename),
        false, true, '') COLUMNS last_value bigint PATH 'last_value', is_called boolean PATH 'is_called') x
)
SELECT jsonb_build_object(
    'format', 1,
    'migrations', (SELECT count(*) FROM public.flyway_schema_history WHERE success),
    'failedMigrations', (SELECT count(*) FROM public.flyway_schema_history WHERE NOT success),
    'rlsTables', (SELECT count(*) FROM relations WHERE relrowsecurity),
    'policies', (SELECT count(*) FROM pg_policy p JOIN relations r ON r.oid = p.polrelid),
    'immutableTriggers', (SELECT count(*) FROM pg_trigger t JOIN relations r ON r.oid = t.tgrelid
        WHERE NOT t.tgisinternal AND t.tgname LIKE '%prevent_update_or_delete%'),
    'locale', (SELECT jsonb_build_array(pg_encoding_to_char(encoding), datcollate, datctype,
        datlocprovider, datlocale) FROM pg_database WHERE datname = current_database()),
    'catalogSha256', (SELECT encode(sha256(convert_to(string_agg(item, chr(10) ORDER BY item), 'UTF8')), 'hex') FROM catalog_items),
    -- Definitions are still checked in full against the independently restored
    -- schema reference built from the same exported snapshot, not hand-written
    -- equivalent SQL. Trigger WHEN expressions can also acquire per-element
    -- casts during PostgreSQL's DDL reparse. Keep their full source definitions
    -- in catalogSha256/diagnostics; compare complete event/function/argument/
    -- scope metadata here and the entire definition against that independent
    -- reference after the full restore. No predicate/cast text is normalized.
    'catalogTopologySha256', (SELECT encode(sha256(convert_to(string_agg(structure,
        chr(10) ORDER BY structure), 'UTF8')), 'hex') FROM (
        SELECT (CASE WHEN item::jsonb->>0 IN ('view', 'constraint', 'index', 'trigger')
            THEN item::jsonb - (jsonb_array_length(item::jsonb) - 1)
            ELSE item::jsonb END)::text structure FROM catalog_items) topology),
    'catalogComponents', (SELECT jsonb_object_agg(kind, digest) FROM (
        SELECT item::jsonb->>0 kind,
            encode(sha256(convert_to(string_agg(item, chr(10) ORDER BY item), 'UTF8')), 'hex') digest
        FROM catalog_items GROUP BY item::jsonb->>0) components),
    -- Opt-in, local synthetic DDL diagnostics only; never copied into the report.
    'catalogDiagnostics', CASE WHEN current_setting('educore.restore_diagnostics', true) = 'on'
        THEN (SELECT jsonb_agg(item::jsonb ORDER BY item) FROM catalog_items) ELSE NULL END,
    'tables', (SELECT jsonb_agg(jsonb_build_object('schema', nspname, 'name', relname,
        'rows', rows, 'sha256', digest) ORDER BY nspname, relname) FROM table_checks),
    'sequences', coalesce((SELECT jsonb_agg(jsonb_build_object('schema', schemaname,
        'name', sequencename, 'type', data_type::text, 'start', start_value, 'minimum', min_value,
        'maximum', max_value, 'increment', increment_by, 'cycle', cycle, 'cache', cache_size,
        'lastValue', restored_last_value, 'called', is_called) ORDER BY schemaname, sequencename)
        FROM sequence_checks), '[]'::jsonb)
);
