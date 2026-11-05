package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.EduCoreApiApplication;
import com.educore.api.files.MalwareScanner;
import com.educore.api.files.ObjectStorage;
import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.recovery.PairedRecoveryFixture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Real API + real PostgreSQL dump/restore + real private bytes. Explicit disposable profile only. */
class OfficialDocumentRecoveryIT {
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ORG = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SCHOOL = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID CAMPUS = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID MAKER = UUID.randomUUID(), CHECKER = UUID.randomUUID(), PARENT = UUID.randomUUID();
    private static final UUID OTHER_PARENT = UUID.randomUUID(), CHILD = UUID.randomUUID(), OTHER_CHILD = UUID.randomUUID();
    private static final UUID FOREIGN_PARENT = UUID.randomUUID(), FOREIGN_ORG = UUID.randomUUID();
    private static final UUID ENROLMENT = UUID.randomUUID(), FACTOR = UUID.randomUUID();
    private static final String SOURCE = PairedRecoveryFixture.database("source", RUN);
    private static final String RESTORE = PairedRecoveryFixture.database("restore", RUN);
    private static final String ROLE = "synthetic_paired_runtime_" + RUN.toString().replace("-", "");
    private static final String LOGIN_PASSWORD = Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));
    private static final JsonMapper JSON = new JsonMapper();
    private static final List<Blob> BLOBS = new ArrayList<>();
    private static final Map<String, String> FROZEN = new LinkedHashMap<>();
    @TempDir static Path scratch;
    private static String baseUrl, ownerUser, ownerPassword, container, serverFolder;
    private static JdbcTemplate control, restoredOwner;
    private static ConfigurableApplicationContext sourceApp, restoredApp;
    private static Api maker, checker, parent, other, foreign;
    private static UUID template, activeDocument, cancelledDocument;
    private static Map<String, Object> originalIssue;
    private static ObjectStorage storage;
    private static Properties custody;
    private static boolean sourceCreated, restoreCreated, roleCreated, serverFolderCreated;

    @BeforeAll static void restoreAnActualSyntheticDocumentSnapshot() throws Exception {
        PairedRecoveryFixture.validate(System.getenv());
        baseUrl = required("TEST_PAIRED_DATABASE_URL");
        ownerUser = required("TEST_PAIRED_DATABASE_USER");
        ownerPassword = required("TEST_PAIRED_DATABASE_PASSWORD");
        container = required("TEST_PAIRED_POSTGRES_CONTAINER");
        control = owner(baseUrl);
        // This base is a newly provisioned, empty test container, never the running acceptance database.
        assertThat(control.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'", Integer.class)).isZero();
        assertThat(control.queryForObject("SELECT current_setting('server_version_num')::int/10000", Integer.class)).isEqualTo(18);
        assertThat(control.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        String runtimePassword = Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));
        try (var connection = DriverManager.getConnection(baseUrl, ownerUser, ownerPassword);
                var sql = connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',?,?)")) {
            sql.setString(1, ROLE); sql.setString(2, runtimePassword);
            try (var value = sql.executeQuery(); var command = connection.createStatement()) {
                value.next(); command.execute(value.getString(1)); roleCreated = true;
            }
        }
        PairedRecoveryFixture.requireDatabase(SOURCE, "source", RUN);
        control.execute("CREATE DATABASE " + SOURCE + " WITH TEMPLATE template0"); sourceCreated = true;
        DatabaseMigrationTool.migrate(Map.of("MIGRATION_DATABASE_URL", url(SOURCE), "MIGRATION_DATABASE_USER", ownerUser,
                "MIGRATION_DATABASE_PASSWORD", ownerPassword, "DATABASE_USER", ROLE));
        JdbcTemplate sourceOwner = owner(url(SOURCE));
        seed(sourceOwner);
        custody = configuration(runtimePassword);
        sourceApp = application(url(SOURCE), custody);
        ProductionDatabaseSecurity.verify(sourceApp.getBean(JdbcTemplate.class));
        maker = login(sourceApp, MAKER); checker = login(sourceApp, CHECKER);
        parent = login(sourceApp, PARENT); other = login(sourceApp, OTHER_PARENT);
        var templateBody = new LinkedHashMap<String, Object>();
        templateBody.put("schoolId", SCHOOL); templateBody.put("templateCode", "SYN_RECOVERY");
        templateBody.put("revisionNumber", 1); templateBody.put("documentKind", "BONAFIDE_CERTIFICATE");
        templateBody.put("displayName", "Synthetic recovery certificate"); templateBody.put("locale", "en-IN");
        templateBody.put("title", "Synthetic test certificate — not a school-issued record");
        templateBody.put("footerText", "Isolated recovery acceptance only. Not valid for any school or person.");
        templateBody.put("signatoryRole", "Synthetic test signatory"); templateBody.put("accentColour", "#23483F");
        templateBody.put("approvalRequired", true); templateBody.put("labelOverrides", Map.of());
        templateBody.put("reason", "Synthetic recovery template fixture");
        template = id(maker.post("/api/v1/documents/templates", templateBody, null, 201));
        maker.post("/api/v1/documents/templates/" + template + "/publish", transition(0), null, 403);
        checker.post("/api/v1/documents/templates/" + template + "/publish", transition(0), null, 200);
        originalIssue = issue(null);
        activeDocument = id(maker.post("/api/v1/documents/issues", originalIssue, "synthetic-paired-original-" + RUN, 201));
        // Pending facts are not downloadable; self-approval remains denied.
        assertThat(parent.get(family(activeDocument)).statusCode()).isEqualTo(404);
        maker.post("/api/v1/documents/" + activeDocument + "/decision", decision(), null, 403);
        checker.post("/api/v1/documents/" + activeDocument + "/decision", decision(), null, 200);
        cancelledDocument = id(maker.post("/api/v1/documents/issues", issue(null), "synthetic-paired-cancelled-" + RUN, 201));
        checker.post("/api/v1/documents/" + cancelledDocument + "/decision", decision(), null, 200);
        maker.post("/api/v1/documents/" + cancelledDocument + "/cancel", transition(1), null, 200);
        storage = sourceApp.getBean(ObjectStorage.class);
        for (UUID document : List.of(activeDocument, cancelledDocument)) {
            Path objectCopy = privateFile("object-" + document + ".pdf");
            Blob blob = sourceOwner.queryForObject("SELECT storage_key,pdf_size_bytes,pdf_sha256 FROM official_documents WHERE id=?",
                    (row, index) -> new Blob(row.getString(1), row.getLong(2), row.getString(3), objectCopy), document);
            var output = new ByteArrayOutputStream(); storage.copyVerifiedTo(blob.key(), blob.size(), blob.sha(), output);
            Files.write(blob.path(), output.toByteArray()); BLOBS.add(blob);
        }
        assertPdf(parent.get(family(activeDocument)), BLOBS.getFirst());
        byte[] proof = new SecureRandom().generateSeed(20);
        Path proofFile = privateFile("synthetic-key-proof.bin"); Files.write(proofFile, proof);
        var encrypted = sourceApp.getBean(SensitiveValueCipher.class).encrypt(proof, PARENT + ":" + FACTOR);
        sourceOwner.update("INSERT INTO mfa_totp_factors(id,user_id,label,secret_ciphertext,secret_nonce,status,created_at) VALUES(?,?,'Synthetic recovery key proof',?,?,'PENDING',now())",
                FACTOR, PARENT, encrypted.ciphertext(), encrypted.nonce());
        Arrays.fill(proof, (byte) 0);
        try (var output = Files.newOutputStream(privateFile("runtime-custody.properties"))) { custody.store(output, "Synthetic private recovery configuration; never an artifact"); }
        sourceApp.close(); sourceApp = null;
        for (String table : List.of("official_documents", "official_document_templates", "official_document_history", "official_certificate_counters", "mfa_totp_factors", "audit_events", "flyway_schema_history")) {
            FROZEN.put(table, tableDigest(sourceOwner, table));
        }
        serverFolder = "/tmp/educore-paired-" + RUN.toString().replace("-", "");
        command(List.of("install", "-d", "-m", "700", serverFolder)); serverFolderCreated = true;
        command(List.of("pg_dump", "--host=127.0.0.1", "--port=5432", "--username=" + ownerUser,
                "--no-password", "--dbname=" + SOURCE, "--format=custom", "--lock-wait-timeout=5s", "--file=" + serverFolder + "/snapshot.dump"));
        // No source writers remain. Copy is a real full pg_dump, not hand-written INSERT replay.
        PairedRecoveryFixture.requireDatabase(RESTORE, "restore", RUN);
        control.execute("CREATE DATABASE " + RESTORE + " WITH TEMPLATE template0"); restoreCreated = true;
        command(List.of("pg_restore", "--host=127.0.0.1", "--port=5432", "--username=" + ownerUser,
                "--no-password", "--dbname=" + RESTORE, "--single-transaction", "--exit-on-error", serverFolder + "/snapshot.dump"));
        restoredOwner = owner(url(RESTORE));
        for (var frozen : FROZEN.entrySet()) assertThat(frozen.getValue().equals(tableDigest(restoredOwner, frozen.getKey()))).isTrue();
        // Start a fresh API only from the privately retained runtime configuration.
        custody = new Properties();
        try (var input = Files.newInputStream(scratch.resolve("runtime-custody.properties"))) { custody.load(input); }
        restoredApp = application(url(RESTORE), custody);
        ProductionDatabaseSecurity.verify(restoredApp.getBean(JdbcTemplate.class));
        storage = restoredApp.getBean(ObjectStorage.class);
        for (Blob blob : BLOBS) storage.delete(blob.key());
        maker = login(restoredApp, MAKER); checker = login(restoredApp, CHECKER);
        parent = login(restoredApp, PARENT); other = login(restoredApp, OTHER_PARENT);
        foreign = login(restoredApp, FOREIGN_PARENT);
        assertUnavailableBytes(parent, family(activeDocument));
        for (Blob blob : BLOBS) restoreObject(blob);
    }

    @Test void realPairedRestoreRecoversPrivatePdfAndPreservesAuthorityHistoryAndRetries() throws Exception {
        Blob active = BLOBS.getFirst();
        assertPdf(parent.get(family(activeDocument)), active);
        assertPdf(maker.get("/api/v1/documents/" + activeDocument + "/download?schoolId=" + SCHOOL), active);
        assertThat(new Api(restoredApp).get(family(activeDocument)).statusCode()).isEqualTo(401);
        assertThat(other.get(family(activeDocument)).statusCode()).isEqualTo(404);
        assertThat(foreign.get(family(activeDocument)).statusCode()).isEqualTo(404);
        assertThat(parent.get("/api/v1/family/children/" + OTHER_CHILD + "/documents/" + activeDocument + "/download").statusCode()).isEqualTo(404);
        assertThat(maker.get("/api/v1/documents/" + activeDocument + "/download?schoolId=" + UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(parent.get(family(cancelledDocument)).statusCode()).isEqualTo(404);
        // Authorized management retains controlled historical access; family does not.
        assertPdf(maker.get("/api/v1/documents/" + cancelledDocument + "/download?schoolId=" + SCHOOL), BLOBS.get(1));
        assertThat(id(maker.post("/api/v1/documents/issues", originalIssue, "synthetic-paired-original-" + RUN, 201))).isEqualTo(activeDocument);
        assertThat(restoredOwner.queryForObject("SELECT count(*) FROM official_documents", Integer.class)).isEqualTo(2);
        assertThat(restoredOwner.queryForObject("SELECT last_number FROM official_certificate_counters WHERE school_id=?", Long.class, SCHOOL)).isEqualTo(2L);
        for (String table : List.of("official_documents", "official_document_templates", "official_document_history", "official_certificate_counters", "mfa_totp_factors", "flyway_schema_history")) {
            assertThat(FROZEN.get(table).equals(tableDigest(restoredOwner, table))).isTrue();
        }
        assertThatThrownBy(() -> restoredOwner.update("UPDATE official_document_history SET reason='Synthetic forbidden edit' WHERE official_document_id=?", activeDocument))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID retainedAudit = restoredOwner.queryForObject("SELECT id FROM audit_events ORDER BY occurred_at,id LIMIT 1", UUID.class);
        assertThatThrownBy(() -> restoredOwner.update("DELETE FROM audit_events WHERE id=?", retainedAudit))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void realPairedRestoreRequiresTheRecoveredKeyAndRejectsMissingWrongOrReboundKeys() throws Exception {
        var encrypted = restoredOwner.queryForObject("SELECT secret_ciphertext,secret_nonce FROM mfa_totp_factors WHERE id=?",
                (row, index) -> new SensitiveValueCipher.EncryptedValue(row.getString(1), row.getString(2)), FACTOR);
        byte[] proof = Files.readAllBytes(scratch.resolve("synthetic-key-proof.bin"));
        var actual = restoredApp.getBean(SensitiveValueCipher.class);
        assertThat(MessageDigest.isEqual(actual.decrypt(encrypted, PARENT + ":" + FACTOR), proof)).isTrue();
        assertThatThrownBy(() -> cipherWithKey("").decrypt(encrypted, PARENT + ":" + FACTOR)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cipherWithKey(Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32)))
                .decrypt(encrypted, PARENT + ":" + FACTOR)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> actual.decrypt(encrypted, OTHER_PARENT + ":" + FACTOR)).isInstanceOf(IllegalStateException.class);
        Arrays.fill(proof, (byte) 0);
    }

    @Test void realPairedRestoreRejectsMissingAndSubstitutedObjectsWithoutRewritingMetadata() throws Exception {
        Blob blob = BLOBS.getFirst();
        try {
            byte[] changed = Files.readAllBytes(blob.path()); changed[0] ^= 1;
            Path substituted = privateFile("synthetic-substituted-object.pdf"); Files.write(substituted, changed);
            storage.put(blob.key(), substituted, "application/pdf", changed.length, sha(changed));
            assertUnavailableBytes(parent, family(activeDocument));
            storage.delete(blob.key());
            assertUnavailableBytes(parent, family(activeDocument));
            assertThat(FROZEN.get("official_documents").equals(tableDigest(restoredOwner, "official_documents"))).isTrue();
        } finally { restoreObject(blob); }
        assertPdf(parent.get(family(activeDocument)), blob);
    }

    private static SensitiveValueCipher cipherWithKey(String key) {
        ApplicationProperties p = restoredApp.getBean(ApplicationProperties.class);
        var s = p.security();
        var security = new ApplicationProperties.Security(s.sessionCookieName(), s.cookieSecure(), s.sessionTtl(),
                s.rememberedSessionTtl(), s.idleTimeout(), s.cookieDomain(), key, s.mfaChallengeTtl(),
                s.mfaEnrollmentTtl(), s.guardianInvitationTtl(), s.totpIssuer());
        return new SensitiveValueCipher(new ApplicationProperties(p.publicUrl(), p.allowedOrigins(),
                p.publicOrganisationId(), p.publicSchoolId(), security, p.bootstrap()), new SecureRandom());
    }

    private static void restoreObject(Blob blob) throws Exception {
        byte[] bytes = Files.readAllBytes(blob.path());
        assertThat(bytes.length == blob.size() && sha(bytes).equals(blob.sha())).isTrue();
        assertThat(restoredApp.getBean(MalwareScanner.class).scan(blob.path()).clean()).isTrue();
        storage.put(blob.key(), blob.path(), "application/pdf", blob.size(), blob.sha());
    }

    private static void assertPdf(HttpResponse<byte[]> response, Blob blob) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store", "private");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(response.headers().firstValue("Vary").orElse("").toLowerCase()).contains("cookie");
        assertThat(response.body().length == blob.size() && sha(response.body()).equals(blob.sha())).isTrue();
    }

    private static void assertUnavailableBytes(Api api, String path) throws Exception {
        var response = api.client.send(api.request(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isIn(500, 503);
        var seen = new ByteArrayOutputStream();
        try (var stream = response.body()) {
            byte[] chunk = new byte[1024]; int count;
            try { while ((count = stream.read(chunk)) != -1) { seen.write(chunk, 0, count); if (seen.size() > 8192) throw new IllegalStateException("Unbounded recovery failure response"); } }
            catch (IOException incompleteFailure) { /* A truncated error may not release any PDF bytes. */ }
        }
        assertThat(new String(seen.toByteArray(), StandardCharsets.ISO_8859_1).contains("%PDF-")).isFalse();
    }

    private static Map<String, Object> issue(UUID supersedes) {
        var body = new LinkedHashMap<String, Object>(); body.put("schoolId", SCHOOL); body.put("templateId", template);
        body.put("sourceType", "STUDENT_ENROLMENT"); body.put("sourceId", ENROLMENT);
        if (supersedes != null) body.put("supersedesDocumentId", supersedes);
        body.put("reason", "Synthetic recovery document request"); return body;
    }
    private static Map<String, Object> transition(long version) { return Map.of("schoolId", SCHOOL, "expectedVersion", version, "reason", "Synthetic independent recovery decision"); }
    private static Map<String, Object> decision() { return Map.of("schoolId", SCHOOL, "expectedVersion", 0, "decision", "APPROVE", "reason", "Synthetic independent recovery decision"); }
    private static UUID id(JsonNode value) { return UUID.fromString(value.path("id").asText()); }
    private static String family(UUID document) { return "/api/v1/family/children/" + CHILD + "/documents/" + document + "/download"; }
    private static String required(String key) { return PairedRecoveryFixture.required(System.getenv(), key); }
    private static String url(String database) { return baseUrl.replace("/educore_paired_integration", "/" + database); }
    private static JdbcTemplate owner(String url) { return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String tableDigest(JdbcTemplate jdbc, String table) {
        if (!List.of("official_documents", "official_document_templates", "official_document_history", "official_certificate_counters", "mfa_totp_factors", "audit_events", "flyway_schema_history").contains(table)) throw new IllegalArgumentException("Unsupported fixture evidence table");
        return jdbc.queryForObject("SELECT encode(sha256(convert_to(coalesce(string_agg(row_value,E'\\n' ORDER BY row_value),''),'UTF8')),'hex') FROM (SELECT to_jsonb(t)::text AS row_value FROM " + table + " t) source_rows", String.class);
    }
    private static Path privateFile(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9.-]+")) throw new IllegalArgumentException("Invalid generated fixture file");
        Path path = scratch.resolve(name);
        if (Files.exists(path)) throw new IllegalStateException("Refusing to overwrite a custody file");
        if (Files.getFileStore(scratch).supportsFileAttributeView("posix")) return Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.createFile(path);
    }

    private static Properties configuration(String runtimePassword) {
        var p = new Properties(); p.setProperty("spring.datasource.username", ROLE); p.setProperty("spring.datasource.password", runtimePassword);
        p.setProperty("app.security.encryption-key", Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32)));
        p.setProperty("app.files.endpoint", required("TEST_S3_ENDPOINT")); p.setProperty("app.files.bucket", required("TEST_S3_BUCKET"));
        p.setProperty("app.files.access-key", required("PRIVATE_FILES_TEST_ACCESS_KEY")); p.setProperty("app.files.secret-key", required("PRIVATE_FILES_TEST_SECRET_KEY"));
        p.setProperty("app.files.region", "us-east-1"); p.setProperty("app.files.auto-create-bucket", "false");
        p.setProperty("app.files.clamav.host", required("TEST_CLAMAV_HOST")); p.setProperty("app.files.clamav.port", required("TEST_CLAMAV_PORT")); return p;
    }
    private static ConfigurableApplicationContext application(String databaseUrl, Properties p) {
        var bindings = new HashMap<String, Object>(); p.forEach((key, value) -> bindings.put(key.toString(), value));
        bindings.put("spring.datasource.url", databaseUrl); bindings.put("spring.flyway.enabled", false);
        bindings.put("spring.datasource.hikari.maximum-pool-size", 2); bindings.put("spring.datasource.hikari.minimum-idle", 1);
        bindings.put("server.address", "127.0.0.1"); bindings.put("server.port", 0);
        bindings.put("app.public-url", "https://synthetic.example.test"); bindings.put("app.allowed-origins", "https://synthetic.example.test");
        bindings.put("app.bootstrap.enabled", false); bindings.put("app.push.registration-enabled", false);
        bindings.put("app.push.delivery.enabled", false); bindings.put("app.push.delivery.worker-enabled", false);
        for (String key : List.of("app.guardian-invitations.expiry.enabled", "app.calendar.notifications.enabled", "app.learning.notifications.enabled", "app.ptm.reminders.enabled", "app.parent-requests.notifications.enabled", "app.student-leave.notifications.enabled")) bindings.put(key, false);
        return new SpringApplicationBuilder(EduCoreApiApplication.class).profiles("test").initializers(context ->
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-synthetic-recovery", bindings))).run();
    }
    private static Api login(ConfigurableApplicationContext app, UUID user) throws Exception {
        var api = new Api(app);
        api.post("/api/v1/auth/sessions", Map.of("identifier", loginName(user), "password", LOGIN_PASSWORD, "remember", false), null, 200);
        return api;
    }
    private static String loginName(UUID user) { return "synthetic-paired-" + user + "@example.test"; }
    private static void seed(JdbcTemplate jdbc) {
        String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(LOGIN_PASSWORD);
        Map<UUID, UUID> members = new HashMap<>();
        jdbc.update("INSERT INTO organisations SELECT (jsonb_populate_record(NULL::organisations,to_jsonb(o)||jsonb_build_object('id',?::uuid,'slug',?,'legal_name','Synthetic foreign recovery tenant','display_name','Synthetic foreign recovery tenant'))).* FROM organisations o WHERE id=?",
                FOREIGN_ORG, "synthetic-paired-" + RUN, ORG);
        for (UUID user : List.of(MAKER, CHECKER, PARENT, OTHER_PARENT, FOREIGN_PARENT)) {
            UUID membership = UUID.randomUUID(); members.put(user, membership);
            jdbc.update("INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status,created_at,updated_at) VALUES(?,?,?,'Synthetic recovery account',?,'ACTIVE',now(),now())", user, loginName(user), loginName(user), hash);
            jdbc.update("INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',now(),now(),now())", membership, user,
                    user.equals(FOREIGN_PARENT) ? FOREIGN_ORG : ORG, user.equals(FOREIGN_PARENT) ? null : SCHOOL, user.equals(FOREIGN_PARENT) ? null : CAMPUS);
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?::uuid)", membership, user.equals(MAKER) || user.equals(CHECKER) ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000000006");
        }
        UUID session = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic recovery session',?,?,'ACTIVE',now(),now())", session, ORG, SCHOOL, today.minusDays(30), today.plusDays(30));
        for (UUID child : List.of(CHILD, OTHER_CHILD)) {
            UUID origin = UUID.randomUUID(), guardian = UUID.randomUUID(); UUID user = child.equals(CHILD) ? PARENT : OTHER_PARENT;
            jdbc.update("INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,target_grade_code,applicant_legal_name,birth_date,gender_code,status,created_by_membership_id,created_at,updated_at) VALUES(?,?,?, ?,repeat('1',64),'Synthetic recovery session','VI','Synthetic recovery learner',DATE '2015-01-01','NOT_STATED','DRAFT',?,now(),now())", origin, ORG, SCHOOL, "synthetic-paired-" + child, members.get(MAKER));
            jdbc.update("INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at) VALUES(?,?,?,?,'Synthetic recovery learner',DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now())", child, ORG, SCHOOL, origin);
            jdbc.update("INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,grade_code,admission_number,status,placement_status,starts_on,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'VI',?,'ACTIVE','PENDING',?,now(),now())", child.equals(CHILD) ? ENROLMENT : UUID.randomUUID(), ORG, SCHOOL, CAMPUS, session, child, origin, "SYN-REC-" + child, today.minusDays(30));
            jdbc.update("INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,user_account_id,created_at,updated_at) VALUES(?,?,?,'Synthetic recovery guardian',?,'ACTIVE',?,now(),now())", guardian, ORG, SCHOOL, loginName(user), user);
            jdbc.update("INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,primary_contact,pickup_authorized,portal_access_status,created_at,updated_at) VALUES(?,?,?,?,'PARENT_GUARDIAN',true,false,'ACTIVE',now(),now())", child, guardian, ORG, SCHOOL);
        }
    }

    private static void command(List<String> arguments) throws Exception {
        var all = new ArrayList<>(List.of("docker", "exec", "--env", "PGPASSWORD", "--env", "PGHOSTADDR", "--env", "PGCONNECT_TIMEOUT", container)); all.addAll(arguments);
        var process = new ProcessBuilder(all); process.environment().put("PGPASSWORD", ownerPassword);
        process.environment().put("PGHOSTADDR", "127.0.0.1"); process.environment().put("PGCONNECT_TIMEOUT", "5");
        process.redirectOutput(ProcessBuilder.Redirect.DISCARD); process.redirectError(ProcessBuilder.Redirect.DISCARD);
        var child = process.start();
        if (!child.waitFor(120, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("Bounded isolated recovery utility timed out"); }
        if (child.exitValue() != 0) throw new IllegalStateException("Isolated recovery utility failed; no success evidence");
    }

    @AfterAll static void cleanupOwnedFixture() throws Exception {
        // Independent limited client also works if setup failed after closing the source API.
        // Attempt every owned cleanup stage, fail the gate on failure, and never print credentials.
        var failures = new ArrayList<String>();
        if (!BLOBS.isEmpty()) cleanup(failures, "private fixture objects", () -> {
            try (var client = S3Client.builder().endpointOverride(URI.create(required("TEST_S3_ENDPOINT")))
                    .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(required("PRIVATE_FILES_TEST_ACCESS_KEY"), required("PRIVATE_FILES_TEST_SECRET_KEY"))))
                    .serviceConfiguration(config -> config.pathStyleAccessEnabled(true))
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30)))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(30)))
                    .build()) {
                for (Blob blob : BLOBS) cleanup(failures, "owned private object", () ->
                        client.deleteObject(request -> request.bucket(required("TEST_S3_BUCKET")).key(blob.key())));
            }
        });
        if (sourceApp != null) cleanup(failures, "source API", () -> sourceApp.close());
        if (restoredApp != null) cleanup(failures, "restored API", () -> restoredApp.close());
        if (control != null) {
            if (restoreCreated) cleanup(failures, "restored fixture database", () -> { PairedRecoveryFixture.requireDatabase(RESTORE, "restore", RUN); control.execute("DROP DATABASE " + RESTORE); });
            if (sourceCreated) cleanup(failures, "source fixture database", () -> { PairedRecoveryFixture.requireDatabase(SOURCE, "source", RUN); control.execute("DROP DATABASE " + SOURCE); });
            if (roleCreated) cleanup(failures, "fixture runtime role", () -> control.execute("DROP ROLE " + ROLE));
        }
        if (serverFolderCreated) {
            cleanup(failures, "private fixture dump", () -> command(List.of("rm", "-f", "--", serverFolder + "/snapshot.dump")));
            cleanup(failures, "owned snapshot folder", () -> command(List.of("rmdir", "--", serverFolder)));
        }
        if (!failures.isEmpty()) throw new IllegalStateException("Isolated recovery cleanup failed: " + String.join(", ", failures));
    }
    @FunctionalInterface private interface Cleanup { void run() throws Exception; }
    private static void cleanup(List<String> failures, String stage, Cleanup action) {
        try { action.run(); } catch (Exception failure) { failures.add(stage); }
    }
    private record Blob(String key, long size, String sha, Path path) {}
    private static final class Api {
        private final URI root;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        Api(ConfigurableApplicationContext app) { root = URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort()); }
        HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(30)); }
        HttpResponse<byte[]> get(String path) throws Exception { return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray()); }
        JsonNode post(String path, Map<String, ?> value, String key, int expectedStatus) throws Exception {
            var request = request(path).header("Content-Type", "application/json");
            if (!path.equals("/api/v1/auth/sessions")) {
                assertThat(get("/api/v1/auth/sessions/current").statusCode()).isEqualTo(200);
                String csrf = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("educore_csrf")).findFirst().orElseThrow().getValue();
                request.header("X-CSRF-TOKEN", URLDecoder.decode(csrf, StandardCharsets.UTF_8));
            }
            if (key != null) request.header("Idempotency-Key", key);
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(value))).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as("Real synthetic API status, never response/credential contents").isEqualTo(expectedStatus);
            JsonNode json = JSON.readTree(response.body()); return json.has("data") ? json.path("data") : json;
        }
    }
}
