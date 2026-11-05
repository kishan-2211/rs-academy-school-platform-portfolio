package com.educore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.educore.api.admissions.AdmissionApplicationService;
import com.educore.api.admissions.EnquiryService;
import com.educore.api.academics.AcademicRolloverController.RolloverExecutionRequest;
import com.educore.api.academics.AcademicRolloverController.RolloverPreviewRequest;
import com.educore.api.academics.AcademicRolloverController.StudentRolloverDecisionRequest;
import com.educore.api.academics.AcademicRolloverService;
import com.educore.api.academics.AcademicService;
import com.educore.api.accounting.AccountingController.AccountRequest;
import com.educore.api.accounting.AccountingController.FinancialYearRequest;
import com.educore.api.accounting.AccountingController.JournalLineRequest;
import com.educore.api.accounting.AccountingController.JournalRequest;
import com.educore.api.accounting.AccountingController.ReversalRequest;
import com.educore.api.accounting.AccountingService;
import com.educore.api.documents.OfficialDocumentController.DecisionRequest;
import com.educore.api.documents.OfficialDocumentController.IssueRequest;
import com.educore.api.documents.OfficialDocumentController.TemplateRequest;
import com.educore.api.documents.OfficialDocumentController.TransitionRequest;
import com.educore.api.documents.OfficialDocumentObjectStore;
import com.educore.api.documents.OfficialDocumentService;
import com.educore.api.finance.FinanceController.DayCloseDecisionRequest;
import com.educore.api.finance.FinanceController.DayCloseRequest;
import com.educore.api.finance.FinanceController.FeeHeadRequest;
import com.educore.api.finance.FinanceController.FeePlanRequest;
import com.educore.api.finance.FinanceController.PaymentRequest;
import com.educore.api.finance.FinanceController.PaymentReversalRequest;
import com.educore.api.finance.FinanceController.PlanActivationRequest;
import com.educore.api.finance.FinanceController.PlanAssignmentRequest;
import com.educore.api.finance.FinanceController.PlanItemRequest;
import com.educore.api.finance.FinanceController.TenderDeclaration;
import com.educore.api.finance.FinanceService;
import com.educore.api.finance.FeeAccountingController.ConfigurationDecisionRequest;
import com.educore.api.finance.FeeAccountingController.ConfigurationRequest;
import com.educore.api.finance.FeeAccountingController.HeadMappingRequest;
import com.educore.api.finance.FeeAccountingController.PostingRequest;
import com.educore.api.finance.FeeAccountingController.TenderMappingRequest;
import com.educore.api.finance.FeeAccountingService;
import com.educore.api.family.FamilyService;
import com.educore.api.communication.PushNotificationController.RegisterPushSubscriptionRequest;
import com.educore.api.communication.PushNotificationController.RevokePushSubscriptionRequest;
import com.educore.api.communication.PushNotificationService;
import com.educore.api.communication.PushDeliveryQueue;
import com.educore.api.communication.PushTransport;
import com.educore.api.tenancy.TenantRequestContext;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.launch.LaunchReadinessController.CreateItemRequest;
import com.educore.api.launch.LaunchReadinessController.ReviewDecisionRequest;
import com.educore.api.launch.LaunchReadinessController.ReviseItemRequest;
import com.educore.api.launch.LaunchReadinessController.SubmitReviewRequest;
import com.educore.api.launch.LaunchReadinessService;
import com.educore.api.payroll.PayrollController;
import com.educore.api.payroll.PayrollAccountingController;
import com.educore.api.payroll.PayrollAccountingService;
import com.educore.api.payroll.PayrollAdvanceController;
import com.educore.api.payroll.PayrollAdvanceService;
import com.educore.api.payroll.PayrollAdvanceStatementService;
import com.educore.api.payroll.PayrollPayslipController;
import com.educore.api.payroll.PayrollPayslipObjectStore;
import com.educore.api.payroll.PayrollPayslipService;
import com.educore.api.payroll.PayrollRunController;
import com.educore.api.payroll.PayrollRunService;
import com.educore.api.payroll.PayrollService;
import com.educore.api.parentrequests.ParentRequestService;
import com.educore.api.reports.ReportsService;
import com.educore.api.schoolcalendar.SchoolCalendarController.CreateNoticeRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.CreatePolicyRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.ReviseNoticeRequest;
import com.educore.api.schoolcalendar.SchoolCalendarController.RevisePolicyRequest;
import com.educore.api.schoolcalendar.SchoolCalendarNotificationProcessor;
import com.educore.api.schoolcalendar.SchoolCalendarService;
import com.educore.api.studentportal.StudentPortalService;
import com.educore.api.workforce.WorkforceController.LeaveBalanceRequest;
import com.educore.api.workforce.WorkforceController.LeaveRequest;
import com.educore.api.workforce.WorkforceController.LeaveTypeRequest;
import com.educore.api.workforce.WorkforceController.MarkInput;
import com.educore.api.workforce.WorkforceController.OpenRegisterRequest;
import com.educore.api.workforce.WorkforceController.PolicyRequest;
import com.educore.api.workforce.WorkforceController.RecordMarksRequest;
import com.educore.api.workforce.WorkforceController.VersionedReasonRequest;
import com.educore.api.workforce.WorkforceService;
import com.educore.api.schoolconfig.SchoolConfigurationController.CampusRequest;
import com.educore.api.schoolconfig.SchoolConfigurationController.SchoolProfileRequest;
import com.educore.api.schoolconfig.SchoolConfigurationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class PostgresIntegrationTest {

    @Autowired private tools.jackson.databind.ObjectMapper launchUpgradeMapper;

    @Autowired
    private com.educore.api.identity.AccountPreferencesService accountPreferences;

    @Test
    @Transactional
    void accountLanguageMigrationPreservesUnsetAndScopesIdempotentChangesToTheActor() {
        UUID firstId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        for (UUID id : List.of(firstId, otherId)) {
            jdbc.update("""
                    INSERT INTO user_accounts(id,login_identifier_normalized,display_name,password_hash,status)
                    VALUES(?,?,'Synthetic language test','not-a-login-hash','ACTIVE')
                    """, id, "synthetic-language-" + id);
        }
        var actor = new AuthenticatedUser(firstId, UUID.randomUUID(), "synthetic", null, "Synthetic", false,
                UUID.randomUUID(), Set.of(), Set.of(), List.of());
        var other = new AuthenticatedUser(otherId, UUID.randomUUID(), "synthetic-other", null, "Synthetic", false,
                UUID.randomUUID(), Set.of(), Set.of(), List.of());
        assertThat(accountPreferences.read(actor).preferredLanguage()).isNull();
        accountPreferences.update(actor, "hi");
        accountPreferences.update(actor, "hi");
        assertThat(accountPreferences.read(actor).preferredLanguage()).isEqualTo("hi");
        assertThat(accountPreferences.read(other).preferredLanguage()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_user_id=? AND action='identity.language.changed'", Integer.class, firstId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='user_accounts_preferred_language_valid'", Integer.class)).isEqualTo(1);
        accountPreferences.update(other, "en");
        assertThat(accountPreferences.read(actor).preferredLanguage()).isEqualTo("hi");
        assertThat(accountPreferences.read(other).preferredLanguage()).isEqualTo("en");
    }

    private static PostgreSQLContainer<?> managedPostgres;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("TEST_DATABASE_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!externalUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalStateException("TEST_DATABASE_URL must be a PostgreSQL JDBC URL.");
            }
            String externalUser = requiredEnvironment("TEST_DATABASE_USER");
            String externalPassword = requiredEnvironment("TEST_DATABASE_PASSWORD");
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> externalUser);
            registry.add("spring.datasource.password", () -> externalPassword);
        } else {
            managedPostgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
            managedPostgres.start();
            registry.add("spring.datasource.url", managedPostgres::getJdbcUrl);
            registry.add("spring.datasource.username", managedPostgres::getUsername);
            registry.add("spring.datasource.password", managedPostgres::getPassword);
        }
        registry.add("app.bootstrap.enabled", () -> false);
        registry.add("app.guardian-invitations.expiry.enabled", () -> false);
        registry.add("app.calendar.notifications.enabled", () -> false);
        registry.add("app.ptm.reminders.enabled", () -> false);
        registry.add("app.parent-requests.notifications.enabled", () -> false);
        registry.add("app.learning.notifications.enabled", () -> false);
        registry.add("app.student-leave.notifications.enabled", () -> false);
        registry.add("app.security.encryption-key", PostgresIntegrationTest::syntheticEncryptionKey);
        registry.add("app.push.registration-enabled", () -> true);
        registry.add("app.push.application-server-key", PostgresIntegrationTest::syntheticP256dh);
    }

    @AfterAll
    static void stopManagedPostgres() {
        if (managedPostgres != null) {
            managedPostgres.stop();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when TEST_DATABASE_URL is configured.");
        }
        return value;
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired private com.educore.api.people.PeopleService publicRevisionPeople;

    @Test @Transactional
    void rejectedFirstStaffPublicRevisionAppendsCorrectionWithoutEditingRejectedEvidence() throws Exception {
        var f = staffPublicRevisionFixture("first-correction", null);
        try (var ignored = TenantRequestContext.open(null, f.organisationId())) {
            UUID rejected = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 1, null, "Synthetic rejected first snapshot", null)).id();
            decideStaffPublicRevision(f, rejected, "REJECTED", "Synthetic first snapshot needs correction");
            String rejectedEvidence = staffPublicRevisionEvidence(rejected);
            assertThat(staffPublicResponse(f).staff()).isEmpty();

            var correction = staffPublicRevisionRequest(f, f.staffId(), 2, rejected,
                    "Synthetic corrected first snapshot", null);
            UUID corrected = publicRevisionPeople.createPublicProfile(f.maker(), correction).id();
            assertThat(staffPublicRevisionEvidence(rejected)).isEqualTo(rejectedEvidence);
            assertThat(staffPublicResponse(f).staff()).isEmpty();
            assertDatabaseRejected(() -> publicRevisionPeople.createPublicProfile(f.maker(), correction),
                    "conflicting people record");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_public_profile_revisions WHERE staff_profile_id=?", Integer.class, f.staffId())).isEqualTo(2);

            assertThatThrownBy(() -> publicRevisionPeople.updatePublicProfile(f.maker(), rejected,
                    staffPublicRevisionRequest(f, f.staffId(), 1, null, "Forbidden rejected edit", 2L)))
                    .hasMessageContaining("no longer editable");
            assertDatabaseRejected(() -> jdbc.update("UPDATE staff_public_profile_revisions SET public_display_name='Forbidden rejected edit' WHERE id=?", rejected),
                    "terminal public staff revisions are immutable");
            assertDatabaseRejected(() -> jdbc.update("DELETE FROM staff_public_profile_revisions WHERE id=?", rejected),
                    "cannot be deleted");

            publicRevisionPeople.submitPublicProfile(f.maker(), corrected,
                    new com.educore.api.people.PeopleController.VersionedReason(f.schoolId(), 0, "Synthetic corrected source reviewed"));
            assertThatThrownBy(() -> publicRevisionPeople.submitPublicProfile(f.maker(), corrected,
                    new com.educore.api.people.PeopleController.VersionedReason(f.schoolId(), 0, "Synthetic duplicate submission")))
                    .hasMessageContaining("changed");
            assertThatThrownBy(() -> publicRevisionPeople.decidePublicProfile(f.maker(), corrected,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 1, "Synthetic maker cannot approve")))
                    .hasMessageContaining("different authorized membership");
            publicRevisionPeople.decidePublicProfile(f.checker(), corrected,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 1, "Synthetic independent correction approved"));
            assertThatThrownBy(() -> publicRevisionPeople.decidePublicProfile(f.checker(), corrected,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 1, "Synthetic duplicate approval")))
                    .hasMessageContaining("changed");
            assertThat(staffPublicRevisionEvidence(rejected)).isEqualTo(rejectedEvidence);
            assertThat(staffPublicResponse(f).staff()).singleElement().satisfies(item -> {
                assertThat(item.id()).isEqualTo(corrected);
                assertThat(item.displayName()).isEqualTo("Synthetic corrected first snapshot");
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_profile_history WHERE subject_id=?", Integer.class, corrected)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE subject_type='STAFF_PUBLIC_PROFILE' AND subject_id=?", Integer.class, corrected.toString())).isEqualTo(3);
            assertThat(Arrays.stream(com.educore.api.people.PublicStaffResponse.PublicStaffItem.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList())
                    .containsExactly("id", "displayName", "designation", "qualificationSummary", "shortBio", "cbseStaffCategory", "displayOrder", "publishedAt");
            assertThat(launchUpgradeMapper.writeValueAsString(staffPublicResponse(f)))
                    .doesNotContain("PRIVATE SYNTHETIC", "@example.test", "employeeCode", "workPhone", "restrictedNote",
                            "verificationReason", "decisionReason", "creatorName", "staffProfileId", "userAccountId", "rowVersion");
        }
    }

    @Test @Transactional
    void rejectedStaffPublicReplacementPreservesLivePublicationUntilIndependentApproval() {
        var f = staffPublicRevisionFixture("live-correction", null);
        try (var ignored = TenantRequestContext.open(null, f.organisationId())) {
            UUID published = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 1, null, "Synthetic live first snapshot", null)).id();
            decideStaffPublicRevision(f, published, "PUBLISHED", "Synthetic independent first publication");
            var originalPublic = staffPublicResponse(f).staff().getFirst();
            UUID rejected = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 2, published, "Synthetic rejected replacement", null)).id();
            decideStaffPublicRevision(f, rejected, "REJECTED", "Synthetic replacement needs correction");
            String rejectedEvidence = staffPublicRevisionEvidence(rejected);

            UUID corrected = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 3, rejected, "Synthetic corrected replacement", null)).id();
            assertThat(staffPublicResponse(f).staff()).containsExactly(originalPublic);
            publicRevisionPeople.updatePublicProfile(f.maker(), corrected,
                    staffPublicRevisionRequest(f, f.staffId(), 3, rejected, "Synthetic reviewed corrected replacement", 0L));
            assertThat(staffPublicResponse(f).staff()).containsExactly(originalPublic);
            publicRevisionPeople.submitPublicProfile(f.maker(), corrected,
                    new com.educore.api.people.PeopleController.VersionedReason(f.schoolId(), 1, "Synthetic complete replacement review"));
            assertThat(staffPublicResponse(f).staff()).containsExactly(originalPublic);
            assertDatabaseRejected(() -> jdbc.update("UPDATE staff_public_profile_revisions SET short_bio='Forbidden pending edit' WHERE id=?", corrected),
                    "submitted public staff content is immutable");
            assertThatThrownBy(() -> publicRevisionPeople.decidePublicProfile(f.maker(), corrected,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 2, "Synthetic maker cannot publish replacement")))
                    .hasMessageContaining("different authorized membership");
            assertThat(staffPublicResponse(f).staff()).containsExactly(originalPublic);
            publicRevisionPeople.decidePublicProfile(f.checker(), corrected,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 2, "Synthetic independent replacement approval"));
            assertThat(staffPublicResponse(f).staff()).singleElement().satisfies(item -> {
                assertThat(item.id()).isEqualTo(corrected);
                assertThat(item.displayName()).isEqualTo("Synthetic reviewed corrected replacement");
            });
            assertThat(jdbc.queryForObject("SELECT status FROM staff_public_profile_revisions WHERE id=?", String.class, published)).isEqualTo("RETIRED");
            assertThat(jdbc.queryForObject("SELECT public_display_name FROM staff_public_profile_revisions WHERE id=?", String.class, published)).isEqualTo(originalPublic.displayName());
            assertThat(jdbc.queryForObject("SELECT published_at FROM staff_public_profile_revisions WHERE id=?", java.sql.Timestamp.class, published).toInstant()).isEqualTo(originalPublic.publishedAt());
            assertThat(staffPublicRevisionEvidence(rejected)).isEqualTo(rejectedEvidence);
            assertDatabaseRejected(() -> jdbc.update("UPDATE staff_public_profile_revisions SET decision_reason='Forbidden rejected history edit' WHERE id=?", rejected),
                    "terminal public staff revisions are immutable");
            assertDatabaseRejected(() -> jdbc.update("UPDATE staff_public_profile_revisions SET short_bio='Forbidden retired history edit' WHERE id=?", published),
                    "terminal public staff revisions are immutable");
            assertDatabaseRejected(() -> jdbc.update("UPDATE staff_public_profile_revisions SET public_display_name='Forbidden published edit' WHERE id=?", corrected),
                    "submitted public staff content is immutable");
            assertDatabaseRejected(() -> jdbc.update("DELETE FROM staff_profile_history WHERE subject_id=?", rejected), "append-only");
            assertThat(jdbc.queryForList("SELECT event_type FROM staff_profile_history WHERE subject_id=? ORDER BY occurred_at,id", String.class, published))
                    .containsExactlyInAnyOrder("PUBLIC_DRAFT_CREATED", "PUBLIC_SUBMITTED", "PUBLIC_PUBLISHED", "PUBLIC_RETIRED");
            assertThat(jdbc.queryForList("SELECT event_type FROM staff_profile_history WHERE subject_id=? ORDER BY occurred_at,id", String.class, rejected))
                    .containsExactlyInAnyOrder("PUBLIC_DRAFT_CREATED", "PUBLIC_SUBMITTED", "PUBLIC_REJECTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_public_profile_revisions WHERE staff_profile_id=? AND status='PUBLISHED'", Integer.class, f.staffId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_public_profile_revisions WHERE staff_profile_id=?", Integer.class, f.staffId())).isEqualTo(3);
        }
    }

    @Test @Transactional
    void staffPublicCorrectionRejectsInvalidSourcesAndUnprivilegedOrCrossSchoolActors() {
        var f = staffPublicRevisionFixture("source-guards", null);
        var otherSchool = staffPublicRevisionFixture("other-school", f.organisationId());
        var foreign = staffPublicRevisionFixture("foreign-school", null);
        UUID foreignParent;
        try (var ignored = TenantRequestContext.open(null, foreign.organisationId())) {
            foreignParent = publicRevisionPeople.createPublicProfile(foreign.maker(),
                    staffPublicRevisionRequest(foreign, foreign.staffId(), 1, null, "Synthetic foreign rejected snapshot", null)).id();
            decideStaffPublicRevision(foreign, foreignParent, "REJECTED", "Synthetic foreign independent rejection");
        }
        UUID otherSchoolParent;
        try (var ignored = TenantRequestContext.open(null, f.organisationId())) {
            otherSchoolParent = publicRevisionPeople.createPublicProfile(otherSchool.maker(),
                    staffPublicRevisionRequest(otherSchool, otherSchool.staffId(), 1, null, "Synthetic other-school rejected snapshot", null)).id();
            decideStaffPublicRevision(otherSchool, otherSchoolParent, "REJECTED", "Synthetic other-school independent rejection");
            UUID first = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 1, null, "Synthetic source guard first snapshot", null)).id();
            assertInvalidStaffPublicParent(f, f.staffId(), 2, first);
            publicRevisionPeople.submitPublicProfile(f.maker(), first,
                    new com.educore.api.people.PeopleController.VersionedReason(f.schoolId(), 0, "Synthetic pending source guard"));
            assertInvalidStaffPublicParent(f, f.staffId(), 2, first);
            publicRevisionPeople.decidePublicProfile(f.checker(), first,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 1, "Synthetic first guard publication"));
            UUID second = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 2, first, "Synthetic second guard snapshot", null)).id();
            decideStaffPublicRevision(f, second, "PUBLISHED", "Synthetic second guard publication");
            assertInvalidStaffPublicParent(f, f.staffId(), 2, first); // RETIRED is not a correction source.
            UUID rejected = publicRevisionPeople.createPublicProfile(f.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 3, second, "Synthetic rejected guard snapshot", null)).id();
            decideStaffPublicRevision(f, rejected, "REJECTED", "Synthetic guard correction required");
            long historyBefore = jdbc.queryForObject("SELECT count(*) FROM staff_profile_history WHERE staff_profile_id=?", Long.class, f.staffId());
            assertInvalidStaffPublicParent(f, f.staffId(), 5, rejected); // No skipped revision numbers.
            assertInvalidStaffPublicParent(f, f.staffId(), 4, UUID.randomUUID());
            assertInvalidStaffPublicParent(f, f.staffId(), 2, otherSchoolParent);
            assertInvalidStaffPublicParent(f, f.staffId(), 2, foreignParent);

            UUID otherStaff = addStaffPublicRevisionStaff(f, "SYN-OTHER");
            assertInvalidStaffPublicParent(f, otherStaff, 4, rejected); // Same-school, wrong staff.
            assertThatThrownBy(() -> publicRevisionPeople.createPublicProfile(f.unauthorized(),
                    staffPublicRevisionRequest(f, f.staffId(), 4, rejected, "Synthetic denied correction", null)))
                    .hasMessageContaining("unavailable for this school");
            assertThatThrownBy(() -> publicRevisionPeople.decidePublicProfile(f.unauthorized(), rejected,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 2, "Synthetic denied approval")))
                    .hasMessageContaining("unavailable for this school");
            assertThatThrownBy(() -> publicRevisionPeople.createPublicProfile(otherSchool.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 4, rejected, "Synthetic cross-school denied correction", null)))
                    .hasMessageContaining("unavailable for this school");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_profile_history WHERE staff_profile_id=?", Long.class, f.staffId())).isEqualTo(historyBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_public_profile_revisions WHERE staff_profile_id=?", Integer.class, f.staffId())).isEqualTo(3);
        }
        try (var ignored = TenantRequestContext.open(null, foreign.organisationId())) {
            assertThatThrownBy(() -> publicRevisionPeople.createPublicProfile(foreign.maker(),
                    staffPublicRevisionRequest(f, f.staffId(), 4, null, "Synthetic cross-tenant denied correction", null)))
                    .hasMessageContaining("unavailable for this school");
            assertThatThrownBy(() -> publicRevisionPeople.decidePublicProfile(foreign.checker(), foreignParent,
                    new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), "PUBLISHED", 2, "Synthetic cross-tenant denied approval")))
                    .hasMessageContaining("unavailable for this school");
        }
    }

    private record StaffPublicRevisionFixture(UUID organisationId, UUID schoolId, UUID campusId,
            String schoolSlug, AuthenticatedUser maker, AuthenticatedUser checker,
            AuthenticatedUser unauthorized, UUID designationId, UUID staffId) {}

    private StaffPublicRevisionFixture staffPublicRevisionFixture(String key, UUID existingOrganisation) {
        UUID organisation = existingOrganisation == null ? UUID.randomUUID() : existingOrganisation;
        UUID school = UUID.randomUUID(); UUID campus = UUID.randomUUID();
        String slug = "synthetic-public-" + school;
        if (existingOrganisation == null) jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?,?,'Synthetic public revision organisation','Synthetic public revision organisation','ACTIVE',now(),now())
                """, organisation, "synthetic-public-" + organisation);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?, ?, ?, 'Synthetic public revision school','Synthetic public revision school','ACTIVE',now(),now())
                """, school, organisation, slug);
        jdbc.update("""
                INSERT INTO campuses(id,organisation_id,school_id,code,display_name,time_zone,status,created_at,updated_at,row_version)
                VALUES(?,?,?,'SYN','Synthetic public revision campus','Asia/Kolkata','ACTIVE',now(),now(),0)
                """, campus, organisation, school);
        var actors = new java.util.ArrayList<AuthenticatedUser>();
        for (String purpose : List.of("maker", "checker", "unprivileged")) {
            UUID user = UUID.randomUUID(); UUID member = UUID.randomUUID();
            insertSyntheticUser(user, "synthetic-public-" + key + "-" + purpose + "-" + user + "@example.test");
            insertSyntheticMembership(member, user, organisation, school);
            if (!purpose.equals("unprivileged")) jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", member,
                    UUID.fromString("00000000-0000-4000-8000-000000000001"));
            actors.add(new AuthenticatedUser(user, UUID.randomUUID(), "synthetic-public-" + purpose, null,
                    "Synthetic public " + purpose, true, organisation, Set.of(member),
                    Set.of("people.read", "people.manage", "people.publish"), List.of()));
        }
        try (var ignored = TenantRequestContext.open(null, organisation)) {
            UUID designation = publicRevisionPeople.createDesignation(actors.get(0),
                    new com.educore.api.people.PeopleController.DesignationRequest(school, "SYN_PUBLIC", "Synthetic office designation",
                            "ADMINISTRATION", "NON_TEACHING", false, false, false, false, null, 0, "ACTIVE", null, "Synthetic test configuration")).id();
            var f = new StaffPublicRevisionFixture(organisation, school, campus, slug, actors.get(0), actors.get(1), actors.get(2), designation, null);
            UUID staff = addStaffPublicRevisionStaff(f, "SYN-REVISION");
            return new StaffPublicRevisionFixture(organisation, school, campus, slug, f.maker(), f.checker(), f.unauthorized(), designation, staff);
        }
    }

    private UUID addStaffPublicRevisionStaff(StaffPublicRevisionFixture f, String employeeCode) {
        UUID staff = publicRevisionPeople.createStaff(f.maker(),
                new com.educore.api.people.PeopleController.StaffRequest(f.schoolId(), employeeCode, "PRIVATE SYNTHETIC legal name",
                        "Synthetic public revision staff", "EN", "private-synthetic-public@example.test", "+910000000000", null,
                        LocalDate.of(2026, 4, 1), "ACTIVE", null, "Synthetic unlinked test staff")).id();
        publicRevisionPeople.createAssignment(f.maker(), new com.educore.api.people.PeopleController.AssignmentRequest(f.schoolId(), staff,
                f.designationId(), null, f.campusId(), "FULL_TIME", "SCHOOL", null, true, LocalDate.of(2026, 4, 1), "ACTIVE", "Synthetic test primary assignment"));
        publicRevisionPeople.createQualification(f.maker(), new com.educore.api.people.PeopleController.QualificationRequest(f.schoolId(), staff,
                "PRIVATE SYNTHETIC unverified qualification", "CERTIFICATE", null, null, null, null, "PRIVATE SYNTHETIC evidence reason"));
        return staff;
    }

    private com.educore.api.people.PeopleController.PublicProfileRequest staffPublicRevisionRequest(
            StaffPublicRevisionFixture f, UUID staffId, int revision, UUID supersedes, String displayName, Long version) {
        return new com.educore.api.people.PeopleController.PublicProfileRequest(f.schoolId(), staffId, revision, supersedes,
                displayName, "Synthetic office designation", null, "Synthetic privacy-reviewed public biography",
                "NON_TEACHING", 0, version, "Synthetic reviewable public draft");
    }

    private void decideStaffPublicRevision(StaffPublicRevisionFixture f, UUID id, String decision, String reason) {
        publicRevisionPeople.submitPublicProfile(f.maker(), id,
                new com.educore.api.people.PeopleController.VersionedReason(f.schoolId(), 0, "Synthetic source reviewed before submission"));
        publicRevisionPeople.decidePublicProfile(f.checker(), id,
                new com.educore.api.people.PeopleController.DecisionRequest(f.schoolId(), decision, 1, reason));
    }

    private void assertInvalidStaffPublicParent(StaffPublicRevisionFixture f, UUID staffId, int revision, UUID parent) {
        assertThatThrownBy(() -> publicRevisionPeople.createPublicProfile(f.maker(),
                staffPublicRevisionRequest(f, staffId, revision, parent, "Synthetic invalid source", null)))
                .isInstanceOf(com.educore.api.common.api.ApiException.class)
                .satisfies(error -> assertThat(((com.educore.api.common.api.ApiException) error).code()).isEqualTo("PUBLIC_STAFF_REVISION_INVALID"));
    }

    private String staffPublicRevisionEvidence(UUID id) {
        return jdbc.queryForObject("SELECT row_to_json(profile)::text FROM staff_public_profile_revisions profile WHERE id=?", String.class, id);
    }

    private com.educore.api.people.PublicStaffResponse staffPublicResponse(StaffPublicRevisionFixture f) {
        org.springframework.http.ResponseEntity<com.educore.api.people.PublicStaffResponse> response =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        applicationContext.getBean(com.educore.api.people.PublicStaffController.class), "staff", f.schoolSlug());
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Autowired private com.educore.api.learning.LearningService learning;
    @Autowired private com.educore.api.learning.LearningPublicationNotificationSource learningNotificationSource;
    @Autowired private com.educore.api.learning.LearningPublicationNotificationProcessor learningNotifications;
    @Autowired private com.educore.api.communication.LearningPublicationService learningPublications;

    @Test @Transactional
    void learningPublicationReachesExistingFamilyAndStudentInboxExactlyOnce() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            assertThat(learningEventIds(f.assignmentId())).isEmpty();
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            var events=learningEventIds(f.assignmentId());
            assertThat(events).hasSize(2);
            for(UUID event:events) {
                assertThat(learningNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNotNull();
                assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE id=?",String.class,event))
                        .doesNotContain("PRIVATE SYNTHETIC", "@example.test", "instructions", "title");
            }
            assertThat(learningNotifications.processNext(p.organisationId())).isIn(events);
            assertThat(learningNotifications.processNext(p.organisationId())).isIn(events);
            assertThat(learningNotifications.processNext(p.organisationId())).isNull();
            var parentItem=ptmInbox.familyInbox(f.guardian(),p.learnerId()).items().getFirst();
            var studentItem=ptmInbox.studentInbox(f.student()).items().getFirst();
            assertThat(parentItem.bodyEn()).contains("Assignments").doesNotContain("PRIVATE SYNTHETIC");
            assertThat(studentItem.bodyHi()).contains("असाइनमेंट");
            ptmInbox.readFamily(f.guardian(),p.learnerId(),parentItem.recipientId());
            ptmInbox.readStudent(f.student(),studentItem.recipientId());
            for(UUID event:events) assertThat(learningPublications.publish(p.organisationId(),p.schoolId(),event)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE source_type='ASSIGNMENT_PUBLICATION'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcement_history WHERE actor_kind='SYSTEM'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM communication_deliveries delivery JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id WHERE recipient.recipient_kind='STUDENT' AND delivery.channel<>'IN_APP'
                    """,Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='learning.assignment.published'",Integer.class)).isEqualTo(1);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void learningPublicationWaitsForAvailabilityAndNeverBackfillsDraftOrExpiredWork() {
        var f=learningNoticeFixture(true); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            for(UUID event:learningEventIds(f.assignmentId())) {
                assertThat(learningNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
                assertThat(learningPublications.publish(p.organisationId(),p.schoolId(),event)).isNull();
            }
            assertThat(learningNotifications.processNext(p.organisationId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements",Integer.class)).isZero();
            var later=java.time.Clock.fixed(f.request().dueAt().plusSeconds(1),java.time.ZoneOffset.UTC);
            var source=new com.educore.api.learning.LearningPublicationNotificationSource(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),later);
            for(UUID event:learningEventIds(f.assignmentId())) assertThat(source.current(p.organisationId(),p.schoolId(),event)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE processed_at IS NOT NULL",Integer.class)).isZero();
        }
    }

    @Test @Transactional
    void learningRevisionSupersedesOldDeliveryWithoutExpandingFrozenLearners() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            var original=learningEventIds(f.assignmentId());
            UUID newcomer=UUID.randomUUID();
            insertRolloverStudent(UUID.randomUUID(),newcomer,UUID.randomUUID(),UUID.randomUUID(),p.organisationId(),p.schoolId(),
                    UUID.fromString("10000000-0000-4000-8000-000000000003"),f.sessionId(),f.sectionId(),p.staffMembershipId(),
                    "learning-newcomer","VI","SYN-NEW","2");
            var request=f.request();
            learning.updateAssignment(f.teacher(),f.assignmentId(),new com.educore.api.learning.LearningController.AssignmentRequest(
                    p.schoolId(),request.academicTermId(),request.offeringId(),null,"PRIVATE SYNTHETIC revised work",request.instructions(),
                    null,null,request.availableFrom(),request.dueAt(),"TEXT",null,false,0,false,false,null,1L,"Synthetic approved revision"));
            assertThat(learningEventIds(f.assignmentId())).hasSize(4);
            for(UUID event:original) assertThat(learningNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            var processed=new java.util.HashSet<UUID>();
            for(int index=0;index<4;index++) processed.add(learningNotifications.processNext(p.organisationId()));
            assertThat(processed).hasSize(4).doesNotContainNull();
            assertThat(learningNotifications.processNext(p.organisationId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE source_type='ASSIGNMENT_PUBLICATION'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment_recipients WHERE assignment_id=?",Integer.class,f.assignmentId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_recipients WHERE learner_id=?",Integer.class,newcomer)).isZero();
            for(UUID event:original) assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE id=?",String.class,event)).isEqualTo("LEARNING_SOURCE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment_revision_history WHERE assignment_id=?",Integer.class,f.assignmentId())).isEqualTo(2);
        }
    }

    @Test @Transactional
    void learningRecipientsRecheckCurrentGuardianAndStudentAuthorityAtDelivery() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            UUID family=learningEvent(f,"SECTION_FAMILIES"),student=learningEvent(f,"SECTION_STUDENTS");
            assertThat(learningNotificationSource.recipients(p.organisationId(),p.schoolId(),family)).hasSize(1);
            assertThat(learningNotificationSource.recipients(p.organisationId(),p.schoolId(),student)).hasSize(1);
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=?",p.learnerId());
            jdbc.update("UPDATE learner_portal_accounts SET status='SUSPENDED',suspended_at=now(),suspended_by_membership_id=?,suspension_reason='Synthetic suspension',row_version=row_version+1 WHERE learner_id=?",p.staffMembershipId(),p.learnerId());
            assertThat(learningNotificationSource.recipients(p.organisationId(),p.schoolId(),family)).isEmpty();
            assertThat(learningNotificationSource.recipients(p.organisationId(),p.schoolId(),student)).isEmpty();
            assertThat(learningNotificationSource.isCurrentGuardian(p.organisationId(),p.schoolId(),family,p.guardianId(),p.learnerId(),p.guardianUserId())).isFalse();
            assertThat(learningPublications.publish(p.organisationId(),p.schoolId(),family)).isNull();
            assertThat(learningPublications.publish(p.organisationId(),p.schoolId(),student)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements",Integer.class)).isZero();
        }
    }

    @Test @Transactional
    void learningPublicationProofAndRecipientsRejectForgeryAndDestructiveChanges() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            UUID event=learningEvent(f,"SECTION_FAMILIES");
            assertDatabaseRejected(()->jdbc.update("UPDATE outbox_events SET payload=payload||'{\"instructions\":\"PRIVATE SYNTHETIC\"}'::jsonb WHERE id=?",event),"proof is immutable");
            assertDatabaseRejected(()->jdbc.update("DELETE FROM outbox_events WHERE id=?",event),"cannot be deleted");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,occurred_at,available_at,attempt_count,created_at)
                    SELECT gen_random_uuid(),aggregate_type,aggregate_id,event_type,payload||jsonb_build_object('schoolId',gen_random_uuid()),
                      headers,occurred_at,available_at,0,created_at FROM outbox_events WHERE id=?
                    """,event),"scoped revision proof");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,occurred_at,available_at,attempt_count,created_at)
                    SELECT gen_random_uuid(),aggregate_type,aggregate_id,event_type,payload,headers,occurred_at,available_at,0,created_at FROM outbox_events WHERE id=?
                    """,event),"learning_publication_outbox_once");
            UUID notice=learningPublications.publish(p.organisationId(),p.schoolId(),event);
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,audience_reference_id,
                      language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,status,created_at,updated_at,
                      authority_kind,source_type,source_id,domain_event_id,learning_assignment_id,learning_history_id)
                    SELECT gen_random_uuid(),organisation_id,school_id,category,priority,audience_kind,audience_reference_id,
                      language_mode,'PRIVATE SYNTHETIC task',title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,status,created_at,updated_at,
                      authority_kind,source_type,source_id,domain_event_id,learning_assignment_id,learning_history_id
                    FROM communication_announcements WHERE id=?
                    """,notice),"privacy-minimal generic content");
            assertDatabaseRejected(()->jdbc.update("UPDATE communication_announcements SET title_en='PRIVATE SYNTHETIC' WHERE id=?",notice),"immutable");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                      guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                    VALUES(?,?,?,?, 'GUARDIAN',?,?,?,'EN',true,now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,UUID.randomUUID(),p.learnerId(),p.guardianUserId()),"current access to the frozen learner audience");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void appliesV063AndEnforcesLearningPublicationViewRowSecurity() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='063'",Boolean.class)).isTrue();
            UUID event=learningEvent(f,"SECTION_FAMILIES");
            assertThat(learningPublications.publish(p.organisationId(),UUID.randomUUID(),event)).isNull();
            assertThatThrownBy(()->learning.publishAssignment(f.guardian(),f.assignmentId(),learningTransition(f,1))).hasMessageContaining("not available");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("CREATE ROLE educore_learning_notification_rls LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION");
            jdbc.queryForObject("SELECT set_config('educore.runtime_role','educore_learning_notification_rls',true)",String.class);
            try {
                jdbc.execute(new org.springframework.core.io.ClassPathResource("db/runtime-grants.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            } catch(java.io.IOException failure) { throw new IllegalStateException("Runtime grant source missing",failure); }
            jdbc.execute("SAVEPOINT learning_notification_runtime_probe");
            jdbc.execute("SET LOCAL ROLE educore_learning_notification_rls");
            try {
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_publication_notification_sources",Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_publication_notification_recipients",Integer.class)).isEqualTo(2);
                // Exercise inserts/triggers with the real grant script, not only owner reads.
                // Normal publication records SYSTEM history later in the same transaction.
                jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
                assertThat(learningPublications.publish(p.organisationId(),p.schoolId(),event)).isNotNull();
                jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).items()).hasSize(1);
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_publication_notification_sources",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_publication_notification_recipients",Integer.class)).isZero();
            } finally {
                jdbc.execute("ROLLBACK TO SAVEPOINT learning_notification_runtime_probe");
                jdbc.execute("RELEASE SAVEPOINT learning_notification_runtime_probe");
            }
        }
    }

    @Test @Transactional
    void learningNotificationRetriesAreBoundedPrivateAndOrganisationScoped() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            UUID event=learningEvent(f,"SECTION_FAMILIES");
            jdbc.update("UPDATE outbox_events SET available_at=now()+interval '10 days' WHERE id=?",learningEvent(f,"SECTION_STUDENTS"));
            var publisher=org.mockito.Mockito.mock(com.educore.api.communication.LearningPublicationService.class);
            org.mockito.Mockito.when(publisher.publish(p.organisationId(),p.schoolId(),event)).thenThrow(new IllegalStateException("PRIVATE SYNTHETIC detail"));
            var clock=new PushQueueClock();
            var processor=new com.educore.api.learning.LearningPublicationNotificationProcessor(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),publisher,clock);
            for(int attempt=1;attempt<=8;attempt++) {
                assertThatThrownBy(()->processor.processNext(p.organisationId()))
                        .hasMessage("Learning notification publication failed").hasNoCause();
                processor.recordFailure(p.organisationId(),event);
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE id=?",Integer.class,event)).isEqualTo(attempt);
                assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE id=?",String.class,event)).isEqualTo("LEARNING_NOTIFICATION_FAILED");
                assertThat(processor.processNext(p.organisationId())).isNull();
                clock.advance(7200);
            }
            assertThat(processor.processNext(p.organisationId())).isNull();
            try(var foreign=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(()->processor.recordFailure(p.organisationId(),event)).hasMessageContaining("organisation context");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements",Integer.class)).isZero();
        }
    }

    @Test @Transactional
    void learningPushCancelsAfterClosureBeforeDecryptingDeviceMaterial() {
        var f=learningNoticeFixture(false); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            var registered=pushNotifications.register(f.guardian(),new RegisterPushSubscriptionRequest(p.schoolId(),
                    "https://push.example.org/learning/synthetic",syntheticP256dh(),syntheticAuth(),"Synthetic learning device"));
            jdbc.update("""
                    INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,
                      credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at)
                    VALUES(?,?,?,'PUSH','LIVE','WEB_PUSH','env:APP_PUSH_DELIVERY_PRIVATE_KEY','ACTIVE',?,now(),now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),p.staffMembershipId());
            learning.publishAssignment(f.teacher(),f.assignmentId(),learningTransition(f,0));
            UUID notice=learningPublications.publish(p.organisationId(),p.schoolId(),learningEvent(f,"SECTION_FAMILIES"));
            UUID delivery=jdbc.queryForObject("""
                    SELECT delivery.id FROM communication_deliveries delivery JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id WHERE recipient.communication_announcement_id=? AND delivery.channel='PUSH'
                    """,UUID.class,notice);
            var clock=new PushQueueClock();
            var queue=new PushDeliveryQueue(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),clock);
            queue.enqueue(p.organisationId(),p.schoolId(),delivery,registered.devices().getFirst().id(),clock.instant().plusSeconds(7200));
            var claim=queue.claimNext(p.organisationId(),p.schoolId());
            var protector=org.mockito.Mockito.spy(pushSubscriptionProtector);
            var preparation=new com.educore.api.communication.PushDeliveryPreparation(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                    protector,clock,publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
            assertThat(preparation.prepare(claim).action()).isEqualTo(com.educore.api.communication.PushDeliveryPreparation.Action.SEND);
            org.mockito.Mockito.clearInvocations(protector);
            learning.closeAssignment(f.teacher(),f.assignmentId(),learningTransition(f,1));
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            org.mockito.Mockito.verifyNoInteractions(protector);
        }
    }

    private LearningNoticeFixture learningNoticeFixture(boolean future) {
        var p=academyPortalFixture("ed000000"); insertPortalFixture(p,"synthetic-learning-notice");
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?), (?,?)",p.staffMembershipId(),
                UUID.fromString("00000000-0000-4000-8000-000000000003"),p.guardianMembershipId(),UUID.fromString("00000000-0000-4000-8000-000000000006"));
        UUID session=UUID.randomUUID(),term=UUID.randomUUID(),section=UUID.randomUUID(),subject=UUID.randomUUID(),offering=UUID.randomUUID(),enrolment=UUID.randomUUID();
        UUID campus=UUID.fromString("10000000-0000-4000-8000-000000000003");
        jdbc.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic learning',CURRENT_DATE-30,CURRENT_DATE+30,'PLANNED',now(),now())",session,p.organisationId(),p.schoolId());
        jdbc.update("INSERT INTO academic_terms(id,organisation_id,school_id,academic_session_id,code,display_name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,?,'SYN_LEARNING','Synthetic term',CURRENT_DATE-30,CURRENT_DATE+30,'ACTIVE',now(),now())",term,p.organisationId(),p.schoolId(),session);
        jdbc.update("INSERT INTO class_sections(id,organisation_id,school_id,campus_id,academic_session_id,grade_code,code,display_name,capacity,status,created_at,updated_at) VALUES(?,?,?,?,?,'VI','SYN','Synthetic class',30,'ACTIVE',now(),now())",section,p.organisationId(),p.schoolId(),campus,session);
        jdbc.update("INSERT INTO school_subjects(id,organisation_id,school_id,code,display_name,subject_category,status,created_at,updated_at) VALUES(?,?,?,'SYN_LEARNING','Synthetic learning','CORE','ACTIVE',now(),now())",subject,p.organisationId(),p.schoolId());
        jdbc.update("INSERT INTO section_subject_offerings(id,organisation_id,school_id,class_section_id,subject_id,weekly_target_periods,status,created_at,updated_at) VALUES(?,?,?,?,?,5,'ACTIVE',now(),now())",offering,p.organisationId(),p.schoolId(),section,subject);
        jdbc.update("INSERT INTO teaching_allocations(id,organisation_id,school_id,section_subject_offering_id,teacher_membership_id,allocation_role,starts_on,status,created_at,updated_at) VALUES(?,?,?,?,?,'PRIMARY',CURRENT_DATE-30,'ACTIVE',now(),now())",UUID.randomUUID(),p.organisationId(),p.schoolId(),offering,p.staffMembershipId());
        jdbc.update("INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,grade_code,status,placement_status,starts_on,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'VI','ACTIVE','PLACED',CURRENT_DATE-30,now(),now())",enrolment,p.organisationId(),p.schoolId(),campus,session,p.learnerId(),p.applicationId());
        jdbc.update("INSERT INTO student_section_placements(id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,roll_number,starts_on,status,placed_by_membership_id,placement_reason,created_at,updated_at) VALUES(?,?,?,?,?,?,'1',CURRENT_DATE-30,'ACTIVE',?,'Synthetic placement',now(),now())",UUID.randomUUID(),p.organisationId(),p.schoolId(),enrolment,p.learnerId(),section,p.staffMembershipId());
        var teacher=new AuthenticatedUser(p.staffUserId(),UUID.randomUUID(),"synthetic-teacher@example.test",null,"Synthetic teacher",true,p.organisationId(),Set.of(p.staffMembershipId()),Set.of("learning.read","learning.manage"),List.of());
        var guardian=new AuthenticatedUser(p.guardianUserId(),UUID.randomUUID(),"synthetic-guardian@example.test",null,"Synthetic guardian",true,p.organisationId(),Set.of(p.guardianMembershipId()),Set.of("portal.guardian"),List.of());
        var student=syntheticInboxStudent(p);
        Instant available=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(future) available=available.plusSeconds(7200);
        var request=new com.educore.api.learning.LearningController.AssignmentRequest(p.schoolId(),term,offering,null,
                "PRIVATE SYNTHETIC learning title","PRIVATE SYNTHETIC instructions",null,null,available,available.plusSeconds(86400),"TEXT",null,false,0,false,false,null,null,null);
        UUID assignment;
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) { assignment=learning.createAssignment(teacher,request).id(); }
        return new LearningNoticeFixture(p,teacher,guardian,student,session,section,assignment,request);
    }
    private List<UUID> learningEventIds(UUID assignment) {
        return jdbc.query("SELECT id FROM outbox_events WHERE aggregate_type='ASSIGNMENT' AND event_type='learning.assignment.publication' AND aggregate_id=? ORDER BY id",(r,n)->r.getObject(1,UUID.class),assignment.toString());
    }
    private UUID learningEvent(LearningNoticeFixture f,String audience) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id=? AND payload->>'audienceKind'=? AND payload->>'publicationRevision'='1'",UUID.class,f.assignmentId().toString(),audience);
    }
    private com.educore.api.learning.LearningController.TransitionRequest learningTransition(LearningNoticeFixture f,long version) {
        return new com.educore.api.learning.LearningController.TransitionRequest(f.portal().schoolId(),version,"Synthetic learning acceptance");
    }
    private record LearningNoticeFixture(PortalFixture portal,AuthenticatedUser teacher,AuthenticatedUser guardian,AuthenticatedUser student,
            UUID sessionId,UUID sectionId,UUID assignmentId,com.educore.api.learning.LearningController.AssignmentRequest request) {}

    @Autowired
    private com.educore.api.guardians.GuardianPortalInvitationService guardianInvitations;

    @Autowired
    private com.educore.api.identity.SessionTokenCodec guardianInvitationTokens;

    @Autowired
    private com.educore.api.identity.InvitationTokenProtector guardianInvitationProtector;

    @Test @Transactional
    void guardianExpiryFinalizesDueTokensWithoutDeliveryOrAccessChanges() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var fixtures = new java.util.ArrayList<GuardianExpiryFixture>();
        for (String delivery : List.of("AWAITING_PROVIDER", "QUEUED", "FAILED", "SENT", "DELIVERED", "CANCELLED")) {
            fixtures.add(insertGuardianExpiryFixture(organisation, null, delivery, -60, "INVITED"));
        }
        var active = insertGuardianExpiryFixture(organisation, null, "AWAITING_PROVIDER", -60, "ACTIVE");
        var suspended = insertGuardianExpiryFixture(organisation, null, "AWAITING_PROVIDER", -60, "SUSPENDED");
        fixtures.add(active); fixtures.add(suspended);
        var future = insertGuardianExpiryFixture(organisation, null, "QUEUED", 3600, "INVITED");
        var accepted = insertGuardianExpiryFixture(organisation, null, "DELIVERED", -60, "ACTIVE");
        var revoked = insertGuardianExpiryFixture(organisation, null, "CANCELLED", -60, "NOT_INVITED");
        jdbc.update("""
                UPDATE guardian_portal_invitations SET status='ACCEPTED',token_ciphertext=NULL,token_nonce=NULL,
                  accepted_at=now()-interval '1 hour',accepted_user_id=? WHERE id=?
                """, accepted.portal().guardianUserId(), accepted.invitationId());
        jdbc.update("""
                UPDATE guardian_portal_invitations SET status='REVOKED',token_ciphertext=NULL,token_nonce=NULL,
                  revoked_at=now()-interval '1 hour',revoked_by_membership_id=?,revoked_reason='Synthetic revocation' WHERE id=?
                """, revoked.portal().staffMembershipId(), revoked.invitationId());
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class);
        int rolesBefore = jdbc.queryForObject("SELECT count(*) FROM membership_roles", Integer.class);
        try (var ignored = TenantRequestContext.open(null, organisation)) {
            var processed = new java.util.HashSet<UUID>();
            for (int index = 0; index < fixtures.size(); index++) processed.add(guardianInvitations.expireNext(organisation));
            assertThat(processed).containsExactlyInAnyOrderElementsOf(fixtures.stream().map(GuardianExpiryFixture::invitationId).toList());
            assertThat(guardianInvitations.expireNext(organisation)).isNull();
            assertThat(guardianInvitations.expireNext(organisation)).isNull();
        }
        for (var fixture : fixtures) {
            assertThat(jdbc.queryForObject("SELECT status FROM guardian_portal_invitations WHERE id=?", String.class, fixture.invitationId())).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT token_ciphertext IS NULL AND token_nonce IS NULL FROM guardian_portal_invitations WHERE id=?", Boolean.class, fixture.invitationId())).isTrue();
            assertThat(jdbc.queryForObject("SELECT row_version FROM guardian_portal_invitations WHERE id=?", Long.class, fixture.invitationId())).isEqualTo(1);
            String original = fixture.delivery();
            assertThat(jdbc.queryForObject("SELECT delivery_status FROM guardian_portal_invitations WHERE id=?", String.class, fixture.invitationId()))
                    .isEqualTo(Set.of("SENT", "DELIVERED").contains(original) ? original : "CANCELLED");
            assertThat(guardianExpiryEventCount(fixture.invitationId())).isEqualTo(1);
            String event = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id=? AND event_type='guardian.portal.invitation.expired'", String.class, fixture.invitationId().toString());
            assertThat(event).contains(fixture.invitationId().toString(), fixture.portal().schoolId().toString())
                    .doesNotContain(fixture.rawToken(), "@example.test", "ciphertext", "Synthetic portal");
        }
        for (var fixture : List.of(active, suspended)) {
            assertThat(jdbc.queryForObject("SELECT portal_access_status FROM learner_guardian_relationships WHERE learner_id=?", String.class, fixture.portal().learnerId()))
                    .isEqualTo(fixture == active ? "ACTIVE" : "SUSPENDED");
        }
        assertThat(jdbc.queryForObject("SELECT portal_access_status FROM learner_guardian_relationships WHERE learner_id=?", String.class, fixtures.getFirst().portal().learnerId())).isEqualTo("NOT_INVITED");
        assertThat(jdbc.queryForObject("SELECT status FROM guardian_portal_invitations WHERE id=?", String.class, future.invitationId())).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("SELECT token_ciphertext IS NOT NULL AND row_version=0 FROM guardian_portal_invitations WHERE id=?", Boolean.class, future.invitationId())).isTrue();
        for (var fixture : List.of(future, accepted, revoked)) assertThat(guardianExpiryEventCount(fixture.invitationId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM guardian_portal_invitations WHERE id=?", String.class, accepted.invitationId())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM guardian_portal_invitations WHERE id=?", String.class, revoked.invitationId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_roles", Integer.class)).isEqualTo(rolesBefore);
        var expiryInstant = jdbc.queryForObject("SELECT expires_at FROM guardian_portal_invitations WHERE id=?", java.sql.Timestamp.class, future.invitationId()).toInstant();
        var boundaryService = new com.educore.api.guardians.GuardianPortalInvitationService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), null, null, null, null, null,
                new tools.jackson.databind.ObjectMapper(), null,
                java.time.Clock.fixed(expiryInstant.minusNanos(1000), java.time.ZoneOffset.UTC));
        try (var system = TenantRequestContext.open(null, organisation)) {
            assertThat(boundaryService.expireNext(organisation)).isNull();
            boundaryService = new com.educore.api.guardians.GuardianPortalInvitationService(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), null, null, null, null, null,
                    new tools.jackson.databind.ObjectMapper(), null, java.time.Clock.fixed(expiryInstant, java.time.ZoneOffset.UTC));
            assertThat(boundaryService.expireNext(organisation)).isEqualTo(future.invitationId());
        }
        assertThat(guardianExpiryEventCount(future.invitationId())).isEqualTo(1);
    }

    @Test @Transactional
    void guardianExpiryRetainsReissuedAndTokenPresentedLifecycle() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var f = insertGuardianExpiryFixture(organisation, null, "AWAITING_PROVIDER", -60, "INVITED");
        var p = f.portal();
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", p.staffMembershipId(), UUID.fromString("00000000-0000-4000-8000-000000000001"));
        var actor = new AuthenticatedUser(p.staffUserId(), UUID.randomUUID(), "synthetic-expiry", null, "Synthetic staff", true,
                organisation, Set.of(p.staffMembershipId()), Set.of("guardian.portal.invite", "guardian.manage"), List.of());
        try (var ignored = TenantRequestContext.open(p.staffUserId(), organisation)) {
            var replacement = guardianInvitations.issue(actor, p.learnerId(), p.guardianId(),
                    new com.educore.api.guardians.IssueGuardianPortalInvitationRequest(0L, "Synthetic provider-independent reissue"));
            assertThat(replacement.status()).isEqualTo("ISSUED");
            assertThat(replacement.deliveryStatus()).isEqualTo("AWAITING_PROVIDER");
            try (var system = TenantRequestContext.open(null, organisation)) {
                assertThat(guardianInvitations.expireNext(organisation)).isNull();
            }
            assertThat(jdbc.queryForObject("SELECT status FROM guardian_portal_invitations WHERE id=?", String.class, f.invitationId())).isEqualTo("REVOKED");
            assertThat(jdbc.queryForObject("SELECT portal_access_status FROM learner_guardian_relationships WHERE learner_id=?", String.class, p.learnerId())).isEqualTo("INVITED");
        }
        var presented = insertGuardianExpiryFixture(organisation, null, "SENT", -60, "INVITED");
        assertThatThrownBy(() -> guardianInvitations.preview(new com.educore.api.guardians.GuardianInvitationTokenRequest(presented.rawToken())))
                .hasMessage("This invitation is invalid, expired, or no longer available.");
        try (var system = TenantRequestContext.open(null, organisation)) {
            assertThat(guardianInvitations.expireNext(organisation)).isNull();
        }
        assertThat(guardianExpiryEventCount(presented.invitationId())).isEqualTo(1);
        assertThat(guardianExpiryEventCount(f.invitationId())).isZero();
    }

    @Test @Transactional
    void guardianExpiryRequiresSystemTenantAndRealRowSecurity() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var f = insertGuardianExpiryFixture(organisation, null, "QUEUED", -60, "INVITED");
        UUID otherOrganisation = UUID.randomUUID(), otherSchool = UUID.randomUUID();
        jdbc.update("INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'Synthetic Other Expiry','Synthetic Other Expiry',now(),now())", otherOrganisation, "synthetic-other-expiry-" + otherOrganisation);
        jdbc.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'synthetic-other-expiry','Synthetic Other Expiry School','Synthetic Other Expiry School',now(),now())", otherSchool, otherOrganisation);
        var other = insertGuardianExpiryFixture(otherOrganisation, otherSchool, "QUEUED", -60, "INVITED");
        assertThatThrownBy(() -> guardianInvitations.expireNext(organisation)).hasMessageContaining("matching system tenant");
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) {
            assertThatThrownBy(() -> guardianInvitations.expireNext(organisation)).hasMessageContaining("matching system tenant");
        }
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='010'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='guardian_portal_invitations_expiry_idx'", Integer.class)).isEqualTo(1);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("CREATE ROLE educore_guardian_expiry_rls NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_guardian_expiry_rls");
        jdbc.execute("GRANT SELECT ON organisations,guardians,learner_guardian_relationships,guardian_portal_invitations TO educore_guardian_expiry_rls");
        jdbc.execute("GRANT UPDATE ON guardians,learner_guardian_relationships,guardian_portal_invitations TO educore_guardian_expiry_rls");
        jdbc.execute("GRANT INSERT ON outbox_events TO educore_guardian_expiry_rls");
        jdbc.execute("SET LOCAL ROLE educore_guardian_expiry_rls");
        try (var ignored = TenantRequestContext.open(null, organisation)) {
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, UUID.randomUUID().toString());
            assertThat(guardianInvitations.expireNext(organisation)).isNull();
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id','',true)", String.class);
            assertThat(guardianInvitations.expireNext(organisation)).isNull();
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, organisation.toString());
            assertThat(guardianInvitations.expireNext(organisation)).isEqualTo(f.invitationId());
            assertThat(guardianInvitations.expireNext(organisation)).isNull();
        } finally { jdbc.execute("RESET ROLE"); }
        assertThat(guardianExpiryEventCount(f.invitationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM guardian_portal_invitations WHERE id=?", String.class, other.invitationId())).isEqualTo("ISSUED");
        assertThat(guardianExpiryEventCount(other.invitationId())).isZero();
    }

    @Test
    void guardianExpiryConcurrentWorkersSkipBusyRelationshipsAndDoNotDuplicateEvents() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(queueTransactionManager);
        UUID organisation = UUID.randomUUID(), school = UUID.randomUUID();
        var fixtures = tx.execute(status -> {
            jdbc.update("INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'Synthetic Expiry Organisation','Synthetic Expiry Organisation',now(),now())", organisation, "synthetic-expiry-" + organisation);
            jdbc.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'synthetic-expiry','Synthetic Expiry School','Synthetic Expiry School',now(),now())", school, organisation);
            return List.of(insertGuardianExpiryFixture(organisation, school, "QUEUED", -120, "INVITED"),
                    insertGuardianExpiryFixture(organisation, school, "QUEUED", -60, "INVITED"));
        });
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT 1 FROM guardians WHERE id=? FOR UPDATE", Integer.class, fixtures.getFirst().portal().guardianId());
                locked.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic expiry lock wait expired");
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return true;
            }));
            try {
                assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var skipBusy = executor.submit(() -> {
                    try (var system = TenantRequestContext.open(null, organisation)) {
                        return guardianInvitations.expireNext(organisation);
                    }
                });
                assertThat(skipBusy.get(3, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(fixtures.get(1).invitationId());
            } finally { release.countDown(); }
            assertThat(holder.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var first = executor.submit(() -> {
                try (var system = TenantRequestContext.open(null, organisation)) { return guardianInvitations.expireNext(organisation); }
            });
            var second = executor.submit(() -> {
                try (var system = TenantRequestContext.open(null, organisation)) { return guardianInvitations.expireNext(organisation); }
            });
            assertThat(java.util.Arrays.asList(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(fixtures.getFirst().invitationId(), null);
        }
        for (var fixture : fixtures) assertThat(guardianExpiryEventCount(fixture.invitationId())).isEqualTo(1);
    }

    private GuardianExpiryFixture insertGuardianExpiryFixture(UUID organisation, UUID school, String delivery, int secondsUntilExpiry, String relationshipStatus) {
        UUID scopedSchool = school == null ? UUID.fromString("10000000-0000-4000-8000-000000000002") : school;
        var portal = new PortalFixture(organisation, scopedSchool, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String key = "synthetic-expiry-" + UUID.randomUUID();
        insertPortalFixture(portal, key);
        jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status=? WHERE learner_id=?", relationshipStatus, portal.learnerId());
        UUID id = UUID.randomUUID();
        String rawToken = guardianInvitationTokens.newRawToken();
        var protectedToken = guardianInvitationProtector.protect(rawToken, id.toString());
        jdbc.update("""
                INSERT INTO guardian_portal_invitations(id,organisation_id,school_id,learner_id,guardian_id,
                  target_email_normalized,token_hash,token_ciphertext,token_nonce,status,delivery_status,
                  expires_at,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(?,?,?,?,?,?,?,?,?,'ISSUED',?,now()+(?*interval '1 second'),?,now()-interval '2 days',now(),0)
                """, id, organisation, scopedSchool, portal.learnerId(), portal.guardianId(), key + "-guardian@example.test",
                guardianInvitationTokens.hash(rawToken), protectedToken.ciphertext(), protectedToken.nonce(), delivery, secondsUntilExpiry, portal.staffMembershipId());
        return new GuardianExpiryFixture(portal, id, rawToken, delivery);
    }

    private int guardianExpiryEventCount(UUID invitationId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='guardian.portal.invitation.expired'", Integer.class, invitationId.toString());
    }

    private record GuardianExpiryFixture(PortalFixture portal, UUID invitationId, String rawToken, String delivery) {}

    @Autowired
    private Flyway flyway;

    @Test @Transactional
    void rteAdmissionPreservesClassificationAndAutomaticScopedFeeExemptions() {
        var f = insertRteFixture();
        UUID existingGuardian = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic existing guardian','synthetic-rte-guardian@example.test','ACTIVE',now(),now())
                """, existingGuardian, f.org(), f.school());
        var ready = readyRteApplication(f, com.educore.api.admissions.AdmissionType.RTE, "SYN-RTE-001");
        assertThat(applications.options(f.admin(), ready.id()).guardianMatches()).singleElement()
                .satisfies(match -> assertThat(match.candidates()).singleElement()
                        .satisfies(candidate -> assertThat(candidate.guardianId()).isEqualTo(existingGuardian)));
        var handoff = applications.enrol(f.admin(), ready.id(), new com.educore.api.admissions.EnrolApplicationRequest(
                f.campus(), f.session(), f.starts(), null,
                List.of(new com.educore.api.admissions.GuardianMatchInput(ready.guardians().getFirst().id(), existingGuardian)), ready.version()));
        assertThat(jdbc.queryForObject("SELECT guardian_id FROM learner_guardian_relationships WHERE learner_id=?", UUID.class,
                handoff.enrolment().learnerId())).isEqualTo(existingGuardian);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guardians WHERE school_id=?", Integer.class, f.school())).isEqualTo(1);
        var options = applications.options(f.admin(), handoff.application().id());
        assertThat(options.campuses()).singleElement();
        assertThat(options.academicSessions()).singleElement().satisfies(session -> {
            assertThat(session.rteSeatLimit()).isEqualTo(2);
            assertThat(session.rteSeatsUsed()).isEqualTo(1);
        });
        assertThat(options.guardianMatches()).singleElement();
        var learner = jdbc.queryForMap("SELECT admission_type,rte_admission_session_id,rte_reference FROM learners WHERE id=?", handoff.enrolment().learnerId());
        assertThat(learner).containsEntry("admission_type", "RTE").containsEntry("rte_admission_session_id", f.session())
                .containsEntry("rte_reference", "SYN-RTE-001");
        var account = assignRteTestPlan(f, f.session(), handoff.enrolment().enrolmentId(), "FIRST");
        var charges = finance.snapshot(f.admin(), f.school()).accounts().stream().filter(a -> a.id().equals(account)).findFirst().orElseThrow().charges();
        assertThat(charges).hasSize(3);
        assertThat(charges.stream().filter(c -> c.feeHeadCode().equals("SYN_ADMISSION") || c.feeHeadCode().equals("SYN_TUITION")))
                .allSatisfy(c -> { assertThat(c.amount()).isEqualByComparingTo("1000"); assertThat(c.rteExemptionAmount()).isEqualByComparingTo("1000"); assertThat(c.outstandingAmount()).isZero(); });
        assertThat(charges.stream().filter(c -> c.feeHeadCode().equals("SYN_TRANSPORT")).findFirst().orElseThrow().outstandingAmount()).isEqualByComparingTo("1000");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fee_ledger_entries WHERE learner_fee_account_id=? AND source_type='RTE_EXEMPTION'", Integer.class, account)).isEqualTo(2);
        var registry = new com.educore.api.students.StudentRegistryService(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), nonTravelAudit, java.time.Clock.systemUTC());
        assertThat(registry.list(f.admin(), null, null, null, null, 0, 25, com.educore.api.admissions.AdmissionType.RTE).items())
                .singleElement().satisfies(student -> { assertThat(student.admissionType()).isEqualTo("RTE"); assertThat(student.rteAdmissionSessionId()).isEqualTo(f.session()); });
        var profile = registry.get(f.admin(), handoff.enrolment().learnerId());
        assertThat(profile.student().admissionType()).isEqualTo("RTE");
        assertThat(profile.student().rteAdmissionSessionId()).isEqualTo(f.session());
        assertThat(profile.student().rteReference()).isEqualTo("SYN-RTE-001");
        assertThat(profile.guardians()).singleElement();
        var foreign = new AuthenticatedUser(f.admin().userId(), UUID.randomUUID(), "synthetic-rte@example.test", "synthetic-rte@example.test",
                "Synthetic foreign actor", true, UUID.randomUUID(), f.admin().membershipIds(), f.admin().capabilities(), List.of());
        assertThatThrownBy(() -> registry.get(foreign, handoff.enrolment().learnerId())).isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test @Transactional
    void rteRegularAdmissionsAndExistingLearnersRemainUnchanged() {
        var f = insertRteFixture();
        var handoff = admitRteLearner(f, com.educore.api.admissions.AdmissionType.REGULAR, null);
        assertThat(jdbc.queryForMap("SELECT admission_type,rte_admission_session_id,rte_reference FROM learners WHERE id=?", handoff.enrolment().learnerId()))
                .containsEntry("admission_type", "REGULAR").containsEntry("rte_admission_session_id", null).containsEntry("rte_reference", null);
        var account = assignRteTestPlan(f, f.session(), handoff.enrolment().enrolmentId(), "REGULAR");
        assertThat(finance.snapshot(f.admin(), f.school()).accounts().stream().filter(a -> a.id().equals(account)).findFirst().orElseThrow().charges())
                .allSatisfy(c -> { assertThat(c.rteExemptionAmount()).isZero(); assertThat(c.outstandingAmount()).isEqualByComparingTo("1000"); });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE school_id=? AND admission_type='RTE'", Integer.class, f.school())).isZero();
        var registry = new com.educore.api.students.StudentRegistryService(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), nonTravelAudit, java.time.Clock.systemUTC());
        assertThat(registry.get(f.admin(), handoff.enrolment().learnerId()).student().admissionType()).isEqualTo("REGULAR");
    }

    @Test @Transactional
    void rteAdmissionRejectsInvalidClassAgeForeignScopeAndUnauthorizedActor() {
        var f = insertRteFixture();
        var valid = rteApplicationRequest(f, com.educore.api.admissions.AdmissionType.RTE, null, "I", f.starts().minusYears(8));
        assertThatThrownBy(() -> applications.create(f.admin(), rteApplicationRequest(f, com.educore.api.admissions.AdmissionType.RTE, null, "II", f.starts().minusYears(8)), "rte-invalid-grade-0001")).hasMessageContaining("Class I");
        assertThatThrownBy(() -> applications.create(f.admin(), rteApplicationRequest(f, com.educore.api.admissions.AdmissionType.RTE, null, "I", f.starts().minusYears(5)), "rte-invalid-age-00001")).hasMessageContaining("age range");
        assertThatThrownBy(() -> applications.create(f.admin(), rteApplicationRequest(f, com.educore.api.admissions.AdmissionType.RTE, null, "I", f.starts().minusYears(15)), "rte-invalid-age-00002")).hasMessageContaining("age range");
        var foreign = new AuthenticatedUser(f.admin().userId(), UUID.randomUUID(), "synthetic-rte@example.test", "synthetic-rte@example.test", "Synthetic RTE staff", true, UUID.randomUUID(), f.admin().membershipIds(), f.admin().capabilities(), List.of());
        assertThatThrownBy(() -> applications.create(foreign, valid, "rte-foreign-org-00001")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?", f.admin().membershipIds().iterator().next());
        assertThatThrownBy(() -> applications.create(f.admin(), valid, "rte-denied-user-00001")).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE school_id=?", Integer.class, f.school())).isZero();
    }

    @Test @Transactional
    void rteSeatLimitReferencesRetriesAndOriginalEvidenceAreProtected() {
        var f = insertRteFixture();
        var first = admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, "SYN-REFERENCE-1");
        var retry = applications.enrol(f.admin(), first.application().id(), new com.educore.api.admissions.EnrolApplicationRequest(f.campus(), f.session(), f.starts(), null, null, 0L));
        assertThat(retry.enrolment().learnerId()).isEqualTo(first.enrolment().learnerId());
        assertThatThrownBy(() -> admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, "syn-reference-1")).hasMessageContaining("reference has already");
        admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, null);
        assertThatThrownBy(() -> admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, null)).hasMessageContaining("seats are already filled");
        assertDatabaseRejected(() -> jdbc.update("UPDATE learners SET admission_type='REGULAR',rte_admission_session_id=NULL,rte_reference=NULL WHERE id=?", first.enrolment().learnerId()), "cannot be silently changed");
        assertDatabaseRejected(() -> jdbc.update("UPDATE academic_sessions SET rte_class_one_seat_limit=1 WHERE id=?", f.session()), "lower than completed");
        assertDatabaseRejected(() -> jdbc.update("UPDATE academic_sessions SET rte_min_age_years=7 WHERE id=?", f.session()), "frozen");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE rte_admission_session_id=?", Integer.class, f.session())).isEqualTo(2);
    }

    @Test @Transactional
    void rteStatusSurvivesRealYearRolloverAndFeesEndAfterClassEight() {
        var f = insertRteFixture();
        var lifecycleRollovers = new AcademicRolloverService(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                nonTravelAudit, new tools.jackson.databind.ObjectMapper(),
                java.time.Clock.fixed(java.time.Instant.parse("2040-04-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        var original = admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, "SYN-LIFECYCLE-1");
        UUID learner = original.enrolment().learnerId(), enrolment = original.enrolment().enrolmentId(), session = f.session();
        jdbc.update("UPDATE academic_sessions SET rte_class_one_seat_limit=3 WHERE id=?", session);
        String[] grades = {"I","II","III","IV","V","VI","VII","VIII","IX"};
        LocalDate start = f.starts();
        for (int index = 0; index < grades.length; index++) {
            var feeAccount = assignRteTestPlan(f, session, enrolment, "YEAR" + index);
            BigDecimal tuitionExemption = jdbc.queryForObject("SELECT rte_exemption_amount FROM learner_fee_charges WHERE learner_fee_account_id=? AND fee_head_code_snapshot='SYN_TUITION'", BigDecimal.class, feeAccount);
            assertThat(tuitionExemption).isEqualByComparingTo(index < 8 ? "1000" : "0");
            assertThat(jdbc.queryForObject("SELECT admission_type FROM learners WHERE id=?", String.class, learner)).isEqualTo("RTE");
            if (index == grades.length - 1) break;
            UUID section = jdbc.queryForObject("SELECT id FROM class_sections WHERE academic_session_id=? AND grade_code=? AND code='A'", UUID.class, session, grades[index]);
            if (index == 0) {
                jdbc.update("UPDATE student_enrolments SET placement_status='PLACED' WHERE id=?", enrolment);
                jdbc.update("""
                    INSERT INTO student_section_placements(id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,starts_on,status,placed_by_membership_id,placement_reason,created_at,updated_at,row_version)
                    VALUES(?,?,?,?,?,?,?,'ACTIVE',?,'Synthetic RTE class placement',now(),now(),0)
                    """, UUID.randomUUID(), f.org(), f.school(), enrolment, learner, section, start, f.admin().membershipIds().iterator().next());
            }
            var request = new RolloverPreviewRequest(f.school(), session, "Synthetic RTE next " + index, start.plusYears(1), start.plusYears(2).minusDays(1), false, false, false, false,
                    List.of(new StudentRolloverDecisionRequest(enrolment, "PROMOTE", grades[index + 1], "A", null, null, "Synthetic RTE promotion")));
            var preview = lifecycleRollovers.preview(f.admin(), request);
            var execution = lifecycleRollovers.execute(f.admin(), new RolloverExecutionRequest(request, "synthetic-rte-rollover-" + index, preview.snapshotHash(), "Synthetic RTE year continuation"));
            session = execution.targetSessionId();
            start = start.plusYears(1);
            enrolment = jdbc.queryForObject("SELECT id FROM student_enrolments WHERE learner_id=? AND academic_session_id=?", UUID.class, learner, session);
            assertThat(jdbc.queryForObject("SELECT rte_class_one_seat_limit FROM academic_sessions WHERE id=?", Integer.class, session)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT rte_admission_session_id FROM learners WHERE id=?", UUID.class, learner)).isEqualTo(f.session());
        }
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE school_id=? AND admission_type='RTE'", Integer.class, f.school())).isEqualTo(1);
    }

    @Test
    void rteConcurrentAdmissionsCannotTakeTheSameLastSeat() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(queueTransactionManager);
        var f = tx.execute(status -> insertRteFixture());
        tx.executeWithoutResult(status -> admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, null));
        var candidates = tx.execute(status -> List.of(
                readyRteApplication(f, com.educore.api.admissions.AdmissionType.RTE, null),
                readyRteApplication(f, com.educore.api.admissions.AdmissionType.RTE, null)));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts = candidates.stream().map(application -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic RTE start timeout");
                try {
                    applications.enrol(f.admin(), application.id(), new com.educore.api.admissions.EnrolApplicationRequest(
                            f.campus(), f.session(), f.starts(), null, null, application.version()));
                    return "ADMITTED";
                } catch (com.educore.api.common.api.ApiException exception) {
                    assertThat(exception).hasMessageContaining("seats are already filled");
                    return "FULL";
                }
            })).toList();
            try { assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            assertThat(List.of(attempts.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                    attempts.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder("ADMITTED", "FULL");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE rte_admission_session_id=?", Integer.class, f.session())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admission_applications WHERE school_id=? AND status='ACCEPTED'", Integer.class, f.school())).isEqualTo(1);
    }

    @Test @Transactional
    void appliesV062AndProtectsRteEvidenceExemptionsAndTenantIsolation() {
        assertThat(Arrays.stream(flyway.info().applied()).map(migration -> migration.getScript()))
                .contains("V062__rte_admissions_and_fee_exemptions.sql");
        var f = insertRteFixture();
        var foreign = insertRteFixture();
        var admitted = admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, null);
        admitRteLearner(foreign, com.educore.api.admissions.AdmissionType.REGULAR, null);
        UUID account = assignRteTestPlan(f, f.session(), admitted.enrolment().enrolmentId(), "GUARDS");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertDatabaseRejected(() -> jdbc.update("UPDATE learner_fee_charges SET rte_exemption_amount=0 WHERE learner_fee_account_id=?", account), "append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,
                    entry_type,direction,amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at)
                SELECT ?,organisation_id,school_id,learner_fee_account_id,id,'CONCESSION','CREDIT',amount,due_on,
                    'Synthetic invalid other-fee exemption','RTE_EXEMPTION',id,?,now()
                FROM learner_fee_charges WHERE learner_fee_account_id=? AND fee_head_category_snapshot='TRANSPORT'
                """, UUID.randomUUID(), f.admin().membershipIds().iterator().next(), account), "immutable eligible charge");
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,
                    entry_type,direction,amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at)
                SELECT ?,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,'CONCESSION','CREDIT',50,
                    effective_on,'Synthetic invalid partial exemption','RTE_EXEMPTION',source_id,created_by_membership_id,now()
                FROM fee_ledger_entries WHERE learner_fee_account_id=? AND source_type='RTE_EXEMPTION' LIMIT 1
                """, UUID.randomUUID(), account), "immutable eligible charge");

        var plan = finance.createPlan(f.admin(), new FeePlanRequest(f.school(), f.session(), null,
                "SYN_RTE_ATOMIC", 1, "Synthetic atomic fee guard", null, "INR", "SYN-RTE", null, null, "Synthetic guard test"));
        UUID tuitionHead = jdbc.queryForObject("SELECT id FROM fee_heads WHERE school_id=? AND category='TUITION'", UUID.class, f.school());
        finance.addPlanItem(f.admin(), plan.id(), new PlanItemRequest(f.school(), tuitionHead, 1,
                "Synthetic uncredited tuition", f.starts().plusDays(10), new BigDecimal("1000"), true, "Synthetic guard test"));
        UUID item = jdbc.queryForObject("SELECT id FROM fee_plan_items WHERE fee_plan_id=?", UUID.class, plan.id());
        UUID assignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fee_plan_assignments(id,organisation_id,school_id,learner_fee_account_id,fee_plan_id,
                    plan_snapshot,assigned_by_membership_id,assigned_at,reason)
                VALUES(?,?,?,?,?,'{}'::jsonb,?,now(),'Synthetic atomic guard test')
                """, assignment, f.org(), f.school(), account, plan.id(), f.admin().membershipIds().iterator().next());
        assertDatabaseRejected(() -> {
            jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
            jdbc.update("""
                    INSERT INTO learner_fee_charges(id,organisation_id,school_id,learner_fee_account_id,
                        fee_plan_assignment_id,fee_plan_item_id,fee_head_code_snapshot,fee_head_name_snapshot,
                        label_snapshot,due_on,amount,allow_partial,created_at)
                    VALUES(?,?,?,?,?,?,'SYN_TUITION','Synthetic tuition','Synthetic uncredited tuition',?,1000,true,now())
                    """, UUID.randomUUID(), f.org(), f.school(), account, assignment, item, f.starts().plusDays(10));
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }, "must commit atomically");
        jdbc.execute("CREATE ROLE educore_rte_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_rte_rls_test");
        jdbc.execute("GRANT SELECT ON learners,admission_applications,learner_fee_charges TO educore_rte_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_rte_rls_test");
        try {
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, f.org().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE admission_type='RTE'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learners WHERE organisation_id=?", Integer.class, foreign.org())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admission_applications WHERE organisation_id=?", Integer.class, foreign.org())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_fee_charges WHERE rte_exemption_amount>0", Integer.class)).isEqualTo(2);
        } finally { jdbc.execute("RESET ROLE"); }
    }

    @Test @Transactional
    void rteExemptionsPostThroughTheExistingDoubleEntryFeeAccounting() {
        var f = insertRteFixture();
        var admitted = admitRteLearner(f, com.educore.api.admissions.AdmissionType.RTE, null);
        UUID feeAccount = assignRteTestPlan(f, f.session(), admitted.enrolment().enrolmentId(), "ACCOUNTING");
        var maker = new AuthenticatedUser(f.admin().userId(), UUID.randomUUID(), "synthetic-rte-maker@example.test",
                "synthetic-rte-maker@example.test", "Synthetic RTE accounting maker", true, f.org(), f.admin().membershipIds(),
                Set.of("fees.read", "fees.close.propose", "fees.close.approve", "accounting.read", "accounting.configure",
                        "accounting.prepare", "accounting.post", "accounting.close"), List.of());
        UUID approverUser = UUID.randomUUID(), approverMembership = UUID.randomUUID();
        insertSyntheticUser(approverUser, "synthetic-rte-approver-" + approverUser + "@example.test");
        insertSyntheticMembership(approverMembership, approverUser, f.org(), f.school());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)", approverMembership,
                UUID.fromString("00000000-0000-4000-8000-000000000007"));
        var approver = new AuthenticatedUser(approverUser, UUID.randomUUID(), "synthetic-rte-approver@example.test",
                "synthetic-rte-approver@example.test", "Synthetic independent RTE approver", true, f.org(), Set.of(approverMembership),
                Set.of("fees.read", "fees.close.approve", "accounting.read", "accounting.post", "accounting.close"), List.of());
        var year = accounting.createFinancialYear(maker, new FinancialYearRequest(f.school(), "SYN_RTE_YEAR",
                "Synthetic RTE financial year", f.starts(), f.starts().plusYears(1).minusDays(1), "ACCRUAL", "INR", "Synthetic RTE accounting test"));
        UUID receivable = rteAccountingAccount(maker, f.school(), "A1100.RTE", "ASSET", "RECEIVABLE");
        UUID concession = rteAccountingAccount(maker, f.school(), "E5100.RTE", "EXPENSE", "EXPENSE");
        UUID income = rteAccountingAccount(maker, f.school(), "I4100.RTE", "INCOME", "INCOME");
        UUID cash = rteAccountingAccount(maker, f.school(), "A1000.RTE", "ASSET", "CASH");
        UUID bank = rteAccountingAccount(maker, f.school(), "A1010.RTE", "ASSET", "BANK");
        accounting.openFinancialYear(approver, year.id(), new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                f.school(), 0, "Synthetic independent RTE book opening"));
        var heads = jdbc.queryForList("SELECT id FROM fee_heads WHERE school_id=? ORDER BY code", UUID.class, f.school());
        var configuration = feeAccounting.createConfiguration(maker, new ConfigurationRequest(f.school(), year.id(), null,
                1, f.starts(), receivable, concession, heads.stream().map(head -> new HeadMappingRequest(head, income)).toList(),
                List.of(new TenderMappingRequest("CASH", cash), new TenderMappingRequest("BANK_TRANSFER", bank),
                        new TenderMappingRequest("CHEQUE", bank), new TenderMappingRequest("SCHOOL_UPI", bank),
                        new TenderMappingRequest("ONLINE_PROVIDER", bank)), null, "Synthetic RTE fee mapping"));
        var submitted = feeAccounting.submitConfiguration(maker, configuration.id(),
                new com.educore.api.finance.FeeAccountingController.VersionedReasonRequest(f.school(), configuration.rowVersion(), "Synthetic RTE mapping review"));
        feeAccounting.decideConfiguration(approver, configuration.id(), new ConfigurationDecisionRequest(
                f.school(), "APPROVE", submitted.rowVersion(), "Synthetic independent RTE mapping approval"));
        for (String event : List.of("CHARGE", "CONCESSION")) {
            var batch = feeAccounting.preparePosting(maker, "synthetic-rte-post-" + event.toLowerCase(),
                    new PostingRequest(f.school(), year.id(), f.starts().plusDays(10), event, 200, "Synthetic RTE " + event + " posting"));
            UUID journal = jdbc.queryForObject("SELECT accounting_journal_id FROM fee_accounting_posting_batches WHERE id=?", UUID.class, batch.id());
            accounting.decideJournal(approver, journal, new com.educore.api.accounting.AccountingController.DecisionRequest(
                    f.school(), "POST", "Synthetic independent RTE journal posting", 1));
        }
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(sum(line.debit_amount-line.credit_amount),0) FROM accounting_journal_lines line
                JOIN accounting_journals journal ON journal.id=line.accounting_journal_id
                WHERE journal.school_id=? AND journal.status='POSTED' AND line.account_id=?
                """, BigDecimal.class, f.school(), receivable)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fee_accounting_posting_items item JOIN fee_ledger_entries ledger ON ledger.id=item.fee_ledger_entry_id
                WHERE ledger.learner_fee_account_id=? AND ledger.source_type='RTE_EXEMPTION' AND item.status='ACTIVE'
                """, Integer.class, feeAccount)).isEqualTo(2);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    private UUID rteAccountingAccount(AuthenticatedUser maker, UUID school, String code, String type, String use) {
        return accounting.createAccount(maker, new AccountRequest(school, null, code, "Synthetic RTE " + use,
                null, type, use, true, "ACTIVE", null, "Synthetic RTE ledger mapping")).id();
    }

    private RteFixture insertRteFixture() {
        UUID org=UUID.randomUUID(),school=UUID.randomUUID(),campus=UUID.randomUUID(),user=UUID.randomUUID(),membership=UUID.randomUUID(),session=UUID.randomUUID();
        jdbc.update("INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'Synthetic RTE organisation','Synthetic RTE organisation',now(),now())",org,"synthetic-rte-"+org);
        jdbc.update("INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'synthetic-rte','Synthetic RTE school','Synthetic RTE school',now(),now())",school,org);
        jdbc.update("INSERT INTO campuses(id,organisation_id,school_id,code,display_name,time_zone,created_at,updated_at) VALUES(?,?,?,'SYN-RTE','Synthetic RTE campus','Asia/Kolkata',now(),now())",campus,org,school);
        insertSyntheticUser(user,"synthetic-rte-"+user+"@example.test"); insertSyntheticMembership(membership,user,org,school);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",membership,UUID.fromString("00000000-0000-4000-8000-000000000001"));
        LocalDate starts=LocalDate.of(2026,4,1);
        jdbc.update("INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at) VALUES(?,?,?,'Synthetic RTE session',?,?,'ACTIVE',now(),now())",session,org,school,starts,starts.plusYears(1).minusDays(1));
        for(String grade:List.of("I","II","III","IV","V","VI","VII","VIII","IX")) jdbc.update("INSERT INTO class_sections(id,organisation_id,school_id,campus_id,academic_session_id,grade_code,code,display_name,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'A',?,'ACTIVE',now(),now())",UUID.randomUUID(),org,school,campus,session,grade,"Synthetic class "+grade);
        var admin=new AuthenticatedUser(user,UUID.randomUUID(),"synthetic-rte@example.test","synthetic-rte@example.test","Synthetic RTE staff",true,org,Set.of(membership),Set.of("admissions.application.manage","admissions.application.read","admissions.application.enrol","admissions.application.decide","student.manage","student.read","guardian.manage","guardian.read","fees.configure","fees.approve","fees.read","academic.rollover.manage"),List.of());
        return new RteFixture(org,school,campus,session,starts,admin);
    }

    private com.educore.api.admissions.CreateApplicationRequest rteApplicationRequest(RteFixture f, com.educore.api.admissions.AdmissionType type, String reference, String grade, LocalDate birth) {
        return new com.educore.api.admissions.CreateApplicationRequest(f.school(),null,f.campus(),type == com.educore.api.admissions.AdmissionType.RTE ? f.session() : null,"Synthetic RTE session",grade,"Synthetic RTE learner",null,birth,com.educore.api.admissions.ApplicantGender.NOT_STATED,null,
                List.of(new com.educore.api.admissions.GuardianInput("Synthetic RTE guardian","PARENT_GUARDIAN","synthetic-rte-guardian@example.test",null,true,false)),type,reference);
    }

    private com.educore.api.admissions.EnrolmentHandoffResponse admitRteLearner(RteFixture f, com.educore.api.admissions.AdmissionType type, String reference) {
        var application = readyRteApplication(f, type, reference);
        return applications.enrol(f.admin(), application.id(), new com.educore.api.admissions.EnrolApplicationRequest(f.campus(), f.session(), f.starts(), null, null, application.version()));
    }

    private com.educore.api.admissions.ApplicationDetailResponse readyRteApplication(RteFixture f, com.educore.api.admissions.AdmissionType type, String reference) {
        var request=rteApplicationRequest(f,type,reference,"I",f.starts().minusYears(8));
        String key="synthetic-rte-"+UUID.randomUUID();
        var application=applications.create(f.admin(),request,key);
        assertThat(applications.create(f.admin(),request,key).id()).isEqualTo(application.id());
        application=applications.updateStatus(f.admin(),application.id(),new com.educore.api.admissions.UpdateApplicationStatusRequest(com.educore.api.admissions.ApplicationStatus.SUBMITTED,application.version()));
        application=applications.decide(f.admin(),application.id(),new com.educore.api.admissions.RecordAdmissionDecisionRequest(com.educore.api.admissions.AdmissionDecision.OFFER,"SYNTHETIC",null,LocalDate.now().plusDays(30),application.version()));
        application=applications.updateStatus(f.admin(),application.id(),new com.educore.api.admissions.UpdateApplicationStatusRequest(com.educore.api.admissions.ApplicationStatus.ACCEPTED,application.version()));
        return application;
    }

    private UUID assignRteTestPlan(RteFixture f, UUID session, UUID enrolment, String suffix) {
        var plan=finance.createPlan(f.admin(),new FeePlanRequest(f.school(),session,null,"SYN_RTE_"+suffix,1,"Synthetic RTE fees",null,"INR","SYN-RTE",null,null,"Synthetic fee setup"));
        LocalDate due=jdbc.queryForObject("SELECT starts_on FROM academic_sessions WHERE id=?",LocalDate.class,session).plusDays(10);
        int sequence=0;
        for(String category:List.of("ADMISSION","TUITION","TRANSPORT")) {
            var heads=jdbc.queryForList("SELECT id FROM fee_heads WHERE school_id=? AND code=?",UUID.class,f.school(),"SYN_"+category);
            UUID head=heads.isEmpty()?finance.createFeeHead(f.admin(),new FeeHeadRequest(f.school(),"SYN_"+category,"Synthetic "+category,null,category,false,"ACTIVE",null,"Synthetic fee category")).id():heads.getFirst();
            finance.addPlanItem(f.admin(),plan.id(),new PlanItemRequest(f.school(),head,++sequence,"Synthetic "+category,due,new BigDecimal("1000"),true,"Synthetic scheduled fee"));
        }
        long version=jdbc.queryForObject("SELECT row_version FROM fee_plans WHERE id=?",Long.class,plan.id());
        finance.activatePlan(f.admin(),plan.id(),new PlanActivationRequest(f.school(),version,false,"Synthetic fee activation"));
        return finance.assignPlan(f.admin(),new PlanAssignmentRequest(f.school(),plan.id(),enrolment,"Synthetic learner fee assignment")).id();
    }

    private record RteFixture(UUID org,UUID school,UUID campus,UUID session,LocalDate starts,AuthenticatedUser admin) {}

    @Autowired
    private AdmissionApplicationService applications;

    @Autowired
    private EnquiryService enquiries;

    @Autowired
    private AcademicService academics;

    @Autowired
    private AcademicRolloverService academicRollovers;

    @Autowired
    private SchoolConfigurationService schoolConfiguration;

    @Autowired
    private ReportsService reports;

    @Autowired
    private OfficialDocumentService officialDocuments;

    @Autowired
    private com.educore.api.documents.FamilyDocumentService familyDocuments;

    @Autowired
    private FinanceService finance;

    @Autowired
    private LaunchReadinessService launchReadiness;

    @Autowired
    private SchoolCalendarService schoolCalendars;

    @Autowired
    private SchoolCalendarNotificationProcessor schoolCalendarNotifications;

    @Autowired
    private FamilyService family;

    @Autowired
    private StudentPortalService studentPortal;

    @Autowired
    private PushNotificationService pushNotifications;

    @Autowired
    private WorkforceService workforce;

    @Autowired
    private AccountingService accounting;

    @Autowired
    private FeeAccountingService feeAccounting;

    @Autowired
    private PayrollService payroll;

    @Autowired
    private PayrollAccountingService payrollAccounting;

    @Autowired
    private PayrollAdvanceService payrollAdvances;

    @Autowired
    private PayrollAdvanceStatementService payrollAdvanceStatements;

    @Autowired
    private com.educore.api.payroll.PayrollReportingService payrollReports;

    @Autowired
    private PayrollRunService payrollRuns;

    @Autowired
    private com.educore.api.people.PeoplePayrollBridge payrollPeople;

    @Autowired
    private PayrollPayslipService payrollPayslips;

    @Autowired
    private ParentRequestService parentRequests;

    @MockitoBean
    private OfficialDocumentObjectStore officialDocumentObjectStore;

    @MockitoBean
    private PayrollPayslipObjectStore payrollPayslipObjectStore;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager queueTransactionManager;

    @Autowired
    private com.educore.api.communication.PushSubscriptionProtector pushSubscriptionProtector;

    @Autowired
    private com.educore.api.schoolcalendar.PublishedSchoolCalendarService publishedSchoolCalendars;
    @Autowired private com.educore.api.ptm.PtmNotificationSource ptmNotificationSource;
    @Autowired private com.educore.api.parentrequests.ParentRequestNotificationSource requestNotificationSource;
    @Autowired private com.educore.api.attendance.AttendanceService attendanceService;
    @Autowired private com.educore.api.parentrequests.ParentRequestNotificationProcessor requestNotifications;
    @Autowired private com.educore.api.communication.ParentRequestPublicationService requestPublications;

    @Autowired
    private com.educore.api.communication.PushOperationsService pushOperations;

    @Test
    @Transactional
    void pushOperationsRequireLiveAuthorityAndAuditVersionedProviderLifecycleWithoutSecrets() {
        var f=insertPushQueueFixture("c1000000");
        var principal=pushOperationsPrincipal(f);
        var initial=pushOperations.snapshot(principal,f.school());
        assertThat(initial.providerStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(initial.transportEnabled()).isFalse();
        assertThat(initial.workerEnabled()).isFalse();
        assertThat(initial.parentStates()).contains(new com.educore.api.communication.PushOperationsResponse.StateCount("READY",1));
        var activated=pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"ACTIVE",null,"Synthetic principal push approval"));
        assertThat(activated.providerStatus()).isEqualTo("ACTIVE");
        assertThat(activated.providerVersion()).isZero();
        assertThat(activated.providerCompatible()).isTrue();
        assertThat(activated.toString()).doesNotContain("env:","https://","ciphertext","Synthetic queue guardian");
        assertThatThrownBy(() -> pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"ACTIVE",null,"Synthetic repeated first approval"))).hasMessageContaining("Refresh");
        var suspended=pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"SUSPENDED",0L,"Synthetic controlled suspension"));
        assertThat(suspended.providerVersion()).isEqualTo(1);
        assertThat(suspended.providerStatus()).isEqualTo("SUSPENDED");
        assertThat(pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"SUSPENDED",1L,"Synthetic same-state replay")).providerVersion()).isEqualTo(1);
        assertThatThrownBy(() -> pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"ACTIVE",0L,"Synthetic stale resume"))).hasMessageContaining("Refresh");
        assertThat(pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"ACTIVE",1L,"Synthetic principal resume")).providerVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=? AND actor_user_id=?
                  AND action IN ('communication.push_provider.approved','communication.push_provider.suspended')
                """,Integer.class,f.org(),f.school(),principal.userId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_provider_configs WHERE organisation_id=? AND school_id=? AND channel='PUSH' AND environment='LIVE'
                """,Integer.class,f.org(),f.school())).isEqualTo(1);
        assertThatThrownBy(() -> pushOperations.snapshot(principal,UUID.randomUUID())).hasMessageContaining("unavailable");
        var noMfa=new AuthenticatedUser(principal.userId(),principal.sessionId(),principal.loginIdentifier(),principal.email(),
                principal.displayName(),false,principal.activeOrganisationId(),principal.membershipIds(),principal.capabilities(),List.of());
        assertThatThrownBy(() -> pushOperations.changeProvider(noMfa,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"SUSPENDED",2L,"Synthetic missing MFA"))).hasMessageContaining("MFA");
        var forgedOrg=new AuthenticatedUser(principal.userId(),principal.sessionId(),principal.loginIdentifier(),principal.email(),
                principal.displayName(),true,UUID.randomUUID(),principal.membershipIds(),principal.capabilities(),List.of());
        assertThatThrownBy(() -> pushOperations.snapshot(forgedOrg,f.school())).hasMessageContaining("unavailable");
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?",f.portal().staffMembershipId());
        assertThatThrownBy(() -> pushOperations.snapshot(principal,f.school())).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"SUSPENDED",2L,"Synthetic revoked authority"))).hasMessageContaining("unavailable");
    }

    @Test
    @Transactional
    void pushOperationsRejectIncompatibleMappingsAndUnapprovedSuspension() {
        var f=insertPushQueueFixture("c2000000");
        var principal=pushOperationsPrincipal(f);
        UUID provider=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,status,created_at,updated_at)
                VALUES(?,?,?,'PUSH','LIVE','OTHER_PROVIDER','INACTIVE',now(),now())
                """,provider,f.org(),f.school());
        assertThat(pushOperations.snapshot(principal,f.school()).providerCompatible()).isFalse();
        assertThatThrownBy(() -> pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"ACTIVE",0L,"Synthetic incompatible mapping"))).hasMessageContaining("will not be overwritten");
        assertThatThrownBy(() -> pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                f.school(),"SUSPENDED",0L,"Synthetic unapproved suspension"))).hasMessageContaining("No live push provider");
        assertThat(jdbc.queryForMap("SELECT provider_code,status,row_version FROM communication_provider_configs WHERE id=?",provider))
                .containsEntry("provider_code","OTHER_PROVIDER").containsEntry("status","INACTIVE").containsEntry("row_version",0L);
    }

    @Test
    @Transactional
    void pushOperationsApprovalReleasesOnlyEligibleUnassignedDeliveriesAndPreservesHistory() {
        var f=insertPushQueueFixture("c3000000");
        var principal=pushOperationsPrincipal(f);
        var dispatch=pushDispatch(f);
        UUID awaiting=insertAwaitingPushDelivery(f);
        UUID originalProvider=jdbc.queryForObject("SELECT provider_config_id FROM communication_deliveries WHERE id=?",UUID.class,f.delivery());
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
            pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                    f.school(),"ACTIVE",null,"Synthetic principal delivery approval"));
            assertThat(dispatch.materializeNext(f.org(),UUID.randomUUID())).isNull();
            assertThat(dispatch.materializeNext(f.org(),f.school())).isEqualTo(awaiting);
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM communication_deliveries WHERE id=?",String.class,awaiting)).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_jobs WHERE communication_delivery_id=?",Integer.class,awaiting)).isEqualTo(2);
            assertThat(jdbc.queryForMap("SELECT status,provider_config_id FROM communication_deliveries WHERE id=?",f.delivery()))
                    .containsEntry("status","READY").containsEntry("provider_config_id",originalProvider);
            UUID held=insertAwaitingPushDelivery(f);
            pushOperations.changeProvider(principal,new com.educore.api.communication.PushOperationsController.ProviderChange(
                    f.school(),"SUSPENDED",0L,"Synthetic temporary provider suspension"));
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
            assertThat(jdbc.queryForMap("SELECT status,provider_config_id FROM communication_deliveries WHERE id=?",held))
                    .containsEntry("status","AWAITING_PROVIDER").containsEntry("provider_config_id",null);
            f.clock().advance(86401);
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
            assertThat(Set.of(dispatch.reconcileNext(f.org(),f.school()),dispatch.reconcileNext(f.org(),f.school())))
                    .containsExactlyInAnyOrder(f.delivery(),held);
            assertThat(jdbc.queryForObject("SELECT status FROM communication_deliveries WHERE id=?",String.class,held)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_jobs WHERE communication_delivery_id=?",Integer.class,held)).isZero();
        }
    }

    private UUID insertAwaitingPushDelivery(PushQueueFixture f) {
        var portal=f.portal();
        UUID notice=UUID.randomUUID(),recipient=UUID.randomUUID(),delivery=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  language_mode,title_en,body_en,request_push,scheduled_for,status,created_by_membership_id,
                  submitted_by_membership_id,submitted_at,decided_by_membership_id,decided_at,decision_reason,created_at,updated_at)
                VALUES(?,?,?,'GENERAL','NORMAL','ALL_FAMILIES','ENGLISH','Synthetic pending notice','Synthetic pending body',
                  true,now(),'APPROVED',?,?,now(),?,now(),'Synthetic independent approval',now(),now())
                """,notice,f.org(),f.school(),portal.staffMembershipId(),portal.staffMembershipId(),portal.guardianMembershipId());
        jdbc.update("""
                INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                  guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                VALUES(?,?,?,?,'GUARDIAN',?,?,?,'EN',true,now(),now())
                """,recipient,f.org(),f.school(),notice,portal.guardianId(),portal.learnerId(),portal.guardianUserId());
        jdbc.update("""
                INSERT INTO communication_deliveries(id,organisation_id,school_id,communication_recipient_id,channel,status,created_at,updated_at)
                VALUES(?,?,?,?,'PUSH','AWAITING_PROVIDER',now(),now())
                """,delivery,f.org(),f.school(),recipient);
        return delivery;
    }

    private AuthenticatedUser pushOperationsPrincipal(PushQueueFixture f) {
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",f.portal().staffMembershipId(),
                UUID.fromString("00000000-0000-4000-8000-000000000007"));
        return new AuthenticatedUser(f.portal().staffUserId(),UUID.randomUUID(),"push-principal@example.test","push-principal@example.test",
                "Synthetic push principal",true,f.org(),Set.of(f.portal().staffMembershipId()),Set.of("communication.approve"),List.of());
    }

    @Test
    @Transactional
    void pushDispatchFreezesDevicesAndReconcilesPartialAcceptanceWithoutResending() {
        var f=insertPushQueueFixture("b1000000");
        var dispatch=pushDispatch(f);
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull(); // TEST is never a live activation.
            activateSyntheticPushProvider(f);
            assertThat(dispatch.materializeNext(f.org(),UUID.randomUUID())).isNull();
            assertThat(dispatch.materializeNext(f.org(),f.school())).isEqualTo(f.delivery());
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_jobs WHERE communication_delivery_id=?",
                    Integer.class,f.delivery())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM communication_deliveries WHERE id=?",String.class,f.delivery())).isEqualTo("QUEUED");
            var accepted=f.queue().claimNext(f.org(),f.school());
            assertThat(f.queue().complete(accepted,pushResult(PushTransport.Outcome.ACCEPTED,201))).isTrue();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isEqualTo(f.delivery());
            var sentAt=jdbc.queryForObject("SELECT sent_at FROM communication_deliveries WHERE id=?",java.sql.Timestamp.class,f.delivery());
            assertThat(sentAt).isNotNull();
            assertThat(jdbc.queryForObject("SELECT status FROM communication_deliveries WHERE id=?",String.class,f.delivery())).isEqualTo("SENT");
            var second=f.queue().claimNext(f.org(),f.school());
            assertThat(f.queue().complete(second,pushResult(PushTransport.Outcome.RETRYABLE_FAILURE,503))).isTrue();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isNull();
            f.clock().advance(300);
            var retry=f.queue().claimNext(f.org(),f.school());
            assertThat(retry.jobId()).isEqualTo(second.jobId()).isNotEqualTo(accepted.jobId());
            assertThat(f.queue().complete(retry,pushResult(PushTransport.Outcome.PERMANENT_FAILURE,400))).isTrue();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isEqualTo(f.delivery());
            assertThat(jdbc.queryForMap("SELECT status,sent_at,delivered_at,last_error_code FROM communication_deliveries WHERE id=?",f.delivery()))
                    .containsEntry("status","SENT").containsEntry("sent_at",sentAt).containsEntry("delivered_at",null)
                    .containsEntry("last_error_code","PUSH_PARTIAL_FAILURE");
            assertThat(pushDispatch(f).reconcileNext(f.org(),f.school())).isNull(); // Restart/replay is a no-op.
            assertThat(f.queue().claimNext(f.org(),f.school())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_delivery_attempts WHERE communication_delivery_id=?",
                    Integer.class,f.delivery())).isZero(); // No fabricated legacy parent-provider calls.
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(() -> dispatch.materializeNext(f.org(),f.school())).hasMessageContaining("organisation context");
                assertThatThrownBy(() -> dispatch.reconcileNext(f.org(),f.school())).hasMessageContaining("organisation context");
            }
        }
    }

    @Test
    @Transactional
    void pushDispatchReconcilesAllFailedDevicesWithoutFalseSentOrDeliveredEvidence() {
        var f=insertPushQueueFixture("b2000000");
        var dispatch=pushDispatch(f);
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            activateSyntheticPushProvider(f);
            assertThat(dispatch.materializeNext(f.org(),f.school())).isEqualTo(f.delivery());
            for(int i=0;i<2;i++) assertThat(f.queue().complete(f.queue().claimNext(f.org(),f.school()),
                    pushResult(PushTransport.Outcome.PERMANENT_FAILURE,400))).isTrue();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isEqualTo(f.delivery());
            var row=jdbc.queryForMap("SELECT status,sent_at,delivered_at,failed_at,last_error_code FROM communication_deliveries WHERE id=?",f.delivery());
            assertThat(row).containsEntry("status","FAILED").containsEntry("sent_at",null).containsEntry("delivered_at",null)
                    .containsEntry("last_error_code","PUSH_ALL_DEVICES_FAILED");
            assertThat(row.get("failed_at")).isNotNull();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isNull();
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
        }
    }

    @Test
    @Transactional
    void pushDispatchCancelsExpiredUnmaterializedNoticesWithoutProviderAttempts() {
        var f=insertPushQueueFixture("b3000000");
        var dispatch=pushDispatch(f);
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            f.clock().advance(86401);
            activateSyntheticPushProvider(f);
            assertThat(dispatch.materializeNext(f.org(),f.school())).isNull();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isEqualTo(f.delivery());
            assertThat(jdbc.queryForMap("SELECT status,sent_at,delivered_at,failed_at FROM communication_deliveries WHERE id=?",f.delivery()))
                    .containsEntry("status","CANCELLED").containsEntry("sent_at",null).containsEntry("delivered_at",null)
                    .containsEntry("failed_at",null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_jobs WHERE communication_delivery_id=?",
                    Integer.class,f.delivery())).isZero();
            assertThat(dispatch.reconcileNext(f.org(),f.school())).isNull();
        }
    }

    private com.educore.api.communication.PushDeliveryDispatch pushDispatch(PushQueueFixture f) {
        return new com.educore.api.communication.PushDeliveryDispatch(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),f.clock());
    }

    private void activateSyntheticPushProvider(PushQueueFixture f) {
        jdbc.update("""
                UPDATE communication_provider_configs SET environment='LIVE',credential_secret_reference='env:APP_PUSH_DELIVERY_PRIVATE_KEY'
                WHERE id=(SELECT provider_config_id FROM communication_deliveries WHERE id=?)
                """,f.delivery());
    }

    @Test
    @Transactional
    void pushCompletionExpiresOnlyTheUnchangedDeviceWithSystemProviderEvidence() {
        var f=insertPushQueueFixture("a8000000");
        var completion=new com.educore.api.communication.PushDeliveryCompletion(f.queue(),
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),f.clock());
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            for(UUID device:f.devices()) f.queue().enqueue(f.org(),f.school(),f.delivery(),device,f.clock().instant().plusSeconds(7200));
            var claim=f.queue().claimNext(f.org(),f.school());
            assertThat(completion.complete(claim,pushResult(PushTransport.Outcome.SUBSCRIPTION_EXPIRED,410),0)).isTrue();
            assertThat(jdbc.queryForMap("""
                    SELECT status,endpoint_ciphertext,p256dh_ciphertext,auth_ciphertext,row_version
                    FROM communication_push_subscriptions WHERE id=?
                    """,claim.subscriptionId())).containsEntry("status","EXPIRED").containsEntry("endpoint_ciphertext",null)
                    .containsEntry("p256dh_ciphertext",null).containsEntry("auth_ciphertext",null).containsEntry("row_version",1L);
            UUID event=jdbc.queryForObject("""
                    SELECT id FROM communication_push_subscription_events WHERE provider_attempt_id=?
                      AND actor_kind='SYSTEM' AND actor_user_id IS NULL AND actor_membership_id IS NULL
                      AND subscription_version=0 AND event_type='EXPIRED'
                    """,UUID.class,claim.token());
            assertThat(event).isNotNull();
            assertThat(completion.complete(claim,pushResult(PushTransport.Outcome.SUBSCRIPTION_EXPIRED,410),0)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_subscription_events WHERE provider_attempt_id=?",
                    Integer.class,claim.token())).isEqualTo(1);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
            assertDatabaseRejected(() -> jdbc.update("DELETE FROM communication_push_subscription_events WHERE id=?",event),
                    "append-only");
            var refreshed=f.queue().claimNext(f.org(),f.school());
            f.clock().advance(1);
            jdbc.update("UPDATE communication_push_subscriptions SET last_confirmed_at=?,row_version=row_version+1 WHERE id=?",
                    java.sql.Timestamp.from(f.clock().instant()),refreshed.subscriptionId());
            assertThat(completion.complete(refreshed,pushResult(PushTransport.Outcome.SUBSCRIPTION_EXPIRED,404),0)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM communication_push_subscriptions WHERE id=?",
                    String.class,refreshed.subscriptionId())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_subscription_events WHERE provider_attempt_id=?",
                    Integer.class,refreshed.token())).isZero();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(() -> completion.complete(claim,pushResult(PushTransport.Outcome.ACCEPTED,201),0))
                        .hasMessageContaining("organisation context");
            }
        }
    }

    @Test
    @Transactional
    void appliesV045AndRejectsExpiryWithoutMatchingProviderAndLifecycleEvidence() {
        assertThat(Arrays.stream(flyway.info().applied()).map(migration -> migration.getScript()).toList())
                .contains("V045__system_attributed_push_subscription_expiry.sql");
        var f=insertPushQueueFixture("a9000000");
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            f.queue().enqueue(f.org(),f.school(),f.delivery(),f.devices().getFirst(),f.clock().instant().plusSeconds(7200));
            var claim=f.queue().claimNext(f.org(),f.school());
            assertThat(f.queue().complete(claim,pushResult(PushTransport.Outcome.ACCEPTED,201))).isTrue();
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_push_subscription_events(id,organisation_id,school_id,
                      communication_push_subscription_id,event_type,actor_kind,provider_attempt_id,subscription_version,occurred_at)
                    VALUES(?,?,?,?,'EXPIRED','SYSTEM',?,0,clock_timestamp())
                    """,UUID.randomUUID(),f.org(),f.school(),claim.subscriptionId(),claim.token()),
                    "system push expiry requires matching completed provider and device evidence");
            assertDatabaseRejected(() -> {
                jdbc.update("""
                        UPDATE communication_push_subscriptions SET status='EXPIRED',endpoint_ciphertext=NULL,endpoint_nonce=NULL,
                          p256dh_ciphertext=NULL,p256dh_nonce=NULL,auth_ciphertext=NULL,auth_nonce=NULL,
                          revoked_at=clock_timestamp(),revoked_reason='Synthetic unrecorded expiry',row_version=row_version+1
                        WHERE id=?
                        """,claim.subscriptionId());
                jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            },"push subscription expiry must preserve lifecycle evidence");
            assertThat(jdbc.queryForObject("SELECT status FROM communication_push_subscriptions WHERE id=?",
                    String.class,claim.subscriptionId())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM communication_push_subscription_events WHERE communication_push_subscription_id=?
                      AND actor_kind='HUMAN' AND actor_user_id IS NOT NULL AND actor_membership_id IS NOT NULL
                      AND provider_attempt_id IS NULL AND event_type='REGISTERED'
                    """,Integer.class,claim.subscriptionId())).isEqualTo(1);
        }
    }

    @Test
    @Transactional
    void pushPreparationRevalidatesConsentProviderFamilyScopeAndSourceBeforeDecryption() {
        var f=insertPushQueueFixture("a6000000");
        UUID campus=UUID.randomUUID(),session=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO campuses(id,organisation_id,school_id,code,display_name,created_at,updated_at)
                VALUES(?,?,?,'SYN-PUSH','Synthetic push campus',now(),now())
                """,campus,f.org(),f.school());
        jdbc.update("""
                INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at)
                VALUES(?,?,?,'Synthetic push session',CURRENT_DATE-180,CURRENT_DATE+180,'PLANNED',now(),now())
                """,session,f.org(),f.school());
        jdbc.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                  origin_application_id,grade_code,status,starts_on,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'VI','ACTIVE',CURRENT_DATE,now(),now())
                """,UUID.randomUUID(),f.org(),f.school(),campus,session,f.portal().learnerId(),f.portal().applicationId());
        var preparation=new com.educore.api.communication.PushDeliveryPreparation(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),pushSubscriptionProtector,f.clock(),publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            f.queue().enqueue(f.org(),f.school(),f.delivery(),f.devices().getFirst(),f.clock().instant().plusSeconds(7200));
            var claim=f.queue().claimNext(f.org(),f.school());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_PROVIDER_NOT_READY");
            jdbc.update("""
                    UPDATE communication_provider_configs SET environment='LIVE',credential_secret_reference='env:APP_PUSH_DELIVERY_PRIVATE_KEY'
                    WHERE id=(SELECT provider_config_id FROM communication_deliveries WHERE id=?)
                    """,f.delivery());
            var ready=preparation.prepare(claim);
            assertThat(ready.action()).isEqualTo(com.educore.api.communication.PushDeliveryPreparation.Action.SEND);
            assertThat(ready.message().endpoint()).startsWith("https://push.example.org/queue/");
            assertThat(ready.message().payload()).contains("New school notice").doesNotContain("Synthetic",f.portal().learnerId().toString());
            assertThat(ready.toString()).doesNotContain("https://","New school notice");
            assertThat(ready.message().topic()).hasSize(32);
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=false WHERE school_id=? AND user_account_id=?",
                    f.school(),f.portal().guardianUserId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_CONSENT_WITHDRAWN");
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=true,preferred_language='HI' WHERE school_id=? AND user_account_id=?",
                    f.school(),f.portal().guardianUserId());
            assertThat(preparation.prepare(claim).message().payload()).contains("स्कूल की नई सूचना");
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE guardian_id=? AND learner_id=?",
                    f.portal().guardianId(),f.portal().learnerId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_RECIPIENT_UNAVAILABLE");
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE' WHERE guardian_id=? AND learner_id=?",
                    f.portal().guardianId(),f.portal().learnerId());
            jdbc.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",f.portal().guardianMembershipId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_RECIPIENT_UNAVAILABLE");
            jdbc.update("UPDATE memberships SET status='ACTIVE' WHERE id=?",f.portal().guardianMembershipId());
            jdbc.update("UPDATE student_enrolments SET status='ENDED' WHERE learner_id=?",f.portal().learnerId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_RECIPIENT_UNAVAILABLE");
            jdbc.update("UPDATE student_enrolments SET status='ACTIVE' WHERE learner_id=?",f.portal().learnerId());
            var local=f.clock().instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime();
            jdbc.update("""
                    UPDATE communication_contact_preferences SET quiet_hours_start=?,quiet_hours_end=?
                    WHERE school_id=? AND user_account_id=?
                    """,local.minusMinutes(30),local.plusMinutes(30),f.school(),f.portal().guardianUserId());
            var quiet=preparation.prepare(claim);
            assertThat(quiet.safeCode()).isEqualTo("PUSH_QUIET_HOURS");
            assertThat(quiet.retryAfterSeconds()).isBetween(1799L,1801L);
            assertThat(quiet.message()).isNull();
            jdbc.update("UPDATE communication_contact_preferences SET quiet_hours_start=NULL,quiet_hours_end=NULL WHERE school_id=? AND user_account_id=?",
                    f.school(),f.portal().guardianUserId());
            var forged=new PushDeliveryQueue.Claim(f.org(),UUID.randomUUID(),claim.jobId(),claim.deliveryId(),claim.subscriptionId(),
                    claim.token(),claim.startedAt(),claim.leaseUntil(),claim.expiresAt(),claim.attemptNumber());
            assertThat(preparation.prepare(forged).safeCode()).isEqualTo("PUSH_CLAIM_UNAVAILABLE");
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(() -> preparation.prepare(claim)).hasMessageContaining("organisation context");
            }
            jdbc.update("""
                    UPDATE communication_announcements SET status='CANCELLED',cancelled_by_membership_id=?,cancelled_at=clock_timestamp(),
                      cancellation_reason='Synthetic cancellation',updated_at=clock_timestamp(),row_version=row_version+1
                    WHERE id=(SELECT recipient.communication_announcement_id FROM communication_deliveries delivery
                      JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id WHERE delivery.id=?)
                    """,f.portal().staffMembershipId(),f.delivery());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            f.clock().advance(91);
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_CLAIM_UNAVAILABLE");
        }
    }

    @Test
    @Transactional
    void pushPreparationHonoursStaffEmergencyConsentAndNeverDecryptsBlockedMessages() {
        var f=insertPushQueueFixture("a7000000");
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",
                f.portal().staffMembershipId(),UUID.fromString("00000000-0000-4000-8000-000000000003"));
        var staff=new AuthenticatedUser(f.portal().staffUserId(),UUID.randomUUID(),"push-staff@example.test",
                "push-staff@example.test","Synthetic push staff",true,f.org(),Set.of(f.portal().staffMembershipId()),
                Set.of("communication.read"),List.of());
        var device=pushNotifications.register(staff,new RegisterPushSubscriptionRequest(f.school(),
                "https://push.example.org/staff/a7000000",syntheticP256dh(),syntheticAuth(),"Synthetic staff device"))
                .devices().getFirst().id();
        jdbc.update("""
                UPDATE communication_provider_configs SET environment='LIVE',credential_secret_reference='env:APP_PUSH_DELIVERY_PRIVATE_KEY'
                WHERE id=(SELECT provider_config_id FROM communication_deliveries WHERE id=?)
                """,f.delivery());
        var local=f.clock().instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime();
        jdbc.update("""
                UPDATE communication_contact_preferences SET quiet_hours_start=?,quiet_hours_end=?,safe_contact_status='TRANSACTIONAL_ONLY'
                WHERE school_id=? AND user_account_id=?
                """,local.minusMinutes(30),local.plusMinutes(30),f.school(),f.portal().staffUserId());
        var spy=org.mockito.Mockito.spy(pushSubscriptionProtector);
        var preparation=new com.educore.api.communication.PushDeliveryPreparation(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),spy,f.clock(),publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
        try(var ignored=TenantRequestContext.open(null,f.org())) {
            var delivery=insertStaffPushPreparationNotice(f,"EMERGENCY",null,null);
            var id=f.queue().enqueue(f.org(),f.school(),delivery,device,f.clock().instant().plusSeconds(7200));
            assertThat(f.queue().enqueue(f.org(),f.school(),delivery,device,f.clock().instant().plusSeconds(7200))).isEqualTo(id);
            var claim=f.queue().claimNext(f.org(),f.school());
            assertThat(preparation.prepare(claim).message().urgent()).isTrue();
            org.mockito.Mockito.clearInvocations(spy);
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=false WHERE school_id=? AND user_account_id=?",
                    f.school(),f.portal().staffUserId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_CONSENT_WITHDRAWN");
            org.mockito.Mockito.verifyNoInteractions(spy);
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=true,quiet_hours_start=NULL,quiet_hours_end=NULL WHERE school_id=? AND user_account_id=?",
                    f.school(),f.portal().staffUserId());
            jdbc.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",f.portal().staffMembershipId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_RECIPIENT_UNAVAILABLE");
            org.mockito.Mockito.verifyNoInteractions(spy);
            jdbc.update("UPDATE memberships SET status='ACTIVE' WHERE id=?",f.portal().staffMembershipId());
            org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic key failure; must not escape"))
                    .when(spy).reveal(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_DECRYPTION_UNAVAILABLE");
            assertThat(f.queue().cancel(claim,"PUSH_TEST_CANCELLED")).isTrue();
            org.mockito.Mockito.clearInvocations(spy);
            for(String source:List.of("SCHOOL_CALENDAR_NOTICE","UNSUPPORTED_SOURCE")) {
                var invalid=insertStaffPushPreparationNotice(f,"EMERGENCY",source,UUID.randomUUID());
                f.queue().enqueue(f.org(),f.school(),invalid,device,f.clock().instant().plusSeconds(7200));
                var invalidClaim=f.queue().claimNext(f.org(),f.school());
                assertThat(preparation.prepare(invalidClaim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
                org.mockito.Mockito.verifyNoInteractions(spy);
                assertThat(f.queue().cancel(invalidClaim,"PUSH_SOURCE_UNAVAILABLE")).isTrue();
            }
        }
    }

    private UUID insertStaffPushPreparationNotice(PushQueueFixture f,String category,String source,UUID sourceId) {
        UUID notice=UUID.randomUUID(),recipient=UUID.randomUUID(),delivery=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  language_mode,title_en,body_en,request_push,scheduled_for,status,created_by_membership_id,
                  submitted_by_membership_id,submitted_at,decided_by_membership_id,decided_at,decision_reason,created_at,updated_at,
                  source_type,source_id)
                VALUES(?,?,?,?,?,'ALL_STAFF','ENGLISH','Synthetic notice','Synthetic private text',
                  true,now(),'APPROVED',?,?,now(),?,now(),'Synthetic independent approval',now(),now(),?,?)
                """,notice,f.org(),f.school(),category,"EMERGENCY".equals(category)?"EMERGENCY":"NORMAL",
                f.portal().staffMembershipId(),f.portal().staffMembershipId(),f.portal().guardianMembershipId(),source,sourceId);
        jdbc.update("""
                INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                  membership_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                VALUES(?,?,?,?,'STAFF',?,?,'EN',true,now(),now())
                """,recipient,f.org(),f.school(),notice,f.portal().staffMembershipId(),f.portal().staffUserId());
        jdbc.update("""
                INSERT INTO communication_deliveries(id,organisation_id,school_id,communication_recipient_id,channel,
                  status,provider_config_id,created_at,updated_at)
                SELECT ?,?,?,?,'PUSH','READY',provider_config_id,now(),now() FROM communication_deliveries WHERE id=?
                """,delivery,f.org(),f.school(),recipient,f.delivery());
        return delivery;
    }

    @Test
    void pushQueueConcurrentWorkersSkipLockedDevicesWithoutDuplicateClaims() throws Exception {
        // A separate synthetic organisation keeps this necessarily committed multi-connection test
        // independent of other school fixtures. No production data or outbound provider is involved.
        var tx=new org.springframework.transaction.support.TransactionTemplate(queueTransactionManager);
        var f=tx.execute(status -> {
            UUID org=UUID.randomUUID(),school=UUID.randomUUID();
            String key="queue-concurrent-"+UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at)
                    VALUES(?,?,'Synthetic Queue Organisation','Synthetic Queue Organisation',now(),now())
                    """,org,key);
            jdbc.update("""
                    INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at)
                    VALUES(?,?,'queue-school','Synthetic Queue School','Synthetic Queue School',now(),now())
                    """,school,org);
            var portal=new PortalFixture(org,school,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                    UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            var fixture=insertPushQueueFixture(key,portal);
            try(var ignored=TenantRequestContext.open(null,org)) {
                fixture.queue().enqueue(org,school,fixture.delivery(),fixture.devices().get(0),fixture.clock().instant().plusSeconds(7200));
            }
            return fixture;
        });
        var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> {
                try(var ignored=TenantRequestContext.open(null,f.org())) {
                    return tx.execute(status -> {
                        var claim=f.queue().claimNext(f.org(),f.school());
                        locked.countDown();
                        try {
                            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test claim wait expired");
                        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        return claim;
                    });
                }
            });
            try {
                assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second=executor.submit(() -> {
                    try(var ignored=TenantRequestContext.open(null,f.org())) {
                        return tx.execute(status -> f.queue().claimNext(f.org(),f.school()));
                    }
                });
                assertThat(second.get(3,java.util.concurrent.TimeUnit.SECONDS)).isNull();
            } finally { release.countDown(); }
            var claim=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(claim).isNotNull();
            assertThat(claim.attemptNumber()).isEqualTo(1);
            try(var ignored=TenantRequestContext.open(null,f.org())) {
                Boolean completed=tx.execute(status -> f.queue().complete(claim,pushResult(PushTransport.Outcome.ACCEPTED,201)));
                assertThat(completed).isTrue();
            }
        }
    }

    @Test
    @Transactional
    void pushQueueRetriesDevicesIndependentlyAndFencesCompletedClaims() {
        var f = insertPushQueueFixture("a1000000");
        var q = f.queue();
        try (var ignored = TenantRequestContext.open(null, f.portal().organisationId())) {
            var expiry = f.clock().instant().plusSeconds(7200);
            UUID first = q.enqueue(f.org(), f.school(), f.delivery(), f.devices().get(0), expiry);
            UUID second = q.enqueue(f.org(), f.school(), f.delivery(), f.devices().get(1), expiry);
            assertThat(first).isNotNull().isNotEqualTo(second);
            assertThat(q.enqueue(f.org(),f.school(),f.delivery(),f.devices().get(0),expiry)).isEqualTo(first);
            var accepted = q.claimNext(f.org(),f.school());
            var retried = q.claimNext(f.org(),f.school());
            assertThat(accepted.jobId()).isNotEqualTo(retried.jobId());
            assertThat(q.claimNext(f.org(),f.school())).isNull();
            assertThat(q.complete(accepted, pushResult(PushTransport.Outcome.ACCEPTED,201))).isTrue();
            assertThat(q.complete(accepted, pushResult(PushTransport.Outcome.ACCEPTED,201))).isFalse();
            assertThat(q.complete(retried, pushResult(PushTransport.Outcome.RETRYABLE_FAILURE,503))).isTrue();
            assertThat(q.claimNext(f.org(),f.school())).isNull();
            f.clock().advance(180);
            var next = q.claimNext(f.org(),f.school());
            assertThat(next.jobId()).isEqualTo(retried.jobId());
            assertThat(next.token()).isNotEqualTo(retried.token());
            assertThat(next.attemptNumber()).isEqualTo(2);
            assertThat(q.complete(retried,pushResult(PushTransport.Outcome.ACCEPTED,201))).isFalse();
            assertThat(q.complete(next,pushResult(PushTransport.Outcome.ACCEPTED,201))).isTrue();
            assertThat(q.claimNext(f.org(),f.school())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_attempts WHERE communication_push_job_id IN (?,?)",
                    Integer.class,first,second)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_jobs WHERE id IN (?,?) AND status='ACCEPTED'",
                    Integer.class,first,second)).isEqualTo(2);
            assertDatabaseRejected(() -> jdbc.update("UPDATE communication_push_jobs SET status='READY' WHERE id=?",first), "terminal");
            assertDatabaseRejected(() -> jdbc.update("DELETE FROM communication_push_jobs WHERE id=?",first), "cannot be deleted");
            assertDatabaseRejected(() -> jdbc.update("DELETE FROM communication_push_attempts WHERE communication_push_job_id=?",first), "append-only");
        }
    }

    @Test
    @Transactional
    void pushQueueRecoversUnknownOutcomesBoundsRetriesAndExpiresPendingMessages() {
        var f = insertPushQueueFixture("a2000000");
        var q = f.queue();
        try (var ignored = TenantRequestContext.open(null, f.org())) {
            UUID job = q.enqueue(f.org(),f.school(),f.delivery(),f.devices().get(0),f.clock().instant().plusSeconds(85000));
            for (int attempt=1;attempt<=8;attempt++) {
                var claim = q.claimNext(f.org(),f.school());
                assertThat(claim).isNotNull();
                assertThat(claim.attemptNumber()).isEqualTo(attempt);
                assertThat(q.recoverExpiredLease(f.org(),f.school())).isNull();
                f.clock().advance(91);
                assertThat(q.complete(claim,pushResult(PushTransport.Outcome.ACCEPTED,201))).isFalse();
                assertThat(q.recoverExpiredLease(f.org(),f.school())).isEqualTo(job);
                assertThat(q.recoverExpiredLease(f.org(),f.school())).isNull();
                assertThat(q.claimNext(f.org(),f.school())).isNull();
                f.clock().advance(4000);
            }
            assertThat(jdbc.queryForObject("SELECT status FROM communication_push_jobs WHERE id=?",String.class,job)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_attempts WHERE communication_push_job_id=? AND outcome='LEASE_EXPIRED'",
                    Integer.class,job)).isEqualTo(8);
            assertThat(q.claimNext(f.org(),f.school())).isNull();
            UUID expired = q.enqueue(f.org(),f.school(),f.delivery(),f.devices().get(1),f.clock().instant().plusSeconds(30));
            f.clock().advance(31);
            assertThat(q.claimNext(f.org(),f.school())).isNull();
            assertThat(q.expirePending(f.org(),f.school())).isEqualTo(expired);
            assertThat(q.expirePending(f.org(),f.school())).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM communication_push_jobs WHERE id=?",String.class,expired)).isEqualTo("CANCELLED");
        }
    }

    @Test
    @Transactional
    void pushQueueRejectsMissingScopeMismatchedDevicesAndForgedAttemptEvidence() {
        var f = insertPushQueueFixture("a3000000");
        var q = f.queue();
        assertThatThrownBy(() -> q.claimNext(f.org(),f.school())).hasMessageContaining("organisation context");
        try (var ignored = TenantRequestContext.open(null,UUID.randomUUID())) {
            assertThatThrownBy(() -> q.claimNext(f.org(),f.school())).hasMessageContaining("organisation context");
        }
        try (var ignored = TenantRequestContext.open(null,f.org())) {
            var expiry=f.clock().instant().plusSeconds(7200);
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",f.portal().staffMembershipId(),
                    UUID.fromString("00000000-0000-4000-8000-000000000006"));
            var other=new AuthenticatedUser(f.portal().staffUserId(),UUID.randomUUID(),"other-queue@example.test",
                    "other-queue@example.test","Synthetic other account",true,f.org(),Set.of(f.portal().staffMembershipId()),Set.of("portal.guardian"),List.of());
            UUID otherDevice=pushNotifications.register(other,new RegisterPushSubscriptionRequest(f.school(),
                    "https://push.example.org/queue/other-account",syntheticP256dh(),syntheticAuth(),"Other synthetic phone")).devices().getFirst().id();
            assertThat(q.enqueue(f.org(),f.school(),f.delivery(),otherDevice,expiry)).isNull();
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_push_jobs(id,organisation_id,school_id,communication_delivery_id,
                      communication_push_subscription_id,next_attempt_at,expires_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,now(),now()+interval '1 hour',now(),now())
                    """,UUID.randomUUID(),f.org(),f.school(),f.delivery(),otherDevice),"eligible recipient");
            assertThat(q.enqueue(f.org(),UUID.randomUUID(),f.delivery(),f.devices().get(0),expiry)).isNull();
            assertThat(q.enqueue(f.org(),f.school(),f.delivery(),UUID.randomUUID(),expiry)).isNull();
            assertThatThrownBy(() -> q.enqueue(f.org(),f.school(),f.delivery(),f.devices().get(0),f.clock().instant()))
                    .hasMessageContaining("expiry");
            UUID job=q.enqueue(f.org(),f.school(),f.delivery(),f.devices().get(0),expiry);
            var claim=q.claimNext(f.org(),f.school());
            assertDatabaseRejected(() -> jdbc.update("""
                    UPDATE communication_push_jobs SET status='ACCEPTED',completed_at=now(),lease_token=NULL,
                      lease_started_at=NULL,lease_until=NULL,row_version=row_version+1 WHERE id=?
                    """,job),"matching immutable attempt evidence");
            assertThatThrownBy(() -> q.complete(claim,new PushTransport.Result(PushTransport.Outcome.ACCEPTED,null,"PUSH_ACCEPTED",0)))
                    .hasMessageContaining("provider evidence");
            assertThatThrownBy(() -> q.cancel(claim,"https://private-endpoint.example.org"))
                    .hasMessageContaining("destination-free");
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_push_attempts(id,organisation_id,school_id,communication_push_job_id,
                      attempt_number,started_at,finished_at,outcome,http_status,safe_error_code)
                    VALUES(?,?,?,?,1,now(),now(),'ACCEPTED',201,'PUSH_ACCEPTED')
                    """,UUID.randomUUID(),f.org(),f.school(),job),"current lease");
            assertDatabaseRejected(() -> {
                jdbc.update("""
                        INSERT INTO communication_push_attempts(id,organisation_id,school_id,communication_push_job_id,
                          attempt_number,started_at,finished_at,outcome,http_status,safe_error_code)
                        VALUES(?,?,?,?,1,?,?,'ACCEPTED',201,'PUSH_ACCEPTED')
                        """,claim.token(),f.org(),f.school(),job,java.sql.Timestamp.from(claim.startedAt()),java.sql.Timestamp.from(claim.startedAt()));
                jdbc.execute("SET CONSTRAINTS communication_push_attempt_result_completed IMMEDIATE");
            },"must complete its job");
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_push_attempts(id,organisation_id,school_id,communication_push_job_id,
                      attempt_number,started_at,finished_at,outcome,http_status,safe_error_code)
                    VALUES(?,?,?,?,1,?,?,'ACCEPTED',201,'PUSH_ACCEPTED')
                    """,claim.token(),f.org(),f.school(),job,java.sql.Timestamp.from(claim.startedAt()),
                    java.sql.Timestamp.from(claim.leaseUntil().plusSeconds(1))),"current lease");
            assertThat(q.cancel(claim,"PUSH_CONSENT_REVOKED")).isTrue();
            jdbc.update("""
                    UPDATE communication_push_subscriptions SET status='REVOKED',revoked_at=clock_timestamp(),revoked_reason='Synthetic revocation',
                      endpoint_ciphertext=NULL,endpoint_nonce=NULL,p256dh_ciphertext=NULL,p256dh_nonce=NULL,
                      auth_ciphertext=NULL,auth_nonce=NULL,row_version=row_version+1 WHERE id=?
                    """,f.devices().get(1));
            assertThat(q.enqueue(f.org(),f.school(),f.delivery(),f.devices().get(1),expiry)).isNull();
        }
    }

    @Test
    @Transactional
    void appliesV044PushJobsAndProtectsTheirEvidenceUnderRowSecurity() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='044'",Boolean.class)).isTrue();
        var f=insertPushQueueFixture("a4000000");
        try (var ignored=TenantRequestContext.open(null,f.org())) {
            f.queue().enqueue(f.org(),f.school(),f.delivery(),f.devices().get(0),f.clock().instant().plusSeconds(7200));
            f.queue().complete(f.queue().claimNext(f.org(),f.school()),pushResult(PushTransport.Outcome.ACCEPTED,201));
        }
        List<String> tables=List.of("communication_push_jobs","communication_push_attempts");
        for(String table:tables) assertThat(jdbc.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname=?",Boolean.class,table)).isTrue();
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("CREATE ROLE educore_push_queue_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_push_queue_rls_test");
        for(String table:tables) jdbc.execute("GRANT SELECT,UPDATE ON "+table+" TO educore_push_queue_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_push_queue_rls_test");
        try {
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,f.org().toString());
            for(String table:tables) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isPositive();
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
            for(String table:tables) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
                assertThat(jdbc.update("UPDATE "+table+" SET school_id=school_id")).isZero();
            }
        } finally { jdbc.execute("RESET ROLE"); }
    }

    private PushQueueFixture insertPushQueueFixture(String prefix) {
        return insertPushQueueFixture(prefix,academyPortalFixture(prefix));
    }

    private PushQueueFixture insertPushQueueFixture(String prefix,PortalFixture portal) {
        insertPortalFixture(portal,"push-queue-"+prefix);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",
                portal.guardianMembershipId(),UUID.fromString("00000000-0000-4000-8000-000000000006"));
        var guardian=new AuthenticatedUser(portal.guardianUserId(),UUID.randomUUID(),"push-queue-"+prefix+"@example.test",
                "push-queue-"+prefix+"@example.test","Synthetic queue guardian",true,portal.organisationId(),
                Set.of(portal.guardianMembershipId()),Set.of("portal.guardian"),List.of());
        for(int i=0;i<2;i++) pushNotifications.register(guardian,new RegisterPushSubscriptionRequest(portal.schoolId(),
                "https://push.example.org/queue/"+prefix+"/"+i,syntheticP256dh(),syntheticAuth(),"Synthetic device"));
        List<UUID> devices=pushNotifications.status(guardian,portal.schoolId()).devices().stream().map(d->d.id()).toList();
        UUID provider=UUID.randomUUID(),notice=UUID.randomUUID(),recipient=UUID.randomUUID(),delivery=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,
                  provider_code,credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at)
                VALUES(?,?,?,'PUSH','TEST','WEB_PUSH','env:synthetic-push-key','ACTIVE',?,now(),now(),now())
                """,provider,portal.organisationId(),portal.schoolId(),portal.staffMembershipId());
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  language_mode,title_en,body_en,request_push,scheduled_for,status,created_by_membership_id,
                  submitted_by_membership_id,submitted_at,decided_by_membership_id,decided_at,decision_reason,created_at,updated_at)
                VALUES(?,?,?,'GENERAL','NORMAL','ALL_FAMILIES','ENGLISH','Synthetic notice','Synthetic approved notice',
                  true,now(),'APPROVED',?,?,now(),?,now(),'Synthetic independent approval',now(),now())
                """,notice,portal.organisationId(),portal.schoolId(),portal.staffMembershipId(),portal.staffMembershipId(),portal.guardianMembershipId());
        jdbc.update("""
                INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                  guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                VALUES(?,?,?,?,'GUARDIAN',?,?,?,'EN',true,now(),now())
                """,recipient,portal.organisationId(),portal.schoolId(),notice,portal.guardianId(),portal.learnerId(),portal.guardianUserId());
        jdbc.update("""
                INSERT INTO communication_deliveries(id,organisation_id,school_id,communication_recipient_id,channel,
                  status,provider_config_id,created_at,updated_at)
                VALUES(?,?,?,?,'PUSH','READY',?,now(),now())
                """,delivery,portal.organisationId(),portal.schoolId(),recipient,provider);
        var testClock=new PushQueueClock();
        return new PushQueueFixture(portal,delivery,devices,testClock,new PushDeliveryQueue(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),testClock));
    }

    private static PushTransport.Result pushResult(PushTransport.Outcome outcome,Integer status) {
        return new PushTransport.Result(outcome,status,"PUSH_TEST_RESULT",60);
    }
    private record PushQueueFixture(PortalFixture portal,UUID delivery,List<UUID> devices,PushQueueClock clock,PushDeliveryQueue queue) {
        UUID org(){return portal.organisationId();}
        UUID school(){return portal.schoolId();}
    }
    private static final class PushQueueClock extends java.time.Clock {
        private java.time.Instant current=java.time.Instant.now().plusSeconds(5);
        void advance(long seconds){current=current.plusSeconds(seconds);}
        @Override public java.time.Instant instant(){return current;}
        @Override public ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        @Override public java.time.Clock withZone(ZoneId zone){return java.time.Clock.fixed(current,zone);}
    }

    @Test
    void appliesThePlatformKernelMigration() {
        Integer migrationCount = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'organisations', 'schools', 'user_accounts', 'public_enquiries', 'audit_events', 'outbox_events'
                )
                """, Integer.class);

        Integer identityTableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'mfa_totp_factors', 'mfa_recovery_codes', 'mfa_login_challenges'
                )
                """, Integer.class);
        Integer immutableAuditTriggerCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.triggers
                WHERE event_object_schema = 'public'
                  AND event_object_table = 'audit_events'
                  AND trigger_name = 'audit_events_prevent_update_or_delete'
                """, Integer.class);
        Integer configuredSchoolCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM school_profiles
                WHERE school_id = '10000000-0000-4000-8000-000000000002'
                  AND organisation_id = '10000000-0000-4000-8000-000000000001'
                  AND board_code = 'CBSE'
                  AND grade_range = 'Nursery to Class X'
                  AND supported_languages @> ARRAY['hi-IN', 'en-IN']::varchar[]
                """, Integer.class);
        Boolean profileRlsEnabled = jdbc.queryForObject("""
                SELECT relrowsecurity
                FROM pg_class
                WHERE oid = 'school_profiles'::regclass
                """, Boolean.class);

        Boolean enquiryRlsEnabled = jdbc.queryForObject("""
                SELECT relrowsecurity
                FROM pg_class
                WHERE oid = 'public_enquiries'::regclass
                """, Boolean.class);
        Integer admissionsTableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'admissions_enquiry_notes'
                """, Integer.class);
        Integer admissionsPolicyCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename IN ('public_enquiries', 'admissions_enquiry_notes')
                """, Integer.class);
        String assigneeConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'public_enquiries_assignee_scope_fk'
                """, String.class);

        Integer applicationTableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'admission_applications', 'admission_application_guardians',
                    'admission_application_documents', 'admission_decisions',
                    'learners', 'guardians', 'learner_guardian_relationships', 'student_enrolments'
                )
                """, Integer.class);
        Integer applicationPolicyCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename IN (
                    'admission_applications', 'admission_application_guardians',
                    'admission_application_documents', 'admission_decisions',
                    'learners', 'guardians', 'learner_guardian_relationships', 'student_enrolments'
                  )
                """, Integer.class);
        Integer decisionImmutabilityTriggerCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.triggers
                WHERE event_object_schema = 'public'
                  AND event_object_table = 'admission_decisions'
                  AND trigger_name = 'admission_decisions_prevent_update_or_delete'
                """, Integer.class);
        Integer applicationCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code LIKE 'admissions.application.%'
                """, Integer.class);
        Integer registryTableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('class_sections', 'student_section_placements')
                """, Integer.class);
        Integer registryPolicyCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename IN ('class_sections', 'student_section_placements')
                """, Integer.class);
        Integer placementHistoryTriggerCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.triggers
                WHERE event_object_schema = 'public'
                  AND event_object_table = 'student_section_placements'
                  AND trigger_name = 'student_section_placements_guard_history'
                """, Integer.class);
        Integer placementUniquenessIndexCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                    'student_section_placements_one_active_idx',
                    'student_section_placements_roll_number_active_idx'
                  )
                """, Integer.class);
        Integer importTableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('student_import_batches', 'student_import_rows')
                """, Integer.class);
        Integer importPolicyCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename IN ('student_import_batches', 'student_import_rows')
                """, Integer.class);
        Integer importCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code = 'student.import' AND risk_level = 'RESTRICTED'
                """, Integer.class);
        Integer exactImportOriginConstraintCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint
                WHERE conname IN ('learners_exactly_one_origin', 'student_enrolments_exactly_one_origin')
                  AND pg_get_constraintdef(oid) LIKE '%num_nonnulls%'
                """, Integer.class);
        String admissionNumberIndex = jdbc.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'student_enrolments_school_admission_number_idx'
                """, String.class);
        Integer guardianPortalTableCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'guardian_portal_invitations'
                """, Integer.class);
        Integer guardianPortalPolicyCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_policies
                WHERE schemaname = 'public' AND tablename = 'guardian_portal_invitations'
                """, Integer.class);
        Integer guardianPortalCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('guardian.portal.invite', 'portal.guardian')
                """, Integer.class);
        Integer parentRoleCount = jdbc.queryForObject("""
                SELECT count(*) FROM roles
                WHERE id = '00000000-0000-4000-8000-000000000006'
                  AND code = 'parent_guardian'
                """, Integer.class);
        Integer guardianAccountIndexCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'guardians_school_user_account_unique'
                """, Integer.class);
        Integer academicTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'academic_terms', 'school_subjects', 'academic_subject_resources',
                    'section_subject_offerings', 'teaching_allocations', 'bell_schedule_periods',
                    'timetable_plans', 'timetable_entries'
                )
                """, Integer.class);
        Integer academicPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename IN (
                    'academic_terms', 'school_subjects', 'academic_subject_resources',
                    'section_subject_offerings', 'teaching_allocations', 'bell_schedule_periods',
                    'timetable_plans', 'timetable_entries'
                )
                """, Integer.class);
        Integer timetableHistoryTriggerCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema = 'public'
                  AND trigger_name IN ('timetable_plans_guard_history', 'timetable_entries_guard_history')
                """, Integer.class);
        Integer timetableSnapshotColumnCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'timetable_entries'
                  AND column_name IN (
                    'subject_name_snapshot', 'subject_short_name_snapshot', 'period_name_snapshot',
                    'day_of_week_snapshot', 'sequence_number_snapshot',
                    'starts_at_snapshot', 'ends_at_snapshot'
                  )
                """, Integer.class);
        Integer attendanceTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'attendance_policies', 'school_calendar_days', 'attendance_registers',
                    'attendance_marks', 'attendance_mark_history',
                    'attendance_correction_requests', 'attendance_explanations'
                )
                """, Integer.class);
        Integer attendancePolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename IN (
                    'attendance_policies', 'school_calendar_days', 'attendance_registers',
                    'attendance_marks', 'attendance_mark_history',
                    'attendance_correction_requests', 'attendance_explanations'
                )
                """, Integer.class);
        Integer attendanceHistoryTriggerCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE event_object_schema = 'public' AND trigger_name IN (
                    'attendance_registers_guard_history', 'attendance_marks_guard_submitted',
                    'attendance_mark_history_prevent_update_or_delete',
                    'attendance_corrections_guard_history', 'attendance_explanations_guard_history'
                )
                """, Integer.class);
        Integer attendanceSnapshotColumnCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'attendance_registers'
                  AND column_name IN (
                    'policy_snapshot', 'period_name_snapshot',
                    'period_starts_at_snapshot', 'period_ends_at_snapshot'
                  )
                """, Integer.class);
        Integer attendanceConfigureCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code = 'attendance.configure' AND risk_level = 'RESTRICTED'
                """, Integer.class);
        Integer assessmentTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'assessment_grading_scales', 'assessment_grade_bands', 'assessment_schemes',
                    'assessment_components', 'assessments', 'assessment_marks',
                    'assessment_mark_history', 'assessment_correction_requests',
                    'result_publications', 'learner_report_cards'
                )
                """, Integer.class);
        Integer assessmentPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename IN (
                    'assessment_grading_scales', 'assessment_grade_bands', 'assessment_schemes',
                    'assessment_components', 'assessments', 'assessment_marks',
                    'assessment_mark_history', 'assessment_correction_requests',
                    'result_publications', 'learner_report_cards'
                )
                """, Integer.class);
        Integer assessmentHistoryTriggerCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE event_object_schema = 'public' AND trigger_name IN (
                    'assessment_schemes_guard_history', 'assessment_components_guard_parent',
                    'assessment_grade_bands_guard_parent', 'assessments_guard_lifecycle',
                    'assessment_marks_guard_edit', 'assessment_mark_history_prevent_update_or_delete',
                    'learner_report_cards_prevent_update_or_delete',
                    'assessment_corrections_guard_history', 'result_publications_guard_history'
                )
                """, Integer.class);
        Integer assessmentConfigureCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code = 'assessment.configure' AND risk_level = 'RESTRICTED'
                """, Integer.class);
        Integer learningTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'assignment_categories', 'assignments', 'assignment_revision_history',
                    'assignment_recipients', 'learner_assignment_submissions',
                    'learner_submission_history'
                )
                """, Integer.class);
        Integer learningPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename IN (
                    'assignment_categories', 'assignments', 'assignment_revision_history',
                    'assignment_recipients', 'learner_assignment_submissions',
                    'learner_submission_history'
                )
                """, Integer.class);
        Integer learningHistoryTriggerCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE event_object_schema = 'public' AND trigger_name IN (
                    'assignments_guard_history',
                    'assignment_categories_guard_identity',
                    'assignment_revision_history_prevent_update_or_delete',
                    'assignment_recipients_guard_insert',
                    'assignment_recipients_prevent_update_or_delete',
                    'learner_submission_history_prevent_update_or_delete',
                    'learner_assignment_submissions_guard_insert',
                    'learner_assignment_submissions_guard_history'
                )
                """, Integer.class);
        Integer learningCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('learning.read', 'learning.manage', 'learning.moderate')
                """, Integer.class);
        Integer financeTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'fee_heads', 'fee_plans', 'fee_plan_items', 'learner_fee_accounts',
                    'fee_plan_assignments', 'learner_fee_charges', 'fee_ledger_entries',
                    'fee_receipt_sequences', 'fee_payments', 'fee_payment_allocations',
                    'fee_concession_requests', 'payment_provider_configs', 'fee_day_closes'
                )
                """, Integer.class);
        Integer financePolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename IN (
                    'fee_heads', 'fee_plans', 'fee_plan_items', 'learner_fee_accounts',
                    'fee_plan_assignments', 'learner_fee_charges', 'fee_ledger_entries',
                    'fee_receipt_sequences', 'fee_payments', 'fee_payment_allocations',
                    'fee_concession_requests', 'payment_provider_configs', 'fee_day_closes'
                )
                """, Integer.class);
        Integer financeEvidenceTriggerCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE event_object_schema = 'public' AND trigger_name IN (
                    'fee_plans_guard', 'fee_heads_guard_identity', 'fee_plan_items_guard',
                    'fee_plan_assignments_immutable', 'learner_fee_charges_immutable',
                    'fee_ledger_entries_immutable', 'fee_payment_allocations_immutable',
                    'fee_payments_guard', 'fee_payments_prevent_delete',
                    'fee_concessions_guard', 'fee_concessions_prevent_delete',
                    'fee_day_closes_guard', 'fee_day_closes_prevent_delete'
                )
                """, Integer.class);
        Integer financeCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('fees.configure', 'fees.collect', 'fees.approve',
                  'fees.close.propose', 'fees.close.approve')
                  AND risk_level = 'RESTRICTED'
                """, Integer.class);
        Integer communicationTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'communication_templates', 'communication_provider_configs',
                    'communication_template_channel_registrations', 'communication_contact_preferences',
                    'communication_consent_events', 'communication_announcements',
                    'communication_attachments', 'communication_recipients', 'communication_deliveries',
                    'communication_delivery_attempts', 'communication_recipient_events',
                    'communication_announcement_history'
                )
                """, Integer.class);
        Integer communicationPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'public' AND tablename IN (
                    'communication_templates', 'communication_provider_configs',
                    'communication_template_channel_registrations', 'communication_contact_preferences',
                    'communication_consent_events', 'communication_announcements',
                    'communication_attachments', 'communication_recipients', 'communication_deliveries',
                    'communication_delivery_attempts', 'communication_recipient_events',
                    'communication_announcement_history'
                )
                """, Integer.class);
        Integer communicationEvidenceTriggerCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.triggers
                WHERE event_object_schema = 'public' AND trigger_name IN (
                    'communication_templates_guard', 'communication_announcements_guard',
                    'private_files_communication_insert_guard', 'communication_attachments_guard',
                    'communication_recipients_guard', 'communication_deliveries_guard',
                    'communication_consent_events_append_only',
                    'communication_delivery_attempts_append_only',
                    'communication_recipient_events_append_only',
                    'communication_announcement_history_append_only'
                )
                """, Integer.class);
        Integer communicationCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('communication.read', 'communication.approve')
                """, Integer.class);
        Integer communicationRoleCount = jdbc.queryForObject("""
                SELECT count(*) FROM roles
                WHERE code IN ('principal_director', 'staff_operations')
                """, Integer.class);
        Integer communicationAttachmentContextCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='private_files'
                  AND column_name='communication_announcement_id'
                """, Integer.class);
        Integer peopleTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                    'staff_departments','staff_designations','staff_profiles','staff_assignments',
                    'staff_qualifications','staff_compliance_types','staff_compliance_checks',
                    'staff_public_profile_revisions','staff_profile_history'
                )
                """, Integer.class);
        Integer peoplePolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                    'staff_departments','staff_designations','staff_profiles','staff_assignments',
                    'staff_qualifications','staff_compliance_types','staff_compliance_checks',
                    'staff_public_profile_revisions','staff_profile_history'
                )
                """, Integer.class);
        Integer peopleGuardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                    'staff_departments_guard','staff_designations_guard','staff_compliance_types_guard',
                    'staff_departments_usage_guard','staff_designations_usage_guard',
                    'staff_profiles_guard','staff_profiles_account_guard','staff_assignments_guard','staff_qualifications_guard',
                    'staff_compliance_checks_guard','staff_public_profiles_guard',
                    'staff_profile_history_append_only'
                )
                """, Integer.class);
        Integer peopleCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('people.read','people.manage','people.compliance','people.publish')
                  AND risk_level IN ('SENSITIVE','RESTRICTED')
                """, Integer.class);
        Integer peopleRlsCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname IN (
                    'staff_departments','staff_designations','staff_profiles','staff_assignments',
                    'staff_qualifications','staff_compliance_types','staff_compliance_checks',
                    'staff_public_profile_revisions','staff_profile_history'
                ) AND relrowsecurity
                """, Integer.class);
        Integer transportTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                    'transport_stops','transport_routes','transport_route_revisions',
                    'transport_route_revision_stops','transport_vehicles',
                    'transport_vehicle_compliance_types','transport_vehicle_compliance_checks',
                    'transport_duty_assignments','learner_transport_assignments','transport_trips',
                    'transport_trip_roster_entries','transport_trip_events','transport_incidents',
                    'transport_history'
                )
                """, Integer.class);
        Integer transportPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                    'transport_stops','transport_routes','transport_route_revisions',
                    'transport_route_revision_stops','transport_vehicles',
                    'transport_vehicle_compliance_types','transport_vehicle_compliance_checks',
                    'transport_duty_assignments','learner_transport_assignments','transport_trips',
                    'transport_trip_roster_entries','transport_trip_events','transport_incidents',
                    'transport_history'
                )
                """, Integer.class);
        Integer transportGuardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                    'transport_route_revisions_guard','transport_route_stops_guard',
                    'transport_vehicle_checks_guard','transport_duties_guard',
                    'learner_transport_assignments_guard','transport_trips_guard',
                    'transport_trip_roster_append_only','transport_trip_events_append_only',
                    'transport_incidents_guard','transport_history_append_only'
                )
                """, Integer.class);
        Integer transportCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('transport.read','transport.configure','transport.operate',
                    'transport.safety','transport.publish')
                  AND risk_level IN ('SENSITIVE','RESTRICTED')
                """, Integer.class);
        Integer transportRoleCount = jdbc.queryForObject("""
                SELECT count(*) FROM roles
                WHERE code IN ('transport_manager','transport_operator') AND system_role
                """, Integer.class);
        Integer transportRlsCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname IN (
                    'transport_stops','transport_routes','transport_route_revisions',
                    'transport_route_revision_stops','transport_vehicles',
                    'transport_vehicle_compliance_types','transport_vehicle_compliance_checks',
                    'transport_duty_assignments','learner_transport_assignments','transport_trips',
                    'transport_trip_roster_entries','transport_trip_events','transport_incidents',
                    'transport_history'
                ) AND relrowsecurity
                """, Integer.class);
        Integer transportEventScopeForeignKeyCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints constraint_record
                JOIN information_schema.constraint_column_usage usage
                  ON usage.constraint_schema=constraint_record.constraint_schema
                 AND usage.constraint_name=constraint_record.constraint_name
                WHERE constraint_record.table_schema='public'
                  AND constraint_record.table_name='transport_trip_events'
                  AND constraint_record.constraint_type='FOREIGN KEY'
                  AND usage.table_name='transport_trip_roster_entries'
                """, Integer.class);
        Integer transportDesignationFlagCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='staff_designations'
                  AND column_name IN ('eligible_as_transport_driver','eligible_as_transport_attendant')
                """, Integer.class);
        Integer transportLifecycleEventIndexCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname='public' AND tablename='transport_trip_events'
                  AND indexname='transport_trip_events_one_lifecycle_event_idx'
                """, Integer.class);
        Integer transportRouteStopDeletePolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename='transport_route_revision_stops'
                  AND policyname='transport_route_stops_tenant_delete' AND cmd='DELETE'
                """, Integer.class);
        Integer transportLearnerCapacityColumnCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='transport_vehicles'
                  AND column_name='learner_capacity'
                """, Integer.class);
        Integer transportBoardingTimestampColumnCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='transport_trips'
                  AND column_name='boarding_opened_at'
                """, Integer.class);
        String transportIncidentStateConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname='transport_incidents_state_valid'
                """, String.class);
        Integer officialDocumentTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'official_document_templates','official_documents','official_document_history')
                """, Integer.class);
        Integer officialDocumentCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('documents.read','documents.configure','documents.issue',
                  'documents.approve','documents.revoke')
                """, Integer.class);
        Integer officialDocumentPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                  'official_document_templates','official_documents','official_document_history')
                """, Integer.class);
        Integer officialDocumentGuardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'official_document_templates_guard','official_documents_guard',
                  'official_document_templates_prevent_delete','official_documents_prevent_delete',
                  'official_document_history_prevent_update_or_delete')
                """, Integer.class);
        Integer officialDocumentRlsCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relname IN (
                  'official_document_templates','official_documents','official_document_history')
                  AND relrowsecurity
                """, Integer.class);
        Integer launchTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'school_launch_readiness_items','school_launch_reviews')
                """, Integer.class);
        Integer launchPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                  'school_launch_readiness_items','school_launch_reviews')
                """, Integer.class);
        Integer launchGuardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'school_launch_items_prevent_update','school_launch_items_prevent_delete',
                  'school_launch_reviews_guard','school_launch_reviews_prevent_delete',
                  'schools_seed_launch_controls')
                """, Integer.class);
        Integer launchCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('launch.configure','launch.approve')
                  AND risk_level='RESTRICTED'
                """, Integer.class);
        Integer launchBaselineCount = jdbc.queryForObject("""
                SELECT count(*) FROM school_launch_readiness_items
                WHERE organisation_id='10000000-0000-4000-8000-000000000001'
                  AND school_id='10000000-0000-4000-8000-000000000002'
                  AND system_control AND revision_number=1 AND required
                """, Integer.class);
        Integer schoolCalendarTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'school_calendar_policy_revisions','school_calendar_notice_series',
                  'school_calendar_notice_revisions','school_calendar_notice_reviews',
                  'school_calendar_notification_jobs')
                """, Integer.class);
        Integer schoolCalendarPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                  'school_calendar_policy_revisions','school_calendar_notice_series',
                  'school_calendar_notice_revisions','school_calendar_notice_reviews',
                  'school_calendar_notification_jobs')
                """, Integer.class);
        Integer schoolCalendarGuardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'school_calendar_policy_prevent_update','school_calendar_policy_prevent_delete',
                  'school_calendar_notice_series_prevent_update',
                  'school_calendar_notice_series_prevent_delete',
                  'school_calendar_notice_revision_prevent_update',
                  'school_calendar_notice_revision_prevent_delete',
                  'school_calendar_notice_reviews_guard',
                  'school_calendar_notification_jobs_guard',
                  'school_calendar_days_authority_guard')
                """, Integer.class);
        Integer schoolCalendarCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('calendar.manage','calendar.approve')
                  AND risk_level='RESTRICTED'
                """, Integer.class);
        Integer schoolCalendarAuthorityColumnCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='school_calendar_days'
                  AND column_name='authority_notice_revision_id'
                """, Integer.class);
        Integer rolloverTableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'academic_rollover_batches','academic_rollover_student_decisions',
                  'learner_admission_identifiers')
                """, Integer.class);
        Integer rolloverPolicyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                  'academic_rollover_batches','academic_rollover_student_decisions',
                  'learner_admission_identifiers')
                """, Integer.class);
        Integer rolloverGuardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'academic_rollover_batches_immutable',
                  'academic_rollover_decisions_immutable',
                  'learner_admission_identifiers_immutable',
                  'student_enrolments_register_admission_identifier')
                """, Integer.class);
        Integer rolloverCapabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code='academic.rollover.manage' AND risk_level='RESTRICTED'
                """, Integer.class);
        var v024 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V024__configurable_launch_readiness_and_approval.sql"
                        .equals(migration.getScript()))
                .findFirst();
        var v025 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V025__governed_school_calendar_and_notifications.sql"
                        .equals(migration.getScript()))
                .findFirst();
        var v027 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V027__controlled_academic_year_rollover.sql"
                        .equals(migration.getScript()))
                .findFirst();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(migrationCount).isEqualTo(flyway.info().applied().length);
        assertThat(tableCount).isEqualTo(6);
        assertThat(identityTableCount).isEqualTo(3);
        assertThat(immutableAuditTriggerCount).isEqualTo(2);
        assertThat(configuredSchoolCount).isEqualTo(1);
        assertThat(profileRlsEnabled).isTrue();
        assertThat(enquiryRlsEnabled).isTrue();
        assertThat(admissionsTableCount).isEqualTo(1);
        assertThat(admissionsPolicyCount).isEqualTo(5);
        assertThat(assigneeConstraint)
                .contains("FOREIGN KEY (assigned_membership_id, organisation_id, school_id)");
        assertThat(applicationTableCount).isEqualTo(8);
        assertThat(applicationPolicyCount).isEqualTo(23);
        assertThat(decisionImmutabilityTriggerCount).isEqualTo(2);
        assertThat(applicationCapabilityCount).isEqualTo(4);
        assertThat(registryTableCount).isEqualTo(2);
        assertThat(registryPolicyCount).isEqualTo(6);
        assertThat(placementHistoryTriggerCount).isEqualTo(1);
        assertThat(placementUniquenessIndexCount).isEqualTo(2);
        assertThat(importTableCount).isEqualTo(2);
        assertThat(importPolicyCount).isEqualTo(6);
        assertThat(importCapabilityCount).isEqualTo(1);
        assertThat(exactImportOriginConstraintCount).isEqualTo(2);
        assertThat(admissionNumberIndex).contains("lower((admission_number)::text)");
        assertThat(guardianPortalTableCount).isEqualTo(1);
        assertThat(guardianPortalPolicyCount).isEqualTo(5);
        assertThat(guardianPortalCapabilityCount).isEqualTo(2);
        assertThat(parentRoleCount).isEqualTo(1);
        assertThat(guardianAccountIndexCount).isEqualTo(1);
        assertThat(academicTableCount).isEqualTo(8);
        assertThat(academicPolicyCount).isEqualTo(8);
        assertThat(timetableHistoryTriggerCount).isEqualTo(2);
        assertThat(timetableSnapshotColumnCount).isEqualTo(7);
        assertThat(attendanceTableCount).isEqualTo(7);
        assertThat(attendancePolicyCount).isEqualTo(7);
        assertThat(attendanceHistoryTriggerCount).isEqualTo(6);
        assertThat(attendanceSnapshotColumnCount).isEqualTo(4);
        assertThat(attendanceConfigureCapabilityCount).isEqualTo(1);
        assertThat(assessmentTableCount).isEqualTo(10);
        assertThat(assessmentPolicyCount).isEqualTo(10);
        assertThat(assessmentHistoryTriggerCount).isEqualTo(16);
        assertThat(assessmentConfigureCapabilityCount).isEqualTo(1);
        assertThat(learningTableCount).isEqualTo(6);
        assertThat(learningPolicyCount).isEqualTo(6);
        assertThat(learningHistoryTriggerCount).isEqualTo(11);
        assertThat(learningCapabilityCount).isEqualTo(3);
        assertThat(financeTableCount).isEqualTo(13);
        assertThat(financePolicyCount).isEqualTo(16);
        assertThat(financeEvidenceTriggerCount).isGreaterThanOrEqualTo(19);
        assertThat(financeCapabilityCount).isEqualTo(5);
        assertThat(communicationTableCount).isEqualTo(12);
        assertThat(communicationPolicyCount).isEqualTo(32);
        assertThat(communicationEvidenceTriggerCount).isGreaterThanOrEqualTo(10);
        assertThat(communicationCapabilityCount).isEqualTo(2);
        assertThat(communicationRoleCount).isEqualTo(2);
        assertThat(communicationAttachmentContextCount).isEqualTo(1);
        assertThat(peopleTableCount).isEqualTo(9);
        assertThat(peoplePolicyCount).isEqualTo(27);
        assertThat(peopleGuardCount).isEqualTo(12);
        assertThat(peopleCapabilityCount).isEqualTo(4);
        assertThat(peopleRlsCount).isEqualTo(9);
        assertThat(transportTableCount).isEqualTo(14);
        assertThat(transportPolicyCount).isEqualTo(40);
        assertThat(transportGuardCount).isEqualTo(10);
        assertThat(transportCapabilityCount).isEqualTo(5);
        assertThat(transportRoleCount).isEqualTo(2);
        assertThat(transportRlsCount).isEqualTo(14);
        assertThat(transportEventScopeForeignKeyCount).isGreaterThanOrEqualTo(1);
        assertThat(transportDesignationFlagCount).isEqualTo(2);
        assertThat(transportLifecycleEventIndexCount).isEqualTo(1);
        assertThat(transportRouteStopDeletePolicyCount).isEqualTo(1);
        assertThat(transportLearnerCapacityColumnCount).isEqualTo(1);
        assertThat(transportBoardingTimestampColumnCount).isEqualTo(1);
        assertThat(transportIncidentStateConstraint)
                .contains("resolved_by_membership_id <> reported_by_membership_id");
        assertThat(officialDocumentTableCount).isEqualTo(3);
        assertThat(officialDocumentCapabilityCount).isEqualTo(5);
        assertThat(officialDocumentPolicyCount).isEqualTo(3);
        assertThat(officialDocumentGuardCount).isEqualTo(5);
        assertThat(officialDocumentRlsCount).isEqualTo(3);
        assertThat(launchTableCount).isEqualTo(2);
        assertThat(launchPolicyCount).isEqualTo(2);
        assertThat(launchGuardCount).isEqualTo(5);
        assertThat(launchCapabilityCount).isEqualTo(2);
        assertThat(launchBaselineCount).isEqualTo(10);
        assertThat(schoolCalendarTableCount).isEqualTo(5);
        assertThat(schoolCalendarPolicyCount).isEqualTo(5);
        assertThat(schoolCalendarGuardCount).isEqualTo(9);
        assertThat(schoolCalendarCapabilityCount).isEqualTo(2);
        assertThat(schoolCalendarAuthorityColumnCount).isEqualTo(1);
        assertThat(rolloverTableCount).isEqualTo(3);
        assertThat(rolloverPolicyCount).isEqualTo(6);
        assertThat(rolloverGuardCount).isEqualTo(4);
        assertThat(rolloverCapabilityCount).isEqualTo(1);
        assertThat(v024).isPresent();
        assertThat(v024.orElseThrow().getScript())
                .isEqualTo("V024__configurable_launch_readiness_and_approval.sql");
        assertThat(v025).isPresent();
        assertThat(v025.orElseThrow().getScript())
                .isEqualTo("V025__governed_school_calendar_and_notifications.sql");
        assertThat(v027).isPresent();
        assertThat(v027.orElseThrow().getScript())
                .isEqualTo("V027__controlled_academic_year_rollover.sql");
    }

    @Test
    void appliesV018PeopleAndPublicStaffProfilesWithRequiredGuards() {
        var v018 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V018__configurable_people_and_public_staff_profiles.sql"
                        .equals(migration.getScript()))
                .findFirst();
        assertThat(v018).isPresent();
        assertThat(v018.orElseThrow().getScript())
                .isEqualTo("V018__configurable_people_and_public_staff_profiles.sql");

        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                    'staff_departments','staff_designations','staff_profiles','staff_assignments',
                    'staff_qualifications','staff_compliance_types','staff_compliance_checks',
                    'staff_public_profile_revisions','staff_profile_history'
                )
                """, Integer.class);
        Integer rowSecurityCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relnamespace='public'::regnamespace AND relrowsecurity
                  AND relname IN (
                    'staff_departments','staff_designations','staff_profiles','staff_assignments',
                    'staff_qualifications','staff_compliance_types','staff_compliance_checks',
                    'staff_public_profile_revisions','staff_profile_history'
                  )
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                    'staff_departments_guard','staff_designations_guard','staff_compliance_types_guard',
                    'staff_departments_usage_guard','staff_designations_usage_guard',
                    'staff_profiles_guard','staff_profiles_account_guard','staff_assignments_guard',
                    'staff_qualifications_guard','staff_compliance_checks_guard',
                    'staff_public_profiles_guard','staff_profile_history_append_only'
                )
                """, Integer.class);

        assertThat(tableCount).isEqualTo(9);
        assertThat(rowSecurityCount).isEqualTo(9);
        assertThat(guardCount).isEqualTo(12);
    }

    @Test
    void appliesV019Point1RoleIdSeparationBeforeStudentPortal() {
        var bridge = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V019_1__separate_transport_operator_and_student_roles.sql"
                        .equals(migration.getScript()))
                .findFirst();
        assertThat(bridge).isPresent();
        assertThat(bridge.orElseThrow().getScript())
                .isEqualTo("V019_1__separate_transport_operator_and_student_roles.sql");

        Integer transportOperatorCount = jdbc.queryForObject("""
                SELECT count(*) FROM roles role_record
                WHERE role_record.id='00000000-0000-4000-8000-000000000011'
                  AND role_record.organisation_id IS NULL
                  AND role_record.code='transport_operator'
                  AND role_record.system_role
                  AND EXISTS (
                    SELECT 1 FROM role_capabilities capability
                    WHERE capability.role_id=role_record.id
                      AND capability.capability_code='transport.operate'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM role_capabilities capability
                    WHERE capability.role_id=role_record.id
                      AND capability.capability_code='portal.student'
                  )
                """, Integer.class);

        assertThat(transportOperatorCount).isEqualTo(1);
    }

    @Test
    void appliesV020GuardianApprovedStudentPortalWithoutRoleCollisions() {
        var v020 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V020__guardian_approved_student_portal.sql"
                        .equals(migration.getScript()))
                .findFirst();
        assertThat(v020).isPresent();
        assertThat(v020.orElseThrow().getScript())
                .isEqualTo("V020__guardian_approved_student_portal.sql");

        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                    'student_portal_consent_requests','learner_portal_accounts',
                    'learner_portal_account_history'
                )
                """, Integer.class);
        Integer rowSecurityCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relnamespace='public'::regnamespace AND relrowsecurity
                  AND relname IN (
                    'student_portal_consent_requests','learner_portal_accounts',
                    'learner_portal_account_history'
                  )
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                    'student_portal_consent_requests','learner_portal_accounts',
                    'learner_portal_account_history'
                )
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                    'student_portal_consent_requests_guard','learner_portal_accounts_guard',
                    'learner_portal_account_history_validate',
                    'learner_portal_account_history_immutable'
                )
                """, Integer.class);
        Integer studentRoleCount = jdbc.queryForObject("""
                SELECT count(*) FROM roles role_record
                JOIN role_capabilities role_capability ON role_capability.role_id=role_record.id
                WHERE role_record.id='00000000-0000-4000-8000-000000000010'
                  AND role_record.organisation_id IS NULL AND role_record.code='student'
                  AND role_capability.capability_code='portal.student'
                """, Integer.class);
        Integer transportRoleCount = jdbc.queryForObject("""
                SELECT count(*) FROM roles
                WHERE organisation_id IS NULL AND (
                    (id='00000000-0000-4000-8000-000000000009' AND code='transport_manager')
                    OR (id='00000000-0000-4000-8000-000000000011' AND code='transport_operator')
                  )
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities
                WHERE code IN ('student.portal.manage','portal.student')
                """, Integer.class);
        Integer loginIdentifierCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='user_accounts'
                  AND column_name='login_identifier_normalized' AND is_nullable='NO'
                """, Integer.class);
        Integer studentRecipientIndexCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname='public' AND tablename='communication_recipients'
                  AND indexname='communication_recipients_student_unique'
                """, Integer.class);
        String announcementAudienceConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname='communication_announcements_audience_valid'
                """, String.class);

        assertThat(tableCount).isEqualTo(3);
        assertThat(rowSecurityCount).isEqualTo(3);
        assertThat(policyCount).isEqualTo(8);
        assertThat(guardCount).isEqualTo(4);
        assertThat(studentRoleCount).isEqualTo(1);
        assertThat(transportRoleCount).isEqualTo(2);
        assertThat(capabilityCount).isEqualTo(2);
        assertThat(loginIdentifierCount).isEqualTo(1);
        assertThat(studentRecipientIndexCount).isEqualTo(1);
        assertThat(announcementAudienceConstraint)
                .contains("ALL_STUDENTS", "SECTION_STUDENTS", "LEARNER_STUDENT");
    }

    @Test
    void appliesV021VersionedCurriculumPublicationsWithRequiredGuards() {
        var v021 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V021__versioned_curriculum_publications.sql"
                        .equals(migration.getScript()))
                .findFirst();
        assertThat(v021).isPresent();
        assertThat(v021.orElseThrow().getScript())
                .isEqualTo("V021__versioned_curriculum_publications.sql");

        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                    'curriculum_publications','curriculum_publication_items'
                )
                """, Integer.class);
        Integer rowSecurityCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                WHERE relnamespace='public'::regnamespace AND relrowsecurity
                  AND relname IN ('curriculum_publications','curriculum_publication_items')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname='public' AND tablename IN (
                    'curriculum_publications','curriculum_publication_items'
                )
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                    'curriculum_publications_guard','curriculum_publication_items_guard'
                )
                """, Integer.class);
        Integer activeReleaseIndexes = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname='public' AND indexname IN (
                    'curriculum_publications_one_draft_idx',
                    'curriculum_publications_one_published_idx'
                )
                """, Integer.class);

        assertThat(tableCount).isEqualTo(2);
        assertThat(rowSecurityCount).isEqualTo(2);
        assertThat(policyCount).isEqualTo(5);
        assertThat(guardCount).isEqualTo(2);
        assertThat(activeReleaseIndexes).isEqualTo(2);
    }

    @Test
    @Transactional
    void teacherAcademicsReturnOnlyOwnPublishedMembershipScope() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID teacherUserId = uuid("46000000", 1);
        UUID teacherMembershipId = uuid("46000000", 2);
        UUID otherTeacherUserId = uuid("46000000", 3);
        UUID otherTeacherMembershipId = uuid("46000000", 4);
        UUID sessionId = uuid("46000000", 5);
        UUID sectionId = uuid("46000000", 6);
        UUID subjectId = uuid("46000000", 7);
        UUID offeringId = uuid("46000000", 8);
        UUID allocationId = uuid("46000000", 9);
        UUID periodId = uuid("46000000", 10);
        UUID publishedPlanId = uuid("46000000", 11);
        UUID publishedEntryId = uuid("46000000", 12);
        UUID draftPlanId = uuid("46000000", 13);
        UUID draftEntryId = uuid("46000000", 14);
        UUID designationId = uuid("46000000", 15);
        UUID staffProfileId = uuid("46000000", 16);
        UUID staffAssignmentId = uuid("46000000", 17);
        UUID otherAllocationId = uuid("46000000", 18);

        insertSyntheticUser(teacherUserId, "teacher-academics@example.test");
        insertSyntheticMembership(
                teacherMembershipId, teacherUserId, organisationId, schoolId);
        insertSyntheticUser(otherTeacherUserId, "other-teacher-academics@example.test");
        insertSyntheticMembership(
                otherTeacherMembershipId, otherTeacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                otherTeacherMembershipId, teacherRoleId);
        jdbc.update("""
                INSERT INTO staff_designations(
                  id,organisation_id,school_id,code,display_name,staff_category,
                  cbse_staff_category,teaching_role,leadership_role,display_order,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN_TEACHER_46', 'Synthetic Teacher', 'TEACHING',
                  'OTHER_TEACHING', true, false, 0, 'ACTIVE', ?, now(), now(), 0)
                """, designationId, organisationId, schoolId, teacherMembershipId);
        jdbc.update("""
                INSERT INTO staff_profiles(
                  id,organisation_id,school_id,employee_code,legal_name,display_name,
                  primary_language,work_email_normalized,user_account_id,joined_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN-T-46', 'Synthetic Teacher', 'Synthetic Teacher',
                  'EN', 'teacher-academics@example.test', ?, CURRENT_DATE - 30,
                  'ACTIVE', ?, now(), now(), 0)
                """, staffProfileId, organisationId, schoolId, teacherUserId,
                teacherMembershipId);
        jdbc.update("""
                INSERT INTO staff_assignments(
                  id,organisation_id,school_id,staff_profile_id,staff_designation_id,
                  campus_id,employment_type,employer_kind,primary_assignment,starts_on,
                  status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, 'FULL_TIME', 'SCHOOL', true,
                  CURRENT_DATE - 30, 'ACTIVE', ?, now(), now(), 0)
                """, staffAssignmentId, organisationId, schoolId, staffProfileId,
                designationId, campusId, teacherMembershipId);
        jdbc.update("""
                INSERT INTO academic_sessions(
                  id,organisation_id,school_id,name,starts_on,ends_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic teacher academics current session',
                  CURRENT_DATE - 30, CURRENT_DATE + 330, 'PLANNED', now(), now(), 0)
                """, sessionId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO class_sections(
                  id,organisation_id,school_id,campus_id,academic_session_id,
                  grade_code,code,display_name,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'VI', 'A', 'Synthetic Class VI A',
                  'ACTIVE', now(), now(), 0)
                """, sectionId, organisationId, schoolId, campusId, sessionId);
        jdbc.update("""
                INSERT INTO school_subjects(
                  id,organisation_id,school_id,code,display_name,short_name,
                  subject_category,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN-TEACH', 'Synthetic Mathematics', 'Maths',
                  'CORE', 'ACTIVE', now(), now(), 0)
                """, subjectId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO section_subject_offerings(
                  id,organisation_id,school_id,class_section_id,subject_id,
                  weekly_target_periods,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 5, 'ACTIVE', now(), now(), 0)
                """, offeringId, organisationId, schoolId, sectionId, subjectId);
        jdbc.update("""
                INSERT INTO teaching_allocations(
                  id,organisation_id,school_id,section_subject_offering_id,
                  teacher_membership_id,allocation_role,starts_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'PRIMARY', CURRENT_DATE - 30,
                  'ACTIVE', now(), now(), 0)
                """, allocationId, organisationId, schoolId, offeringId, teacherMembershipId);
        jdbc.update("""
                INSERT INTO teaching_allocations(
                  id,organisation_id,school_id,section_subject_offering_id,
                  teacher_membership_id,allocation_role,starts_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'CO_TEACHER', CURRENT_DATE - 30,
                  'ACTIVE', now(), now(), 0)
                """, otherAllocationId, organisationId, schoolId, offeringId,
                otherTeacherMembershipId);
        jdbc.update("""
                INSERT INTO bell_schedule_periods(
                  id,organisation_id,school_id,campus_id,academic_session_id,
                  day_of_week,sequence_number,code,display_name,starts_at,ends_at,
                  period_kind,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 1, 1, 'P1', 'Period 1',
                  TIME '08:00', TIME '08:40', 'TEACHING', 'ACTIVE', now(), now(), 0)
                """, periodId, organisationId, schoolId, campusId, sessionId);
        jdbc.update("""
                INSERT INTO timetable_plans(
                  id,organisation_id,school_id,campus_id,academic_session_id,plan_name,
                  status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'Synthetic published teacher timetable',
                  'DRAFT', ?, now(), now(), 0)
                """, publishedPlanId, organisationId, schoolId, campusId, sessionId,
                teacherMembershipId);
        insertSyntheticTimetableEntry(
                publishedEntryId, organisationId, schoolId, publishedPlanId, sectionId,
                offeringId, periodId, teacherMembershipId);
        jdbc.update("""
                UPDATE timetable_plans
                SET status='PUBLISHED',published_by_membership_id=?,published_at=now(),
                  updated_at=now(),row_version=row_version+1
                WHERE id=?
                """, teacherMembershipId, publishedPlanId);
        jdbc.update("""
                INSERT INTO timetable_plans(
                  id,organisation_id,school_id,campus_id,academic_session_id,plan_name,
                  status,created_by_membership_id,supersedes_plan_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'Synthetic hidden draft timetable',
                  'DRAFT', ?, ?, now(), now(), 0)
                """, draftPlanId, organisationId, schoolId, campusId, sessionId,
                teacherMembershipId, publishedPlanId);
        insertSyntheticTimetableEntry(
                draftEntryId, organisationId, schoolId, draftPlanId, sectionId,
                offeringId, periodId, teacherMembershipId);

        AuthenticatedUser teacher = syntheticAcademicUser(
                teacherUserId, teacherMembershipId, organisationId);
        AuthenticatedUser otherTeacher = syntheticAcademicUser(
                otherTeacherUserId, otherTeacherMembershipId, organisationId);

        var own = academics.teacherSnapshot(teacher, schoolId);
        var other = academics.teacherSnapshot(otherTeacher, schoolId);

        assertThat(own.teachingGroups())
                .extracting(group -> group.allocationId())
                .containsExactly(allocationId);
        assertThat(own.scheduleEntries())
                .extracting(entry -> entry.entryId())
                .containsExactly(publishedEntryId);
        assertThat(other.teachingGroups()).isEmpty();
        assertThat(other.scheduleEntries()).isEmpty();

        AuthenticatedUser forgedMembership = new AuthenticatedUser(
                teacherUserId, uuid("46000000", 20), "teacher-academics@example.test",
                "teacher-academics@example.test", "Synthetic teacher", true, organisationId,
                Set.of(otherTeacherMembershipId), Set.of("academics.read"), List.of());
        assertThatThrownBy(() -> academics.teacherSnapshot(forgedMembership, schoolId))
                .hasMessageContaining("Academic access is not available");
        assertThatThrownBy(() -> academics.teacherSnapshot(
                teacher, uuid("46000000", 21)))
                .hasMessageContaining("Academic access is not available");
    }

    @Test
    @Transactional
    void schoolConfigurationIsVersionedAuditedAndStrictlySchoolScoped() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID schoolAdminRoleId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID adminUserId = uuid("47000000", 1);
        UUID adminMembershipId = uuid("47000000", 2);
        UUID teacherUserId = uuid("47000000", 3);
        UUID teacherMembershipId = uuid("47000000", 4);
        UUID otherSchoolId = uuid("47000000", 5);
        UUID otherOrganisationId = uuid("47000000", 6);
        UUID otherOrganisationSchoolId = uuid("47000000", 7);
        UUID otherAdminUserId = uuid("47000000", 8);
        UUID otherAdminMembershipId = uuid("47000000", 9);

        insertSyntheticUser(adminUserId, "school-config-admin@example.test");
        insertSyntheticMembership(adminMembershipId, adminUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                adminMembershipId, schoolAdminRoleId);
        insertSyntheticUser(teacherUserId, "school-config-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);
        jdbc.update("""
                INSERT INTO schools(
                  id,organisation_id,slug,legal_name,display_name,time_zone,currency_code,
                  status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-other-school', 'Synthetic Other School',
                  'Synthetic Other School', 'Asia/Kolkata', 'INR', 'ACTIVE', now(), now(), 0)
                """, otherSchoolId, organisationId);
        jdbc.update("""
                INSERT INTO organisations(
                  id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'synthetic-config-other-org', 'Synthetic Other Organisation',
                  'Synthetic Other Organisation', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(
                  id,organisation_id,slug,legal_name,display_name,time_zone,currency_code,
                  status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-config-other-org-school', 'Synthetic Other Org School',
                  'Synthetic Other Org School', 'Asia/Kolkata', 'INR', 'ACTIVE', now(), now(), 0)
                """, otherOrganisationSchoolId, otherOrganisationId);
        insertSyntheticUser(otherAdminUserId, "school-config-other-admin@example.test");
        insertSyntheticMembership(otherAdminMembershipId, otherAdminUserId,
                otherOrganisationId, otherOrganisationSchoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                otherAdminMembershipId, schoolAdminRoleId);

        AuthenticatedUser admin = new AuthenticatedUser(
                adminUserId, UUID.randomUUID(), "school-config-admin@example.test",
                "school-config-admin@example.test", "Synthetic administrator", true,
                organisationId, Set.of(adminMembershipId),
                Set.of("school.configure", "platform.configure"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                teacherUserId, UUID.randomUUID(), "school-config-teacher@example.test",
                "school-config-teacher@example.test", "Synthetic teacher", true,
                organisationId, Set.of(teacherMembershipId), Set.of("academics.read"), List.of());
        AuthenticatedUser otherOrganisationAdmin = new AuthenticatedUser(
                otherAdminUserId, UUID.randomUUID(), "school-config-other-admin@example.test",
                "school-config-other-admin@example.test", "Synthetic other administrator", true,
                otherOrganisationId, Set.of(otherAdminMembershipId),
                Set.of("school.configure", "platform.configure"), List.of());

        var before = schoolConfiguration.snapshot(admin, schoolId);
        var profileRequest = new SchoolProfileRequest(
                schoolId, "Synthetic Academy", "Synthetic Academy", "Asia/Kolkata", "INR",
                "CBSE", "Nursery to Class X", "Synthetic knowledge motto",
                List.of("hi-IN", "en-IN"), "school@example.test", "+91 9999999999",
                "Synthetic Principal", "Synthetic school address", "Synthetic locality",
                "Varanasi", "Uttar Pradesh", "IN", 700, 800, 20, 10,
                before.rowVersion(), true, "Synthetic authority-verified profile update");
        var unconfirmedRequest = new SchoolProfileRequest(
                schoolId, "Synthetic Academy", "Synthetic Academy", "Asia/Kolkata", "INR",
                "CBSE", "Nursery to Class X", "Synthetic knowledge motto",
                List.of("hi-IN", "en-IN"), "school@example.test", "+91 9999999999",
                "Synthetic Principal", "Synthetic school address", "Synthetic locality",
                "Varanasi", "Uttar Pradesh", "IN", 700, 800, 20, 10,
                before.rowVersion(), false, "Synthetic unconfirmed profile update");
        assertThatThrownBy(() -> schoolConfiguration.updateProfile(admin, unconfirmedRequest))
                .hasMessageContaining("Confirm that the school facts are accurate");
        var updated = schoolConfiguration.updateProfile(admin, profileRequest);
        var after = schoolConfiguration.snapshot(admin, schoolId);

        assertThat(updated.rowVersion()).isEqualTo(before.rowVersion() + 1);
        assertThat(after.displayName()).isEqualTo("Synthetic Academy");
        assertThat(after.supportedLanguages()).containsExactly("hi-IN", "en-IN");
        assertThat(after.verifiedAt()).isNotNull();
        assertThatThrownBy(() -> schoolConfiguration.updateProfile(admin, profileRequest))
                .hasMessageContaining("configuration changed");

        var campusRequest = new CampusRequest(
                schoolId, "SYN_47", "Synthetic Second Campus", "Asia/Kolkata", "ACTIVE",
                null, "Synthetic campus created by school authority");
        var campus = schoolConfiguration.createCampus(admin, campusRequest);
        assertDatabaseRejected(
                () -> schoolConfiguration.createCampus(admin, campusRequest),
                "conflicting school or campus configuration");
        var inactivateSynthetic = new CampusRequest(
                schoolId, "SYN_47", "Synthetic Second Campus", "Asia/Kolkata", "INACTIVE",
                campus.rowVersion(), "Synthetic campus inactivation");
        schoolConfiguration.updateCampus(admin, campus.id(), inactivateSynthetic);
        var inactivateOnlyActive = new CampusRequest(
                schoolId, "SUAA", "Suaari Campus", "Asia/Kolkata", "INACTIVE", 0L,
                "Synthetic last-campus guard verification");
        assertThatThrownBy(() -> schoolConfiguration.updateCampus(
                admin, campusId, inactivateOnlyActive))
                .hasMessageContaining("at least one active campus");

        Integer auditCount = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE organisation_id=? AND school_id=? AND actor_membership_id=?
                  AND action IN (
                    'school.configuration.profile.updated',
                    'school.configuration.campus.created',
                    'school.configuration.campus.updated')
                """, Integer.class, organisationId, schoolId, adminMembershipId);
        assertThat(auditCount).isEqualTo(3);
        assertThatThrownBy(() -> schoolConfiguration.snapshot(teacher, schoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> schoolConfiguration.snapshot(admin, otherSchoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> schoolConfiguration.snapshot(otherOrganisationAdmin, schoolId))
                .hasMessageContaining("unavailable for this school");
    }

    @Test
    @Transactional
    void reportsOverviewReturnsOnlySchoolScopedAggregatesAndAuditsAccess() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID auditorRoleId = UUID.fromString("00000000-0000-4000-8000-000000000005");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID auditorUserId = uuid("48000000", 1);
        UUID auditorMembershipId = uuid("48000000", 2);
        UUID teacherUserId = uuid("48000000", 3);
        UUID teacherMembershipId = uuid("48000000", 4);
        UUID enquiryId = uuid("48000000", 5);
        UUID otherSchoolId = uuid("48000000", 6);
        UUID otherOrganisationId = uuid("48000000", 8);
        UUID otherOrganisationSchoolId = uuid("48000000", 9);
        UUID otherAuditorUserId = uuid("48000000", 10);
        UUID otherAuditorMembershipId = uuid("48000000", 11);

        insertSyntheticUser(auditorUserId, "reports-auditor@example.test");
        insertSyntheticMembership(auditorMembershipId, auditorUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                auditorMembershipId, auditorRoleId);
        insertSyntheticUser(teacherUserId, "reports-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);
        insertEnquiry(enquiryId, organisationId, schoolId, "reports-overview-synthetic-enquiry");
        jdbc.update("""
                INSERT INTO schools(
                  id,organisation_id,slug,legal_name,display_name,time_zone,currency_code,
                  status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-reports-other-school', 'Synthetic Reports Other School',
                  'Synthetic Reports Other School', 'Asia/Kolkata', 'INR', 'ACTIVE', now(), now(), 0)
                """, otherSchoolId, organisationId);
        jdbc.update("""
                INSERT INTO organisations(
                  id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'synthetic-reports-other-org', 'Synthetic Reports Other Organisation',
                  'Synthetic Reports Other Organisation', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(
                  id,organisation_id,slug,legal_name,display_name,time_zone,currency_code,
                  status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-reports-other-org-school',
                  'Synthetic Reports Other Organisation School',
                  'Synthetic Reports Other Organisation School', 'Asia/Kolkata', 'INR',
                  'ACTIVE', now(), now(), 0)
                """, otherOrganisationSchoolId, otherOrganisationId);
        insertSyntheticUser(otherAuditorUserId, "reports-other-auditor@example.test");
        insertSyntheticMembership(otherAuditorMembershipId, otherAuditorUserId,
                otherOrganisationId, otherOrganisationSchoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                otherAuditorMembershipId, auditorRoleId);

        AuthenticatedUser auditor = new AuthenticatedUser(
                auditorUserId, UUID.randomUUID(), "reports-auditor@example.test",
                "reports-auditor@example.test", "Synthetic auditor", true, organisationId,
                Set.of(auditorMembershipId), Set.of("reports.read"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                teacherUserId, UUID.randomUUID(), "reports-teacher@example.test",
                "reports-teacher@example.test", "Synthetic teacher", true, organisationId,
                Set.of(teacherMembershipId), Set.of("academics.read"), List.of());
        AuthenticatedUser otherOrganisationAuditor = new AuthenticatedUser(
                otherAuditorUserId, UUID.randomUUID(), "reports-other-auditor@example.test",
                "reports-other-auditor@example.test", "Synthetic other auditor", true,
                otherOrganisationId, Set.of(otherAuditorMembershipId),
                Set.of("reports.read"), List.of());

        var snapshot = reports.overview(auditor, schoolId);
        var retrySnapshot = reports.overview(auditor, schoolId);
        var admissions = snapshot.sections().stream()
                .filter(section -> "admissions".equals(section.code()))
                .findFirst().orElseThrow();
        var openEnquiries = admissions.metrics().stream()
                .filter(metric -> "openEnquiries".equals(metric.code()))
                .findFirst().orElseThrow();

        assertThat(snapshot.schoolId()).isEqualTo(schoolId);
        assertThat(retrySnapshot.sections()).isEqualTo(snapshot.sections());
        assertThat(snapshot.sections()).hasSize(8);
        assertThat(openEnquiries.value()).isEqualByComparingTo("1");
        assertThat(snapshot.sections())
                .flatExtracting(section -> section.metrics())
                .allSatisfy(metric -> assertThat(metric.value()).isNotNull());
        Integer auditCount = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE organisation_id=? AND school_id=? AND actor_membership_id=?
                  AND action='reports.overview.generated'
                """, Integer.class, organisationId, schoolId, auditorMembershipId);
        assertThat(auditCount).isEqualTo(2);
        assertThatThrownBy(() -> reports.overview(teacher, schoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> reports.overview(auditor, otherSchoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> reports.overview(otherOrganisationAuditor, schoolId))
                .hasMessageContaining("unavailable for this school");
        AuthenticatedUser forgedMembership = new AuthenticatedUser(
                auditorUserId, UUID.randomUUID(), "reports-auditor@example.test",
                "reports-auditor@example.test", "Synthetic auditor", true, organisationId,
                Set.of(uuid("48000000", 7)), Set.of("reports.read"), List.of());
        assertThatThrownBy(() -> reports.overview(forgedMembership, schoolId))
                .hasMessageContaining("unavailable for this school");
    }

    @Test
    @Transactional
    void familyDocumentsExposeOnlyIssuedOwnChildEvidenceAndAuditDownloads() {
        var f=familyDocumentFixture();
        try(var ignored=TenantRequestContext.open(f.parent().userId(),f.parent().activeOrganisationId())) {
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).isEmpty();
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),f.document()))
                    .hasMessageContaining("not available");
            decideReceiptDocument(f.approver(),f.document(),new DecisionRequest(f.school(),"APPROVE","Synthetic independent approval",0));
            var page=familyDocuments.list(f.parent(),f.learner(),0);
            assertThat(page.documents()).singleElement().satisfies(item->{
                assertThat(item.id()).isEqualTo(f.document());
                assertThat(item.documentKind()).isEqualTo("FEE_RECEIPT");
                assertThat(item.locale()).isEqualTo("en-IN");
                assertThat(item.sizeBytes()).isPositive();
            });
            assertThat(page.hasMore()).isFalse();
            assertThat(familyDocuments.list(f.parent(),f.learner(),1).documents()).isEmpty();
            assertThat(familyDocuments.list(f.parent(),f.learner(),0)).isEqualTo(page);
            assertThatThrownBy(()->familyDocuments.list(f.parent(),f.learner(),-1)).hasMessageContaining("valid document page");
            assertThatThrownBy(()->familyDocuments.list(f.parent(),f.learner(),10001)).hasMessageContaining("valid document page");
            var file=familyDocuments.download(f.parent(),f.learner(),f.document());
            var output=new java.io.ByteArrayOutputStream();
            file.writer().writeTo(output);
            String recordedDigest=jdbc.queryForObject("SELECT pdf_sha256 FROM official_documents WHERE id=?",String.class,f.document());
            org.mockito.Mockito.verify(officialDocumentObjectStore).copyVerifiedTo(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(file.sizeBytes()),org.mockito.ArgumentMatchers.eq(recordedDigest),
                    org.mockito.ArgumentMatchers.same(output));
            var staffFile=officialDocuments.download(f.maker(),f.school(),f.document());
            var staffOutput=new java.io.ByteArrayOutputStream();
            staffFile.writer().writeTo(staffOutput);
            org.mockito.Mockito.verify(officialDocumentObjectStore).copyVerifiedTo(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(staffFile.sizeBytes()),org.mockito.ArgumentMatchers.eq(recordedDigest),
                    org.mockito.ArgumentMatchers.same(staffOutput));
            org.mockito.Mockito.verify(officialDocumentObjectStore,org.mockito.Mockito.never()).copyTo(
                    org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
            assertThat(file.filename()).endsWith(".pdf").doesNotContain("Synthetic");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='documents.family.download_authorized' AND subject_id=? AND actor_user_id=?",
                    Integer.class,f.document().toString(),f.parent().userId())).isEqualTo(1);
            when(officialDocumentObjectStore.bucket()).thenReturn("different-private-bucket");
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),f.document())).hasMessageContaining("temporarily unavailable");
            when(officialDocumentObjectStore.bucket()).thenReturn("synthetic-private-documents");
            officialDocuments.cancel(f.maker(),f.document(),new TransitionRequest(f.school(),1,"Synthetic cancellation"));
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).isEmpty();
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),f.document())).hasMessageContaining("not available");
            assertThat(officialDocuments.download(f.maker(),f.school(),f.document()).sizeBytes()).isPositive();
        }
    }

    @Test
    @Transactional
    void feeReceiptDocumentsFreezeTruthfulPaymentTimingWithoutRelabellingLegacyEvidence() throws Exception {
        var f = familyDocumentFixture();
        UUID organisation = f.maker().activeOrganisationId();
        UUID originalPayment = jdbc.queryForObject("SELECT source_id FROM official_documents WHERE id=?", UUID.class, f.document());
        UUID account = jdbc.queryForObject("SELECT learner_fee_account_id FROM fee_payments WHERE id=?", UUID.class, originalPayment);
        UUID enrolment = jdbc.queryForObject("SELECT enrolment_id FROM learner_fee_accounts WHERE id=?", UUID.class, account);
        UUID session = jdbc.queryForObject("SELECT academic_session_id FROM learner_fee_accounts WHERE id=?", UUID.class, account);
        UUID template = jdbc.queryForObject("SELECT template_id FROM official_documents WHERE id=?", UUID.class, f.document());
        var bridge = applicationContext.getBean(com.educore.api.finance.FinanceReceiptDocumentBridge.class);
        UUID actor = f.maker().membershipIds().iterator().next();
        var initial = officialDocuments.review(f.maker(), f.school(), f.document()).sourceSnapshot();
        assertThat(initial.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(initial.path("payment").path("actualReceivedAt").asText()).isEqualTo("2026-08-22T04:31:00Z");
        assertThat(initial.path("payment").has("externalReference")).isFalse();
        assertThat(initial.path("payment").has("receivedOn")).isFalse();
        assertThat(initial.toString()).doesNotContain("bankCredit", "verifiedBy", "reviewReason");
        assertThatThrownBy(() -> bridge.receiptTiming(f.maker().userId(), organisation, f.school(), originalPayment, actor))
                .hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> bridge.receiptTiming(f.maker().userId(),
                UUID.randomUUID(), f.school(), originalPayment, actor)))
                .hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> bridge.receiptTiming(f.maker().userId(),
                organisation, UUID.randomUUID(), originalPayment, actor)))
                .hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> bridge.receiptTiming(f.maker().userId(),
                organisation, f.school(), originalPayment, f.membership())))
                .hasMessageContaining("authorized school work");
        // Keep deliberately wrong/background contexts wrong; never authorize these negative calls through a helper.
        try (var ignored = TenantRequestContext.open(f.parent().userId(), organisation)) {
            assertThatThrownBy(() -> bridge.receiptTiming(f.maker().userId(), organisation, f.school(), originalPayment, actor))
                    .hasMessageContaining("authorized school work");
        }
        try (var ignored = TenantRequestContext.open(null, organisation)) {
            assertThatThrownBy(() -> bridge.receiptTiming(f.maker().userId(), organisation, f.school(), originalPayment, actor))
                    .hasMessageContaining("authorized school work");
        }
        inManualFinanceScope(f.maker(), () -> {
            jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)", String.class, f.parent().userId().toString());
            assertThatThrownBy(() -> bridge.receiptTiming(f.maker().userId(), organisation, f.school(), originalPayment, actor))
                    .hasMessageContaining("authorized school work");
            return null;
        });

        UUID head = finance.createFeeHead(f.maker(), new FeeHeadRequest(f.school(), "SYN_DOC_TIMING",
                "Synthetic timing tuition", null, "TUITION", false, "ACTIVE", null, "Synthetic timing setup")).id();
        UUID plan = finance.createPlan(f.maker(), new FeePlanRequest(f.school(), session, null, "SYN_DOC_TIMING", 1,
                "Synthetic timing fees", "VI", "INR", "SYN/DOC", null, null, "Synthetic timing setup")).id();
        finance.addPlanItem(f.maker(), plan, new PlanItemRequest(f.school(), head, 1, "Synthetic timing tuition",
                LocalDate.of(2026, 9, 1), new BigDecimal("1000.00"), true, "Synthetic timing charge"));
        finance.activatePlan(f.maker(), plan, new PlanActivationRequest(f.school(), 0, false, "Synthetic timing plan activation"));
        finance.assignPlan(f.maker(), new PlanAssignmentRequest(f.school(), plan, enrolment, "Synthetic timing obligation"));
        UUID cash = inManualFinanceScope(f.maker(), () -> finance.recordPayment(f.maker(), "synthetic-doc-date-only-cash",
                new PaymentRequest(f.school(), account, "CASH", null, new BigDecimal("100.00"),
                        LocalDate.of(2026, 9, 9), "Synthetic counted cash", List.of()))).id();
        var cashDocument = requestReceiptDocument(f.maker(), "synthetic-doc-cash-timing",
                new IssueRequest(f.school(), template, "FEE_PAYMENT", cash, null, "Synthetic date-only receipt"));
        var cashSource = officialDocuments.review(f.maker(), f.school(), cashDocument.id()).sourceSnapshot().path("payment");
        assertThat(cashSource.path("receivedTimePrecision").asText()).isEqualTo("DATE_ONLY");
        assertThat(cashSource.path("actualReceivedAt").isNull() || cashSource.path("actualReceivedAt").isMissingNode()).isTrue();
        assertThat(cashSource.path("originalReceivedOn").asText()).isEqualTo("2026-09-09");

        var close = finance.proposeDayClose(f.maker(), new DayCloseRequest(f.school(), LocalDate.of(2026, 9, 10),
                closeDeclarations("0.00"), "Synthetic independent close before late evidence", null));
        finance.decideDayClose(f.approver(), close.id(), new DayCloseDecisionRequest(f.school(), "APPROVED",
                "Synthetic independent close approval", 0));
        String closedEvidence = jdbc.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?", String.class, close.id());
        Instant received = Instant.parse("2026-09-10T03:05:07.123Z");
        UUID delayed = inManualFinanceScope(f.maker(), () -> finance.recordPayment(f.maker(), "synthetic-doc-delayed-upi",
                new PaymentRequest(f.school(), account, "SCHOOL_UPI", "SYN_DOC_UPI_TIMING", new BigDecimal("200.00"),
                        LocalDate.of(2026, 9, 10), "Synthetic verified credit", List.of(), received,
                        LocalDate.of(2026, 9, 11), new com.educore.api.finance.ManualCollectionEvidence.BankCreditVerification(
                        "BANK_STATEMENT_CREDIT", true, "SYN_DOC_UPI_TIMING"), "Synthetic reviewed open-date posting"))).id();
        var delayedRequest = new IssueRequest(f.school(), template, "FEE_PAYMENT", delayed, null, "Synthetic late receipt evidence");
        var pending = requestReceiptDocument(f.maker(), "synthetic-doc-delayed-timing", delayedRequest);
        var lateLookup = inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), delayed, 0));
        assertThat(lateLookup.paymentId()).isEqualTo(delayed);
        assertThat(lateLookup.sourceStatus()).isEqualTo("POSTED");
        assertThat(lateLookup.documents()).singleElement().satisfies(d -> assertThat(d.id()).isEqualTo(pending.id()));
        var delayedSource = officialDocuments.review(f.maker(), f.school(), pending.id()).sourceSnapshot();
        assertThat(delayedSource.path("payment").path("postingOn").asText()).isEqualTo("2026-09-11");
        assertThat(delayedSource.path("payment").path("originalReceivedOn").asText()).isEqualTo("2026-09-10");
        assertThat(Instant.parse(delayedSource.path("payment").path("actualReceivedAt").asText())).isEqualTo(received);
        assertThat(delayedSource.path("payment").path("schoolTimeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(delayedSource.toString()).doesNotContain("SYN_DOC_UPI_TIMING", "Synthetic reviewed open-date posting",
                "bankCreditReference", "verifiedByMembershipId");
        assertThat(jdbc.queryForObject("SELECT evidence_snapshot::text FROM fee_day_closes WHERE id=?", String.class, close.id()))
                .isEqualTo(closedEvidence);

        UUID cheque = inManualFinanceScope(f.maker(), () -> finance.recordPayment(f.maker(), "synthetic-doc-legacy-date-only",
                new PaymentRequest(f.school(), account, "CHEQUE", "SYN_DOC_CHEQUE_TIMING", new BigDecimal("100.00"),
                        LocalDate.of(2026, 9, 12), "Synthetic unchanged date-only source", List.of()))).id();
        var legacyTiming = inManualFinanceScope(f.maker(), () -> bridge.receiptTiming(f.maker().userId(), organisation, f.school(), cheque, actor));
        assertThat(legacyTiming.receivedTimePrecision()).isEqualTo("DATE_ONLY");
        assertThat(legacyTiming.actualReceivedAt()).isNull();
        assertThat(legacyTiming.schoolTimeZone()).isNull();

        var pdf = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        org.mockito.Mockito.doAnswer(invocation -> { pdf.set(java.nio.file.Files.readAllBytes(invocation.getArgument(1, java.nio.file.Path.class))); return null; })
                .when(officialDocumentObjectStore).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        // A current school-zone change cannot rewrite the frozen received-funds evidence.
        jdbc.update("UPDATE schools SET time_zone='UTC' WHERE id=?", f.school());
        decideReceiptDocument(f.approver(), pending.id(), new DecisionRequest(f.school(), "APPROVE", "Synthetic independent timing approval", 0));
        String frozen = jdbc.queryForObject("SELECT source_snapshot::text FROM official_documents WHERE id=?", String.class, pending.id());
        assertThat(launchUpgradeMapper.readTree(frozen)).isEqualTo(delayedSource);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.get())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertThat(text).contains("Financial posting date", "2026-09-11", "Original received date", "2026-09-10",
                    "2026-09-10T08:35:07.123+05:30", "[Asia/Kolkata]");
            assertThat(text).doesNotContain("SYN_DOC_UPI_TIMING", "Synthetic reviewed open-date posting");
        }
        assertThat(requestReceiptDocument(f.maker(), "synthetic-doc-delayed-timing", delayedRequest).id()).isEqualTo(pending.id());
        assertThat(jdbc.queryForObject("SELECT source_snapshot::text FROM official_documents WHERE id=?", String.class, pending.id())).isEqualTo(frozen);
        assertDatabaseRejected(() -> jdbc.update("UPDATE official_documents SET source_snapshot='{}'::jsonb WHERE id=?", pending.id()),
                "source evidence is immutable");
        // Exercise the new read boundary as a real non-owner role, not merely an owner with application filters.
        String timingRole = "educore_receipt_timing_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(timingRole).matches("^[a-z][a-z0-9_]{1,62}$");
        String previousTimingUser = jdbc.queryForObject("SELECT current_setting('app.current_user_id',true)", String.class);
        String previousTimingOrganisation = jdbc.queryForObject("SELECT current_setting('app.current_organisation_id',true)", String.class);
        jdbc.execute("CREATE ROLE " + timingRole + " NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + timingRole);
        jdbc.execute("GRANT SELECT ON fee_payments,fee_manual_collection_evidence,fee_gateway_captures,fee_gateway_attempts,"
                + "memberships,user_accounts,organisations,schools,membership_roles,roles,role_capabilities "
                + "TO " + timingRole);
        jdbc.execute("SET LOCAL ROLE " + timingRole);
        try {
            assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT current_user<>pg_get_userbyid(relowner) FROM pg_class WHERE oid='fee_payments'::regclass", Boolean.class)).isTrue();
            var runtimeTiming = inManualFinanceScope(f.maker(), () -> bridge.receiptTiming(f.maker().userId(), organisation,
                    f.school(), delayed, actor));
            assertThat(runtimeTiming.actualReceivedAt()).isEqualTo(received);
            assertThat(runtimeTiming.originalReceivedOn()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(runtimeTiming.postingOn()).isEqualTo(LocalDate.of(2026, 9, 11));
            // An actual different user cannot borrow the otherwise-authorized document membership.
            assertThatThrownBy(() -> inManualFinanceScope(f.parent(), () -> bridge.receiptTiming(f.parent().userId(),
                    organisation, f.school(), delayed, actor))).hasMessageContaining("authorized school work");
            try (var ignored = TenantRequestContext.open(null, organisation)) {
                jdbc.queryForObject("SELECT set_config('app.current_user_id','',true)", String.class);
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, organisation.toString());
                assertThatThrownBy(() -> bridge.receiptTiming(f.maker().userId(), organisation, f.school(), delayed, actor))
                        .hasMessageContaining("authorized school work");
            }
            UUID foreign = UUID.randomUUID();
            try (var ignored = TenantRequestContext.open(f.maker().userId(), foreign)) {
                jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)", String.class, f.maker().userId().toString());
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, foreign.toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fee_payments", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fee_manual_collection_evidence", Integer.class)).isZero();
                assertThatThrownBy(() -> bridge.receiptTiming(f.maker().userId(), foreign, f.school(), delayed, actor))
                        .hasMessageContaining("authorized school work");
            }
        } finally {
            jdbc.execute("RESET ROLE");
            jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)", String.class, previousTimingUser == null ? "" : previousTimingUser);
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, previousTimingOrganisation == null ? "" : previousTimingOrganisation);
        }
        jdbc.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?", actor);
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> bridge.receiptTiming(f.maker().userId(),
                organisation, f.school(), delayed, actor))).hasMessageContaining("authorized school work");
    }

    @Test
    @Transactional
    void exactFeeReceiptLookupIsPagedScopedAndFamilyPaymentFiltersNeverGrantIssuance() throws Exception {
        var f = familyDocumentFixture();
        UUID organisation = f.maker().activeOrganisationId();
        UUID payment = jdbc.queryForObject("SELECT source_id FROM official_documents WHERE id=?", UUID.class, f.document());
        UUID template = jdbc.queryForObject("SELECT template_id FROM official_documents WHERE id=?", UUID.class, f.document());
        UUID session = jdbc.queryForObject("SELECT academic_session_id FROM student_enrolments WHERE learner_id=?", UUID.class, f.learner());
        UUID campus = UUID.fromString("10000000-0000-4000-8000-000000000003");
        var pending = inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0));
        assertThat(pending.schoolId()).isEqualTo(f.school());
        assertThat(pending.paymentId()).isEqualTo(payment);
        assertThat(pending.sourceStatus()).isEqualTo("POSTED");
        assertThat(pending.canIssue()).isTrue();
        assertThat(pending.templates()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(template);
            assertThat(t.documentKind()).isEqualTo("FEE_RECEIPT");
            assertThat(t.status()).isEqualTo("PUBLISHED");
        });
        assertThat(pending.documents()).singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("PENDING_APPROVAL"));
        var draft = officialDocuments.createTemplate(f.maker(), documentTemplate(f.school(), "EXACT_DRAFT", "hi-IN", "Synthetic draft excluded"));
        assertThat(inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0)).templates())
                .noneMatch(t -> t.id().equals(draft.id()));
        assertThat(inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, payment)).documents()).isEmpty();
        assertThatThrownBy(() -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> inManualFinanceScope(f.parent(), () -> officialDocuments.feeReceipts(f.parent(), f.school(), payment, 0)))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, -1)))
                .hasMessageContaining("valid receipt page");
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), UUID.randomUUID(), 0)))
                .hasMessageContaining("source was not found");
        assertThatThrownBy(() -> inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), UUID.randomUUID(), payment, 0)))
                .hasMessageContaining("unavailable for this school");

        // Create real rejected history through existing independent approval, not synthetic row bypasses.
        decideReceiptDocument(f.approver(), f.document(), new DecisionRequest(f.school(), "REJECT", "Synthetic history only", 0));
        for (int i = 0; i < 25; i++) {
            var next = requestReceiptDocument(f.maker(), "synthetic-exact-receipt-" + i,
                    new IssueRequest(f.school(), template, "FEE_PAYMENT", payment, null, "Synthetic history request"));
            decideReceiptDocument(f.approver(), next.id(), new DecisionRequest(f.school(), "REJECT", "Synthetic historical rejection", 0));
        }
        var request = new IssueRequest(f.school(), template, "FEE_PAYMENT", payment, null, "Synthetic exact current receipt");
        var current = requestReceiptDocument(f.maker(), "synthetic-exact-current", request);
        var issued = decideReceiptDocument(f.approver(), current.id(), new DecisionRequest(f.school(), "APPROVE", "Synthetic independent current approval", 0));
        assertThat(requestReceiptDocument(f.maker(), "synthetic-exact-current", request).id()).isEqualTo(issued.id());
        var first = inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0));
        var second = inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 1));
        assertThat(first.pageSize()).isEqualTo(25);
        assertThat(first.documents()).hasSize(25).allSatisfy(d -> {
            assertThat(d.sourceId()).isEqualTo(payment);
            assertThat(d.sourceType()).isEqualTo("FEE_PAYMENT");
            assertThat(d.documentKind()).isEqualTo("FEE_RECEIPT");
        });
        assertThat(first.hasMore()).isTrue();
        assertThat(second.documents()).hasSize(2);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.documents()).extracting(com.educore.api.documents.OfficialDocumentSnapshot.DocumentItem::id)
                .doesNotContainAnyElementsOf(second.documents().stream().map(com.educore.api.documents.OfficialDocumentSnapshot.DocumentItem::id).toList());
        assertThat(inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 2)).documents()).isEmpty();
        assertThat(first.templates()).singleElement().satisfies(t -> assertThat(t.id()).isEqualTo(template));
        String metadata = launchUpgradeMapper.writeValueAsString(first);
        assertThat(metadata).doesNotContain("sourceSnapshot", "templateSnapshot", "storageKey", "storageBucket", "verificationToken",
                "bankCreditReference", "verifiedByMembershipId", "SYN_DOC_BANK_");
        var filtered = inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, payment));
        assertThat(filtered.documents()).singleElement().satisfies(d -> assertThat(d.id()).isEqualTo(issued.id()));
        assertThat(inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0))).isEqualTo(filtered);
        assertThat(launchUpgradeMapper.writeValueAsString(filtered)).doesNotContain("sourceId", "sourceSnapshot", "decisionReason", "requestedBy", "storage", "bank");
        UUID otherPayment = insertSyntheticFeePaymentSource("4f200000", 200, organisation, f.school(), campus, session,
                f.maker().membershipIds().iterator().next());
        assertThat(inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), otherPayment, 0)).documents()).isEmpty();
        assertThatThrownBy(() -> inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, otherPayment)))
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, UUID.randomUUID())))
                .hasMessageContaining("not available");

        UUID splitUser = UUID.randomUUID(), readRole = UUID.randomUUID(), feesRole = UUID.randomUUID();
        UUID readMembership = UUID.randomUUID(), feesMembership = UUID.randomUUID();
        insertSyntheticUser(splitUser, "synthetic-exact-split@example.test");
        insertSyntheticMembership(readMembership, splitUser, organisation, f.school());
        jdbc.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,?,'ACTIVE',now(),now(),now())
                """, feesMembership, splitUser, organisation, f.school(), campus);
        jdbc.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,system_role,created_at,updated_at)
                VALUES (?,?,'SYN_EXACT_DOC_READ','Synthetic receipt document read',false,now(),now()),
                       (?,?,'SYN_EXACT_FEE_READ','Synthetic receipt fee read',false,now(),now())
                """, readRole, organisation, feesRole, organisation);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,'documents.read'),(?,'fees.read')", readRole, feesRole);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?),(?,?)", readMembership, readRole, feesMembership, feesRole);
        var split = new AuthenticatedUser(splitUser, UUID.randomUUID(), "synthetic-split@example.test", null,
                "Synthetic split receipt reader", true, organisation, Set.of(readMembership, feesMembership), Set.of("documents.read", "fees.read"), List.of());
        assertThatThrownBy(() -> inManualFinanceScope(split, () -> officialDocuments.feeReceipts(split, f.school(), payment, 0)))
                .hasMessageContaining("unavailable for this school");
        // The older documents-only membership must not mask another membership that itself holds both rights.
        jdbc.update("UPDATE memberships SET created_at=now()-interval '1 hour' WHERE id=?", readMembership);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,'documents.read')", feesRole);
        var laterPermitted = inManualFinanceScope(split, () -> officialDocuments.feeReceipts(split, f.school(), payment, 0));
        assertThat(laterPermitted.documents()).hasSize(25);
        assertThat(laterPermitted.canIssue()).isFalse();
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,'fees.read')", readRole);
        assertThat(inManualFinanceScope(split, () -> officialDocuments.feeReceipts(split, f.school(), payment, 0)).canIssue()).isFalse();

        String lookupRole = "educore_receipt_lookup_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(lookupRole).matches("^[a-z][a-z0-9_]{1,62}$");
        jdbc.execute("CREATE ROLE " + lookupRole + " NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + lookupRole);
        jdbc.execute("GRANT SELECT ON official_documents,official_document_templates,fee_payments,learner_fee_accounts,"
                + "memberships,user_accounts,organisations,schools,membership_roles,roles,role_capabilities,guardians,learner_guardian_relationships,learners,"
                + "learner_report_cards,result_publications,student_enrolments TO " + lookupRole);
        jdbc.execute("SET LOCAL ROLE " + lookupRole);
        try {
            assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT current_user<>pg_get_userbyid(relowner) FROM pg_class WHERE oid='official_documents'::regclass", Boolean.class)).isTrue();
            assertThat(inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 1)).documents()).hasSize(2);
            assertThat(inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, payment)).documents()).hasSize(1);
            inManualFinanceScope(f.maker(), () -> {
                jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)", String.class, f.parent().userId().toString());
                assertThatThrownBy(() -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0))
                        .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class, failure ->
                                assertThat(failure.code()).isIn("DOCUMENT_SCOPE_DENIED", "DOCUMENT_SOURCE_NOT_FOUND"));
                return null;
            });
            inManualFinanceScope(f.maker(), () -> {
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, UUID.randomUUID().toString());
                assertThatThrownBy(() -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0))
                        .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class, failure ->
                                assertThat(failure.code()).isIn("DOCUMENT_SCOPE_DENIED", "DOCUMENT_SOURCE_NOT_FOUND"));
                return null;
            });
            inManualFinanceScope(f.parent(), () -> {
                jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)", String.class, f.maker().userId().toString());
                assertThatThrownBy(() -> familyDocuments.list(f.parent(), f.learner(), 0, payment)).hasMessageContaining("not available");
                return null;
            });
            var foreign = new AuthenticatedUser(f.maker().userId(), UUID.randomUUID(), "synthetic-foreign@example.test", null,
                    "Synthetic foreign receipt context", true, UUID.randomUUID(), f.maker().membershipIds(), Set.of("documents.read", "fees.read"), List.of());
            assertThatThrownBy(() -> inManualFinanceScope(foreign, () -> officialDocuments.feeReceipts(foreign, f.school(), payment, 0)))
                    .hasMessageContaining("unavailable for this school");
            assertThatThrownBy(() -> inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, otherPayment)))
                    .hasMessageContaining("not available");
        } finally {
            jdbc.execute("RESET ROLE");
        }
        inManualFinanceScope(f.maker(), () -> finance.reversePayment(f.maker(), payment,
                new PaymentReversalRequest(f.school(), 0, "Synthetic controlled source reversal")));
        var reversed = inManualFinanceScope(f.maker(), () -> officialDocuments.feeReceipts(f.maker(), f.school(), payment, 0));
        assertThat(reversed.sourceStatus()).isEqualTo("REVERSED");
        assertThat(reversed.canIssue()).isFalse();
        assertThat(reversed.documents()).anyMatch(d -> d.id().equals(issued.id()) && d.status().equals("ISSUED"));
        assertThat(inManualFinanceScope(f.parent(), () -> familyDocuments.list(f.parent(), f.learner(), 0, payment)).documents()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM official_documents WHERE id=?", String.class, issued.id())).isEqualTo("ISSUED");
    }

    @Test
    @Transactional
    void familyDocumentsRecheckTenantMembershipAndFamilyAuthorityOnEveryRead() {
        var f=familyDocumentFixture();
        decideReceiptDocument(f.approver(),f.document(),new DecisionRequest(f.school(),"APPROVE","Synthetic independent approval",0));
        assertThatThrownBy(()->familyDocuments.list(f.parent(),f.learner(),0)).hasMessageContaining("not available");
        try(var ignored=TenantRequestContext.open(f.parent().userId(),UUID.randomUUID())) {
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),f.document())).hasMessageContaining("not available");
        }
        try(var ignored=TenantRequestContext.open(f.maker().userId(),f.parent().activeOrganisationId())) {
            assertThatThrownBy(()->familyDocuments.list(f.parent(),f.learner(),0)).hasMessageContaining("not available");
        }
        var forgedTenant=new AuthenticatedUser(f.parent().userId(),f.parent().sessionId(),"synthetic@example.test",null,
                "Synthetic",true,UUID.randomUUID(),f.parent().membershipIds(),Set.of("portal.guardian"),List.of());
        try(var ignored=TenantRequestContext.open(forgedTenant.userId(),forgedTenant.activeOrganisationId())) {
            assertThatThrownBy(()->familyDocuments.list(forgedTenant,f.learner(),0)).hasMessageContaining("not available");
        }
        try(var ignored=TenantRequestContext.open(f.parent().userId(),f.parent().activeOrganisationId())) {
            assertThatThrownBy(()->familyDocuments.list(f.parent(),UUID.randomUUID(),0)).hasMessageContaining("not available");
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),UUID.randomUUID())).hasMessageContaining("not available");
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '1 day',valid_until=now()-interval '1 second' WHERE id=?",f.membership());
            assertFamilyDocumentDenied(f);
            jdbc.update("UPDATE memberships SET valid_until=NULL,valid_from=now()+interval '1 day' WHERE id=?",f.membership());
            assertFamilyDocumentDenied(f);
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '1 day' WHERE id=?",f.membership());
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE guardian_id=?",f.guardian());
            assertFamilyDocumentDenied(f);
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE' WHERE guardian_id=?",f.guardian());
            for(String table:List.of("guardians","learners","user_accounts","schools","organisations")) {
                UUID id=switch(table) {case "guardians"->f.guardian();case "learners"->f.learner();case "user_accounts"->f.parent().userId();case "schools"->f.school();default->f.parent().activeOrganisationId();};
                String inactive=Set.of("guardians","learners").contains(table)?"INACTIVE":"SUSPENDED";
                jdbc.update("UPDATE "+table+" SET status=? WHERE id=?",inactive,id);
                assertFamilyDocumentDenied(f);
                jdbc.update("UPDATE "+table+" SET status='ACTIVE' WHERE id=?",id);
            }
            jdbc.update("DELETE FROM membership_roles WHERE membership_id=?",f.membership());
            assertFamilyDocumentDenied(f);
            org.mockito.Mockito.verify(officialDocumentObjectStore,org.mockito.Mockito.never()).copyTo(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(officialDocumentObjectStore,org.mockito.Mockito.never()).copyVerifiedTo(
                    org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    @Transactional
    void familyReportDocumentsRequireCurrentPublicationAndPreserveAcademicHistory() {
        var f=familyDocumentFixture();
        UUID org=f.parent().activeOrganisationId(),session=jdbc.queryForObject("SELECT academic_session_id FROM student_enrolments WHERE learner_id=?",UUID.class,f.learner());
        UUID term=UUID.randomUUID(),section=UUID.randomUUID(),scheme=UUID.randomUUID(),publication=UUID.randomUUID(),card=UUID.randomUUID();
        UUID makerMembership=f.maker().membershipIds().iterator().next();
        jdbc.update("""
                INSERT INTO academic_terms(id,organisation_id,school_id,academic_session_id,code,display_name,starts_on,ends_on,status,created_at,updated_at,row_version)
                VALUES(?,?,?,?,'TERM1','Synthetic term',DATE '2026-04-01',DATE '2026-09-30','ACTIVE',now(),now(),0)
                """,term,org,f.school(),session);
        jdbc.update("""
                INSERT INTO class_sections(id,organisation_id,school_id,campus_id,academic_session_id,grade_code,code,display_name,status,created_at,updated_at,row_version)
                VALUES(?,?,?,'10000000-0000-4000-8000-000000000003',?,'VI','A','Synthetic VI A','ACTIVE',now(),now(),0)
                """,section,org,f.school(),session);
        jdbc.update("""
                INSERT INTO assessment_schemes(id,organisation_id,school_id,academic_session_id,grade_code,scheme_name,aggregation_mode,status,activated_by_membership_id,activated_at,created_at,updated_at,row_version)
                VALUES(?,?,?,?,'VI','Synthetic scheme','DESCRIPTIVE','ACTIVE',?,now(),now(),now(),0)
                """,scheme,org,f.school(),session,makerMembership);
        jdbc.update("""
                INSERT INTO result_publications(id,organisation_id,school_id,academic_session_id,academic_term_id,class_section_id,assessment_scheme_id,
                  publication_version,publication_name,status,published_by_membership_id,published_at,created_at)
                VALUES(?,?,?,?,?,?,?,1,'Synthetic result','PUBLISHED',?,now(),now())
                """,publication,org,f.school(),session,term,section,scheme,makerMembership);
        jdbc.update("""
                INSERT INTO learner_report_cards(id,organisation_id,school_id,result_publication_id,learner_id,enrolment_id,
                  report_number,learner_name_snapshot,section_name_snapshot,term_name_snapshot,report_snapshot,snapshot_sha256,published_at)
                VALUES(?,?,?,?,?,?,'SYN-REPORT-1','Synthetic child','Synthetic VI A','Synthetic term','{}',?,now())
                """,card,org,f.school(),publication,f.learner(),uuid("4f000000",32),"a".repeat(64));
        var template=officialDocuments.createTemplate(f.maker(),new TemplateRequest(f.school(),"FAMILY_REPORT",1,null,"REPORT_CARD",
                "Synthetic report","en-IN","Synthetic report",null,"Synthetic evidence","Principal","#23483F",true,java.util.Map.of(),null,"Synthetic template"));
        officialDocuments.publishTemplate(f.approver(),template.id(),new TransitionRequest(f.school(),0,"Synthetic independent publication"));
        var issue=officialDocuments.requestIssue(f.maker(),"synthetic-family-report",new IssueRequest(f.school(),template.id(),"LEARNER_REPORT_CARD",card,null,"Synthetic report request"));
        officialDocuments.decideIssue(f.approver(),issue.id(),new DecisionRequest(f.school(),"APPROVE","Synthetic independent approval",0));
        try(var ignored=TenantRequestContext.open(f.parent().userId(),org)) {
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).singleElement()
                    .satisfies(item->assertThat(item.documentKind()).isEqualTo("REPORT_CARD"));
            assertThat(familyDocuments.download(f.parent(),f.learner(),issue.id()).sizeBytes()).isPositive();
            jdbc.update("UPDATE result_publications SET status='REVOKED',revoked_by_membership_id=?,revoked_at=now(),revocation_reason='Synthetic revocation' WHERE id=?",makerMembership,publication);
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).isEmpty();
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),issue.id())).hasMessageContaining("not available");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_report_cards WHERE id=?",Integer.class,card)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM official_documents WHERE id=?",String.class,issue.id())).isEqualTo("ISSUED");
        }
    }

    @Test
    @Transactional
    void familyReceiptDocumentsStopAtControlledPaymentReversal() {
        var f=familyDocumentFixture();
        decideReceiptDocument(f.approver(),f.document(),new DecisionRequest(f.school(),"APPROVE","Synthetic independent approval",0));
        UUID payment=jdbc.queryForObject("SELECT source_id FROM official_documents WHERE id=?",UUID.class,f.document());
        try(var ignored=TenantRequestContext.open(f.parent().userId(),f.parent().activeOrganisationId())) {
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).hasSize(1);
            inManualFinanceScope(f.maker(), () -> finance.reversePayment(f.maker(),payment,
                    new com.educore.api.finance.FinanceController.PaymentReversalRequest(f.school(),0,"Synthetic controlled source reversal")));
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).isEmpty();
            assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),f.document())).hasMessageContaining("not available");
            assertThat(jdbc.queryForObject("SELECT status FROM official_documents WHERE id=?",String.class,f.document())).isEqualTo("ISSUED");
        }
    }

    @Test
    @Transactional
    void officialDocumentsRejectSuspendedAccountSchoolAndOrganisationAuthority() {
        var f=familyDocumentFixture();
        for(String table:List.of("user_accounts","schools","organisations")) {
            UUID id=switch(table) {case "user_accounts"->f.maker().userId();case "schools"->f.school();default->f.maker().activeOrganisationId();};
            jdbc.update("UPDATE "+table+" SET status='SUSPENDED' WHERE id=?",id);
            assertThatThrownBy(()->officialDocuments.snapshot(f.maker(),f.school())).hasMessageContaining("unavailable for this school");
            assertThatThrownBy(()->officialDocuments.createTemplate(f.maker(),documentTemplate(f.school(),"DENIED_TEMPLATE","en-IN","Synthetic denied template")))
                    .hasMessageContaining("unavailable for this school");
            assertThatThrownBy(()->officialDocuments.download(f.maker(),f.school(),f.document())).hasMessageContaining("unavailable for this school");
            jdbc.update("UPDATE "+table+" SET status='ACTIVE' WHERE id=?",id);
        }
        assertThat(officialDocuments.snapshot(f.maker(),f.school()).templates()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM official_document_templates WHERE template_code='DENIED_TEMPLATE'",Integer.class)).isZero();
    }

    @Test
    @Transactional
    void officialDocumentsExcludeForeignOrganisationRolesFromActionsAndSourcePermissions() {
        var f=familyDocumentFixture();
        UUID foreignOrg=UUID.randomUUID(),foreignRole=UUID.randomUUID(),localRole=UUID.randomUUID();
        UUID membership=f.maker().membershipIds().iterator().next();
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,created_at,updated_at,row_version)
                VALUES(?,'synthetic-document-role-owner','Synthetic other owner','Synthetic other owner','ACTIVE','en-IN',now(),now(),0)
                """,foreignOrg);
        jdbc.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,system_role,created_at,updated_at,row_version)
                VALUES(?,?,'SYN_DOCUMENT_READER','Synthetic document reader',false,now(),now(),0),
                      (?,?,'SYN_FOREIGN_DOCUMENTS','Synthetic foreign documents',false,now(),now(),0)
                """,localRole,f.maker().activeOrganisationId(),foreignRole,foreignOrg);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,'documents.read'),(?,'documents.issue'),(?,'documents.configure'),(?,'documents.approve'),(?,'documents.revoke'),(?,'fees.read'),(?,'assessment.read')",
                localRole,foreignRole,foreignRole,foreignRole,foreignRole,foreignRole,foreignRole);
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?",membership);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?),(?,?)",membership,localRole,membership,foreignRole);
        var snapshot=officialDocuments.snapshot(f.maker(),f.school());
        assertThat(snapshot.canIssue()).isFalse();
        assertThat(snapshot.canConfigure()).isFalse();
        assertThat(snapshot.canApprove()).isFalse();
        assertThat(snapshot.canRevoke()).isFalse();
        assertThat(snapshot.sources()).isEmpty();
        assertThat(snapshot.documents()).isEmpty();
        assertThatThrownBy(()->officialDocuments.createTemplate(f.maker(),documentTemplate(f.school(),"FOREIGN_DENIED","en-IN","Synthetic denied template")))
                .hasMessageContaining("unavailable for this school");
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=? AND role_id=?",membership,localRole);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,'documents.read')",foreignRole);
        assertThatThrownBy(()->officialDocuments.snapshot(f.maker(),f.school())).hasMessageContaining("unavailable for this school");
    }

    @Test
    @Transactional
    void officialDocumentTemplatesRequireAnotherPersonNotAnotherMembership() {
        var f=familyDocumentFixture();
        var template=officialDocuments.createTemplate(f.maker(),
                documentTemplate(f.school(),"SYN_PERSON_CHECK","hi-IN","Synthetic person-level approval"));
        var alternate=alternateDocumentMembership(f);
        assertThatThrownBy(()->officialDocuments.publishTemplate(alternate,template.id(),
                new TransitionRequest(f.school(),0,"Synthetic alternate membership attempt")))
                .hasMessageContaining("different authorized person");
        assertThat(jdbc.queryForObject("SELECT status FROM official_document_templates WHERE id=?",String.class,template.id()))
                .isEqualTo("DRAFT");
        officialDocuments.publishTemplate(f.approver(),template.id(),
                new TransitionRequest(f.school(),0,"Synthetic independent publication"));
    }

    @Test
    @Transactional
    void officialDocumentDecisionsRequireAnotherPersonNotAnotherMembership() {
        var f=familyDocumentFixture();
        var alternate=alternateDocumentMembership(f);
        for(String decision:List.of("APPROVE","REJECT")) {
            assertThatThrownBy(()->decideReceiptDocument(alternate,f.document(),
                    new DecisionRequest(f.school(),decision,"Synthetic alternate membership attempt",0)))
                    .hasMessageContaining("different authorized person");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM official_documents WHERE id=?",String.class,f.document()))
                .isEqualTo("PENDING_APPROVAL");
        decideReceiptDocument(f.approver(),f.document(),
                new DecisionRequest(f.school(),"APPROVE","Synthetic independent approval",0));
    }

    @Test
    @Transactional
    void appliesV055AndGuardsDocumentPeopleAgainstDirectDatabaseBypass() {
        var f=familyDocumentFixture();
        var template=officialDocuments.createTemplate(f.maker(),
                documentTemplate(f.school(),"SYN_PERSON_DB","hi-IN","Synthetic database approval"));
        var alternate=alternateDocumentMembership(f);
        UUID actor=alternate.membershipIds().iterator().next();
        UUID maker=f.maker().membershipIds().iterator().next();
        UUID approver=f.approver().membershipIds().iterator().next();
        UUID org=f.maker().activeOrganisationId();
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN
                  ('official_document_templates_guard_person','official_documents_guard_person')
                """,Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT official_document_actor_is_independent(?,?,?,?)",
                Boolean.class,approver,maker,org,f.school())).isTrue();
        for(UUID[] scope:List.of(new UUID[]{org,UUID.randomUUID()},new UUID[]{UUID.randomUUID(),f.school()})) {
            assertThat(jdbc.queryForObject("SELECT official_document_actor_is_independent(?,?,?,?)",
                    Boolean.class,approver,maker,scope[0],scope[1])).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT official_document_actor_is_independent(?,?,?,?)",
                Boolean.class,actor,maker,org,f.school())).isFalse();
        assertDatabaseRejected(()->jdbc.update("""
                UPDATE official_document_templates SET status='PUBLISHED',published_by_membership_id=?,
                  published_at=now(),updated_at=now(),row_version=row_version+1 WHERE id=?
                """,actor,template.id()),"requires an independent person");
        assertDatabaseRejected(()->jdbc.update("""
                INSERT INTO official_document_templates
                SELECT (jsonb_populate_record(NULL::official_document_templates,to_jsonb(t)||jsonb_build_object(
                  'id',CAST(? AS uuid),'template_code','SYN_DB_INSERT','status','PUBLISHED',
                  'published_by_membership_id',CAST(? AS uuid),'published_at',now()))).*
                FROM official_document_templates t WHERE t.id=?
                """,UUID.randomUUID(),actor,template.id()),"requires an independent person");
        for(String decision:List.of("ISSUED","REJECTED")) {
            assertDatabaseRejected(()->jdbc.update("""
                    UPDATE official_documents SET status=?,decided_by_membership_id=?,decided_at=now(),
                      decision_reason='Synthetic database bypass',updated_at=now(),row_version=row_version+1 WHERE id=?
                    """,decision,actor,f.document()),"requires an independent person");
        }
        assertDatabaseRejected(()->jdbc.update("""
                INSERT INTO official_documents
                SELECT (jsonb_populate_record(NULL::official_documents,to_jsonb(d)||jsonb_build_object(
                  'id',CAST(? AS uuid),'idempotency_key','synthetic-insert-person-bypass','status','REJECTED',
                  'decided_by_membership_id',CAST(? AS uuid),'decided_at',now(),
                  'decision_reason','Synthetic database bypass'))).*
                FROM official_documents d WHERE d.id=?
                """,UUID.randomUUID(),actor,f.document()),"requires an independent person");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM official_document_history WHERE official_document_id=?",
                Integer.class,f.document())).isEqualTo(1);
        decideReceiptDocument(f.approver(),f.document(),
                new DecisionRequest(f.school(),"REJECT","Synthetic independent rejection",0));
    }

    @Test
    @Transactional
    void documentPersonGuardPreservesIndependentlyPublishedDirectIssuance() {
        var f=familyDocumentFixture();
        var template=officialDocuments.createTemplate(f.maker(),new TemplateRequest(f.school(),"SYN_DIRECT",1,null,
                "FEE_RECEIPT","Synthetic direct receipt","hi-IN","Synthetic receipt",null,null,"Principal",
                "#23483F",false,java.util.Map.of(),null,"Synthetic direct issuance configuration"));
        officialDocuments.publishTemplate(f.approver(),template.id(),
                new TransitionRequest(f.school(),0,"Synthetic independent direct policy approval"));
        UUID payment=jdbc.queryForObject("SELECT source_id FROM official_documents WHERE id=?",UUID.class,f.document());
        var request=new IssueRequest(f.school(),template.id(),"FEE_PAYMENT",payment,null,"Synthetic direct receipt");
        var issued=requestReceiptDocument(f.maker(),"synthetic-direct-person-check",request);
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(requestReceiptDocument(f.maker(),"synthetic-direct-person-check",request).id()).isEqualTo(issued.id());
        officialDocuments.cancel(f.maker(),issued.id(),new TransitionRequest(f.school(),0,"Synthetic controlled cancellation"));
        assertThat(officialDocuments.download(f.maker(),f.school(),issued.id()).sizeBytes()).isPositive();
    }

    @Test
    @Transactional
    void bonafideCertificatesFreezeNumberedEvidenceAndReachOnlyOwnFamily() throws Exception {
        var f=familyDocumentFixture();
        var template=publishedBonafideTemplate(f);
        UUID enrolment=jdbc.queryForObject("SELECT id FROM student_enrolments WHERE learner_id=?",UUID.class,f.learner());
        var request=new IssueRequest(f.school(),template,"STUDENT_ENROLMENT",enrolment,null,"Synthetic enrolment certificate");
        var first=officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-one",request);
        assertThat(first.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(first.documentNumber()).matches("SYN_BONAFIDE/\\d{4}/000000001");
        assertThat(officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-one",request).id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT last_number FROM official_certificate_counters WHERE school_id=?",Long.class,f.school())).isEqualTo(1L);
        assertThatThrownBy(()->officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-one",
                new IssueRequest(f.school(),template,"STUDENT_ENROLMENT",UUID.randomUUID(),null,"Different source"))).hasMessageContaining("different document request");
        assertThatThrownBy(()->officialDocuments.decideIssue(f.maker(),first.id(),new DecisionRequest(f.school(),"APPROVE","Synthetic self approval",0)))
                .hasMessageContaining("different authorized person");
        var review=officialDocuments.review(f.approver(),f.school(),first.id());
        assertThat(review.sourceSnapshot().path("learner").path("name").asText()).isEqualTo("Synthetic document learner");
        try(var ignored=TenantRequestContext.open(f.parent().userId(),f.parent().activeOrganisationId())) {
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).isEmpty();
        }
        var pdf=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        org.mockito.Mockito.doAnswer(invocation->{pdf.set(java.nio.file.Files.readAllBytes(invocation.getArgument(1,java.nio.file.Path.class)));return null;})
                .when(officialDocumentObjectStore).put(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        officialDocuments.decideIssue(f.approver(),first.id(),new DecisionRequest(f.school(),"APPROVE","Synthetic independent certificate approval",0));
        String token;
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf.get())) {
            String text=new org.apache.pdfbox.text.PDFTextStripper().getText(document).replaceAll("\\s+","");
            var matcher=java.util.regex.Pattern.compile("/verify/document/([A-Za-z0-9_-]{43})").matcher(text);
            assertThat(matcher.find()).isTrue(); token=matcher.group(1);
        }
        assertThat(officialDocuments.verify(token).verificationStatus()).isEqualTo("VALID");
        try(var ignored=TenantRequestContext.open(f.parent().userId(),f.parent().activeOrganisationId())) {
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).extracting("id").containsExactly(first.id());
            assertThat(familyDocuments.download(f.parent(),f.learner(),first.id()).sizeBytes()).isPositive();
            assertThatThrownBy(()->familyDocuments.download(f.parent(),UUID.randomUUID(),first.id())).hasMessageContaining("not available");
        }
        var replacement=officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-two",
                new IssueRequest(f.school(),template,"STUDENT_ENROLMENT",enrolment,first.id(),"Synthetic controlled replacement"));
        assertThat(replacement.documentNumber()).endsWith("000000002");
        officialDocuments.decideIssue(f.approver(),replacement.id(),new DecisionRequest(f.school(),"APPROVE","Synthetic independent replacement",0));
        assertThat(officialDocuments.verify(token).verificationStatus()).isEqualTo("SUPERSEDED");
        jdbc.update("UPDATE academic_sessions SET status='CLOSED' WHERE id=(SELECT academic_session_id FROM student_enrolments WHERE id=?)",enrolment);
        assertThat(officialDocuments.certificateSources(f.maker(),f.school(),null,0).sources()).isEmpty();
        try(var ignored=TenantRequestContext.open(f.parent().userId(),f.parent().activeOrganisationId())) {
            assertThat(familyDocuments.list(f.parent(),f.learner(),0).documents()).extracting("id").containsExactly(replacement.id());
        }
        officialDocuments.cancel(f.maker(),replacement.id(),new TransitionRequest(f.school(),1,"Synthetic cancellation"));
        assertThat(officialDocuments.download(f.maker(),f.school(),replacement.id()).sizeBytes()).isPositive();
    }

    @Test
    @Transactional
    void bonafideCertificatesRejectChangedOrIneligibleSourcesAndMissingAuthority() {
        var f=familyDocumentFixture(); var template=publishedBonafideTemplate(f);
        assertThatThrownBy(()->officialDocuments.createTemplate(f.maker(),new TemplateRequest(f.school(),"SYN_UNSAFE",1,null,
                "BONAFIDE_CERTIFICATE","Synthetic unsafe template","en-IN","Synthetic certificate",null,null,"Principal",
                "#23483F",false,java.util.Map.of(),null,"Synthetic attempt to bypass approval")))
                .hasMessageContaining("independent approval");
        UUID enrolment=jdbc.queryForObject("SELECT id FROM student_enrolments WHERE learner_id=?",UUID.class,f.learner());
        var request=new IssueRequest(f.school(),template,"STUDENT_ENROLMENT",enrolment,null,"Synthetic certificate request");
        var pending=officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-changed",request);
        jdbc.update("UPDATE learners SET legal_name='Synthetic corrected learner' WHERE id=?",f.learner());
        assertThatThrownBy(()->officialDocuments.decideIssue(f.approver(),pending.id(),new DecisionRequest(f.school(),"APPROVE","Synthetic stale approval",0)))
                .hasMessageContaining("facts changed");
        assertThat(officialDocuments.review(f.approver(),f.school(),pending.id()).sourceSnapshot().path("learner").path("name").asText())
                .isEqualTo("Synthetic document learner");
        officialDocuments.decideIssue(f.approver(),pending.id(),new DecisionRequest(f.school(),"REJECT","Synthetic obsolete evidence",0));
        assertThatThrownBy(()->officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-wrong-type",
                new IssueRequest(f.school(),template,"FEE_PAYMENT",enrolment,null,"Synthetic wrong type"))).hasMessageContaining("current student enrolment");
        jdbc.update("UPDATE student_enrolments SET status='ENDED' WHERE id=?",enrolment);
        assertThatThrownBy(()->officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-ended",request)).hasMessageContaining("active enrolment");
        assertThatThrownBy(()->officialDocuments.certificateSources(f.parent(),f.school(),null,0)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(()->officialDocuments.review(f.maker(),UUID.randomUUID(),pending.id())).hasMessageContaining("unavailable for this school");
        var foreign=syntheticDocumentUser(f.maker().userId(),f.maker().membershipIds().iterator().next(),UUID.randomUUID(),"Synthetic wrong tenant");
        assertThatThrownBy(()->officialDocuments.review(foreign,f.school(),pending.id())).hasMessageContaining("unavailable for this school");
        jdbc.update("DELETE FROM role_capabilities WHERE role_id='00000000-0000-4000-8000-000000000001' AND capability_code='student.read'");
        assertThatThrownBy(()->officialDocuments.certificateSources(f.maker(),f.school(),null,0)).hasMessageContaining("outside this membership");
        assertThatThrownBy(()->officialDocuments.review(f.maker(),f.school(),pending.id())).hasMessageContaining("outside this membership");
        assertThatThrownBy(()->officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-changed",request)).hasMessageContaining("outside this membership");
        assertThat(officialDocuments.snapshot(f.maker(),f.school()).documents()).noneMatch(d->d.documentKind().equals("BONAFIDE_CERTIFICATE"));
    }

    @Test
    @Transactional
    void appliesV056AndProtectsCertificateSourceAndNumberingWithRowSecurity() {
        var f=familyDocumentFixture(); var template=publishedBonafideTemplate(f);
        UUID enrolment=jdbc.queryForObject("SELECT id FROM student_enrolments WHERE learner_id=?",UUID.class,f.learner());
        var pending=officialDocuments.requestIssue(f.maker(),"synthetic-bonafide-guards",new IssueRequest(f.school(),template,"STUDENT_ENROLMENT",enrolment,null,"Synthetic guarded certificate"));
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='056'",Boolean.class)).isTrue();
        assertDatabaseRejected(()->jdbc.update("DELETE FROM official_certificate_counters WHERE school_id=?",f.school()),"cannot be reset");
        assertDatabaseRejected(()->jdbc.update("UPDATE official_certificate_counters SET last_number=0 WHERE school_id=?",f.school()),"advance exactly once");
        assertDatabaseRejected(()->jdbc.update("""
                INSERT INTO official_documents
                SELECT (jsonb_populate_record(NULL::official_documents,to_jsonb(d)||jsonb_build_object(
                  'id',CAST(? AS uuid),'idempotency_key','synthetic-wrong-learner','subject_id',CAST(? AS uuid)))).*
                FROM official_documents d WHERE d.id=?
                """,UUID.randomUUID(),UUID.randomUUID(),pending.id()),"matching scoped enrolment");
        assertDatabaseRejected(()->jdbc.update("UPDATE official_documents SET source_snapshot='{}'::jsonb WHERE id=?",pending.id()),"source evidence is immutable");
        assertDatabaseRejected(()->jdbc.update("""
                INSERT INTO official_document_templates
                SELECT (jsonb_populate_record(NULL::official_document_templates,to_jsonb(t)||jsonb_build_object(
                  'id',CAST(? AS uuid),'template_code','SYN_NO_APPROVAL','approval_required',false))).*
                FROM official_document_templates t WHERE t.id=?
                """,UUID.randomUUID(),template),"official_certificate_approval_required");
        jdbc.execute("CREATE ROLE educore_certificate_counter_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_certificate_counter_rls_test");
        jdbc.execute("GRANT SELECT,UPDATE ON official_certificate_counters TO educore_certificate_counter_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_certificate_counter_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM official_certificate_counters",Integer.class)).isZero();
        assertThat(jdbc.update("UPDATE official_certificate_counters SET last_number=last_number+1 WHERE school_id=?",f.school())).isZero();
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,f.maker().activeOrganisationId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM official_certificate_counters",Integer.class)).isEqualTo(1);
        jdbc.execute("RESET ROLE");
    }

    @Test
    @Transactional
    void bonafideSourceSearchIsPaginatedAndLiteral() {
        var f=familyDocumentFixture();
        UUID session=jdbc.queryForObject("SELECT academic_session_id FROM student_enrolments WHERE learner_id=?",UUID.class,f.learner());
        for(int i=0;i<27;i++) insertSyntheticFeePaymentSource("4f100000",1000+i*10,f.maker().activeOrganisationId(),f.school(),
                UUID.fromString("10000000-0000-4000-8000-000000000003"),session,f.maker().membershipIds().iterator().next());
        var first=officialDocuments.certificateSources(f.maker(),f.school(),null,0);
        var second=officialDocuments.certificateSources(f.maker(),f.school(),null,1);
        assertThat(first.sources()).hasSize(25); assertThat(first.hasMore()).isTrue();
        assertThat(second.sources()).hasSize(3); assertThat(second.hasMore()).isFalse();
        assertThat(first.sources().stream().map(s->s.id()).toList()).doesNotContainAnyElementsOf(second.sources().stream().map(s->s.id()).toList());
        assertThat(officialDocuments.certificateSources(f.maker(),f.school(),"SYN-DOC-1060",0).sources()).hasSize(1);
        assertThat(officialDocuments.certificateSources(f.maker(),f.school(),"%",0).sources()).isEmpty();
        assertThatThrownBy(()->officialDocuments.certificateSources(f.maker(),f.school(),null,-1)).hasMessageContaining("valid source page");
        assertThatThrownBy(()->officialDocuments.certificateSources(f.maker(),f.school(),"x".repeat(101),0)).hasMessageContaining("supported length");
    }

    private UUID publishedBonafideTemplate(FamilyDocumentFixture f) {
        var request=new TemplateRequest(f.school(),"SYN_BONAFIDE",1,null,"BONAFIDE_CERTIFICATE","Synthetic certificate",
                "en-IN","Bonafide certificate",null,null,"Principal","#23483F",true,java.util.Map.of(),null,"Synthetic certificate template");
        var template=officialDocuments.createTemplate(f.maker(),request);
        officialDocuments.publishTemplate(f.approver(),template.id(),new TransitionRequest(f.school(),0,"Synthetic independent certificate template approval"));
        return template.id();
    }

    private AuthenticatedUser alternateDocumentMembership(FamilyDocumentFixture f) {
        UUID alternate=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,'10000000-0000-4000-8000-000000000003','ACTIVE',now(),now(),now())
                """,alternate,f.maker().userId(),f.maker().activeOrganisationId(),f.school());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,'00000000-0000-4000-8000-000000000001')",alternate);
        // A later active assignment must not erase the historical person's authorship.
        jdbc.update("UPDATE memberships SET status='ENDED' WHERE id=?",f.maker().membershipIds().iterator().next());
        return syntheticDocumentUser(f.maker().userId(),alternate,f.maker().activeOrganisationId(),"Synthetic alternate membership");
    }

    private void assertFamilyDocumentDenied(FamilyDocumentFixture f) {
        assertThatThrownBy(()->familyDocuments.list(f.parent(),f.learner(),0)).hasMessageContaining("not available");
        assertThatThrownBy(()->familyDocuments.download(f.parent(),f.learner(),f.document())).hasMessageContaining("not available");
    }

    private FamilyDocumentFixture familyDocumentFixture() {
        UUID org=UUID.fromString("10000000-0000-4000-8000-000000000001"),school=UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID makerId=UUID.randomUUID(),makerMembership=UUID.randomUUID(),approverId=UUID.randomUUID(),approverMembership=UUID.randomUUID();
        UUID parentId=UUID.randomUUID(),membership=UUID.randomUUID(),guardian=UUID.randomUUID(),session=UUID.randomUUID();
        for(UUID id:List.of(makerId,approverId,parentId)) insertSyntheticUser(id,id+"@example.test");
        insertSyntheticMembership(makerMembership,makerId,org,school);
        insertSyntheticMembership(approverMembership,approverId,org,school);
        insertSyntheticMembership(membership,parentId,org,school);
        for(UUID id:List.of(makerMembership,approverMembership)) jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,'00000000-0000-4000-8000-000000000001')",id);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,'00000000-0000-4000-8000-000000000006')",membership);
        jdbc.update("""
                INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at,row_version)
                VALUES(?,?,?,'Synthetic family documents',DATE '2026-04-01',DATE '2027-03-31','ACTIVE',now(),now(),0)
                """,session,org,school);
        UUID payment=insertSyntheticFeePaymentSource("4f000000",30,org,school,UUID.fromString("10000000-0000-4000-8000-000000000003"),session,makerMembership);
        UUID learner=uuid("4f000000",31);
        jdbc.update("""
                INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,created_at,updated_at,row_version,user_account_id)
                VALUES(?,?,?,'Synthetic family document guardian','synthetic-family-document@example.test','ACTIVE',now(),now(),0,?)
                """,guardian,org,school,parentId);
        jdbc.update("""
                INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,
                  relationship_code,primary_contact,pickup_authorized,portal_access_status,created_at,updated_at,row_version)
                VALUES(?,?,?,?,'PARENT_GUARDIAN',true,true,'ACTIVE',now(),now(),0)
                """,learner,guardian,org,school);
        var maker=syntheticDocumentUser(makerId,makerMembership,org,"Synthetic maker");
        var approver=syntheticDocumentUser(approverId,approverMembership,org,"Synthetic approver");
        var parent=new AuthenticatedUser(parentId,UUID.randomUUID(),"synthetic-parent@example.test","synthetic-parent@example.test",
                "Synthetic guardian",true,org,Set.of(membership),Set.of("portal.guardian"),List.of());
        when(officialDocumentObjectStore.bucket()).thenReturn("synthetic-private-documents");
        var template=officialDocuments.createTemplate(maker,documentTemplate(school,"FAMILY_RECEIPT","en-IN","Synthetic family receipt"));
        officialDocuments.publishTemplate(approver,template.id(),new TransitionRequest(school,0,"Synthetic independent template approval"));
        var issue=requestReceiptDocument(maker,"synthetic-family-document",new IssueRequest(school,template.id(),"FEE_PAYMENT",payment,null,"Synthetic receipt request"));
        return new FamilyDocumentFixture(maker,approver,parent,school,learner,guardian,membership,issue.id());
    }

    private record FamilyDocumentFixture(AuthenticatedUser maker,AuthenticatedUser approver,AuthenticatedUser parent,
            UUID school,UUID learner,UUID guardian,UUID membership,UUID document) {}

    @Test
    @Transactional
    void officialDocumentsAreVersionedMakerCheckerIdempotentAndTenantScoped() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID administratorRoleId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID requesterUserId = uuid("49000000", 1);
        UUID requesterMembershipId = uuid("49000000", 2);
        UUID approverUserId = uuid("49000000", 3);
        UUID approverMembershipId = uuid("49000000", 4);
        UUID teacherUserId = uuid("49000000", 5);
        UUID teacherMembershipId = uuid("49000000", 6);
        UUID sessionId = uuid("49000000", 7);
        UUID otherOrganisationId = uuid("4a000000", 1);
        UUID otherSchoolId = uuid("4a000000", 2);
        UUID otherAdminUserId = uuid("4a000000", 3);
        UUID otherAdminMembershipId = uuid("4a000000", 4);

        insertSyntheticUser(requesterUserId, "documents-requester@example.test");
        insertSyntheticMembership(requesterMembershipId, requesterUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                requesterMembershipId, administratorRoleId);
        insertSyntheticUser(approverUserId, "documents-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, administratorRoleId);
        insertSyntheticUser(teacherUserId, "documents-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);

        jdbc.update("""
                INSERT INTO academic_sessions(
                  id,organisation_id,school_id,name,starts_on,ends_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic documents 2026-27', DATE '2026-04-01',
                  DATE '2027-03-31', 'ACTIVE', now(), now(), 0)
                """, sessionId, organisationId, schoolId);
        UUID paymentId = insertSyntheticFeePaymentSource("49000000", 30, organisationId, schoolId, campusId,
                sessionId, requesterMembershipId);
        UUID secondPaymentId = insertSyntheticFeePaymentSource("49000000", 40, organisationId, schoolId, campusId,
                sessionId, requesterMembershipId);

        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'synthetic-documents-org', 'Synthetic Documents Organisation',
                  'Synthetic Documents Organisation', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,time_zone,
                  currency_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-documents-school', 'Synthetic Documents School',
                  'Synthetic Documents School', 'Asia/Kolkata', 'INR', 'ACTIVE', now(), now(), 0)
                """, otherSchoolId, otherOrganisationId);
        insertSyntheticUser(otherAdminUserId, "documents-other-admin@example.test");
        insertSyntheticMembership(otherAdminMembershipId, otherAdminUserId,
                otherOrganisationId, otherSchoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                otherAdminMembershipId, administratorRoleId);

        AuthenticatedUser requester = syntheticDocumentUser(
                requesterUserId, requesterMembershipId, organisationId, "Synthetic requester");
        AuthenticatedUser approver = syntheticDocumentUser(
                approverUserId, approverMembershipId, organisationId, "Synthetic approver");
        AuthenticatedUser teacher = new AuthenticatedUser(
                teacherUserId, UUID.randomUUID(), "documents-teacher@example.test",
                "documents-teacher@example.test", "Synthetic teacher", true, organisationId,
                Set.of(teacherMembershipId), Set.of("academics.read"), List.of());
        AuthenticatedUser otherAdmin = syntheticDocumentUser(
                otherAdminUserId, otherAdminMembershipId, otherOrganisationId,
                "Synthetic other administrator");
        when(officialDocumentObjectStore.bucket()).thenReturn("synthetic-private-documents");

        TemplateRequest english = documentTemplate(
                schoolId, "FEE_RECEIPT_EN", "en-IN", "Official fee receipt");
        var englishTemplate = officialDocuments.createTemplate(requester, english);
        assertThatThrownBy(() -> officialDocuments.publishTemplate(requester, englishTemplate.id(),
                new TransitionRequest(schoolId, 0, "Synthetic forbidden self-publication")))
                .hasMessageContaining("different authorized person");
        var publishedEnglish = officialDocuments.publishTemplate(approver, englishTemplate.id(),
                new TransitionRequest(schoolId, 0, "Synthetic independent template publication"));

        TemplateRequest hindi = documentTemplate(
                schoolId, "FEE_RECEIPT_HI", "hi-IN", "शुल्क रसीद");
        var hindiTemplate = officialDocuments.createTemplate(requester, hindi);
        officialDocuments.publishTemplate(approver, hindiTemplate.id(),
                new TransitionRequest(schoolId, 0, "Synthetic Hindi template publication"));

        assertThatThrownBy(() -> officialDocuments.createTemplate(teacher, english))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> officialDocuments.snapshot(requester, otherSchoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> officialDocuments.snapshot(otherAdmin, schoolId))
                .hasMessageContaining("unavailable for this school");

        IssueRequest issueRequest = new IssueRequest(
                schoolId, englishTemplate.id(), "FEE_PAYMENT", paymentId, null,
                "Synthetic controlled receipt request");
        var pending = requestReceiptDocument(
                requester, "synthetic-document-retry-001", issueRequest);
        var retry = requestReceiptDocument(
                requester, "synthetic-document-retry-001", issueRequest);
        assertThat(retry).isEqualTo(pending);
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
        assertThatThrownBy(() -> requestReceiptDocument(requester,
                "synthetic-document-retry-001",
                new IssueRequest(schoolId, hindiTemplate.id(), "FEE_PAYMENT", secondPaymentId,
                        null, "Synthetic conflicting retry")))
                .hasMessageContaining("different document request");
        assertThatThrownBy(() -> decideReceiptDocument(requester, pending.id(),
                new DecisionRequest(schoolId, "APPROVE", "Synthetic forbidden self-approval", 0)))
                .hasMessageContaining("different authorized person");

        var issued = decideReceiptDocument(approver, pending.id(),
                new DecisionRequest(schoolId, "APPROVE", "Synthetic independent approval", 0));
        assertThat(issued.status()).isEqualTo("ISSUED");
        var snapshot = officialDocuments.snapshot(requester, schoolId);
        assertThat(snapshot.documents()).anySatisfy(document -> {
            assertThat(document.id()).isEqualTo(issued.id());
            assertThat(document.status()).isEqualTo("ISSUED");
            assertThat(document.pdfSha256()).matches("^[0-9a-f]{64}$");
        });
        var cancelled = officialDocuments.cancel(requester, issued.id(),
                new TransitionRequest(schoolId, issued.rowVersion(), "Synthetic receipt cancellation"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        var cancelledRetry = requestReceiptDocument(
                requester, "synthetic-document-retry-001", issueRequest);
        assertThat(cancelledRetry.id()).isEqualTo(cancelled.id());
        assertThat(cancelledRetry.status()).isEqualTo("CANCELLED");
        Integer historyCount = jdbc.queryForObject("""
                SELECT count(*) FROM official_document_history
                WHERE official_document_id=? AND event_type IN ('REQUESTED','APPROVED','CANCELLED')
                """, Integer.class, issued.id());
        assertThat(historyCount).isEqualTo(3);

        var otherTemplate = officialDocuments.createTemplate(otherAdmin,
                documentTemplate(otherSchoolId, "FEE_RECEIPT_OTHER", "en-IN", "Other receipt"));
        assertThat(otherTemplate.status()).isEqualTo("DRAFT");
        jdbc.execute("CREATE ROLE educore_official_documents_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_official_documents_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON official_document_templates, official_documents, official_document_history TO educore_official_documents_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_official_documents_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, organisationId.toString());
        Integer visibleTemplates = jdbc.queryForObject(
                "SELECT count(*) FROM official_document_templates", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE official_document_templates SET display_name='Forbidden rewrite' WHERE id=?",
                otherTemplate.id());
        assertThat(visibleTemplates).isEqualTo(2);
        assertThat(crossTenantUpdate).isZero();
        jdbc.execute("RESET ROLE");

        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE official_document_templates SET title='Rewritten published title'
                WHERE id=? AND row_version=?
                """, publishedEnglish.id(), publishedEnglish.rowVersion()),
                "published official document templates may only be retired");
    }

    @Test
    @Transactional
    void feeAgeingAndDayCloseAreMakerCheckerImmutableAndTenantScoped() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID administratorRoleId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID principalRoleId = UUID.fromString("00000000-0000-4000-8000-000000000007");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID makerUserId = uuid("4b000000", 1);
        UUID makerMembershipId = uuid("4b000000", 2);
        UUID approverUserId = uuid("4b000000", 3);
        UUID approverMembershipId = uuid("4b000000", 4);
        UUID teacherUserId = uuid("4b000000", 5);
        UUID teacherMembershipId = uuid("4b000000", 6);
        UUID sessionId = uuid("4b000000", 7);
        UUID applicationId = uuid("4b000000", 8);
        UUID learnerId = uuid("4b000000", 9);
        UUID enrolmentId = uuid("4b000000", 10);
        UUID otherSchoolId = uuid("4b000000", 11);
        // Fixture receipts must use the configured school's day, not the CI host's UTC day.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate dueOn = today.minusDays(45);

        insertSyntheticUser(makerUserId, "finance-close-maker@example.test");
        insertSyntheticMembership(makerMembershipId, makerUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                makerMembershipId, administratorRoleId);
        insertSyntheticUser(approverUserId, "finance-close-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, principalRoleId);
        insertSyntheticUser(teacherUserId, "finance-close-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,time_zone,
                  currency_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-finance-other-school', 'Synthetic Finance Other School',
                  'Synthetic Finance Other School', 'Asia/Kolkata', 'INR', 'ACTIVE',now(),now(),0)
                """, otherSchoolId, organisationId);

        jdbc.update("""
                INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,
                  status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic finance close session', ?, ?, 'ACTIVE',now(),now(),0)
                """, sessionId, organisationId, schoolId, today.minusDays(120), today.plusDays(245));
        insertApplication(applicationId, organisationId, schoolId, makerMembershipId,
                "synthetic-finance-close-application");
        jdbc.update("""
                INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,
                  birth_date,gender_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'Synthetic finance learner', DATE '2015-01-01',
                  'NOT_STATED','ACTIVE',now(),now(),0)
                """, learnerId, organisationId, schoolId, applicationId);
        jdbc.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,
                  academic_session_id,learner_id,origin_application_id,grade_code,admission_number,
                  status,placement_status,starts_on,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'VI', 'SYN-FIN-001', 'ACTIVE', 'PENDING',
                  ?,now(),now(),0)
                """, enrolmentId, organisationId, schoolId, campusId, sessionId, learnerId,
                applicationId, today.minusDays(120));

        AuthenticatedUser maker = new AuthenticatedUser(
                makerUserId, UUID.randomUUID(), "finance-close-maker@example.test",
                "finance-close-maker@example.test", "Synthetic finance maker", true,
                organisationId, Set.of(makerMembershipId), Set.of(
                        "fees.read", "fees.configure", "fees.collect", "fees.approve",
                        "fees.refund", "fees.close.propose", "fees.close.approve"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(
                approverUserId, UUID.randomUUID(), "finance-close-approver@example.test",
                "finance-close-approver@example.test", "Synthetic principal approver", true,
                organisationId, Set.of(approverMembershipId), Set.of(
                        "fees.read", "fees.approve", "fees.close.approve"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                teacherUserId, UUID.randomUUID(), "finance-close-teacher@example.test",
                "finance-close-teacher@example.test", "Synthetic teacher", true,
                organisationId, Set.of(teacherMembershipId), Set.of("academics.read"), List.of());

        var head = finance.createFeeHead(maker, new FeeHeadRequest(
                schoolId, "SYN_CLOSE", "Synthetic close tuition", null,
                "TUITION", false, "ACTIVE", null, "Synthetic fee-head setup"));
        var plan = finance.createPlan(maker, new FeePlanRequest(
                schoolId, sessionId, null, "SYN_CLOSE_PLAN", 1,
                "Synthetic finance close plan", "VI", "INR", "SYN/FC",
                "Synthetic day-close integration plan", null, "Synthetic plan setup"));
        finance.addPlanItem(maker, plan.id(), new PlanItemRequest(
                schoolId, head.id(), 1, "Synthetic overdue tuition", dueOn,
                new BigDecimal("1000.00"), true, "Synthetic scheduled charge"));
        finance.activatePlan(approver, plan.id(), new PlanActivationRequest(
                schoolId, 0, false, "Synthetic independent plan approval"));
        finance.assignPlan(maker, new PlanAssignmentRequest(
                schoolId, plan.id(), enrolmentId, "Synthetic learner assignment"));

        var initial = finance.snapshot(maker, schoolId);
        var account = initial.accounts().stream()
                .filter(item -> item.learnerId().equals(learnerId)).findFirst().orElseThrow();
        var firstPaymentRequest = new PaymentRequest(
                schoolId, account.id(), "CASH", null, new BigDecimal("200.00"),
                today, "Synthetic first collection", List.of());
        var firstPayment = inManualFinanceScope(maker, () -> finance.recordPayment(
                maker, "synthetic-close-payment-001", firstPaymentRequest));
        var afterFirstPayment = finance.snapshot(maker, schoolId);
        assertThat(afterFirstPayment.ageing().days31To60()).isEqualByComparingTo("800.00");
        assertThat(afterFirstPayment.ageing().overdueAccountCount()).isEqualTo(1);
        assertThat(afterFirstPayment.dayCloseCandidate().netCollections())
                .isEqualByComparingTo("200.00");

        DayCloseRequest firstCloseRequest = new DayCloseRequest(
                schoolId, today, closeDeclarations("200.00"),
                "Synthetic daily close submission", null);
        var firstClose = finance.proposeDayClose(maker, firstCloseRequest);
        assertThatThrownBy(() -> finance.proposeDayClose(maker, firstCloseRequest))
                .hasMessageContaining("pending or approved close");
        assertThatThrownBy(() -> finance.decideDayClose(maker, firstClose.id(),
                new DayCloseDecisionRequest(schoolId, "APPROVED",
                        "Synthetic forbidden self-approval", 0)))
                .hasMessageContaining("different authorized person");

        var secondPaymentRequest = new PaymentRequest(
                schoolId, account.id(), "CASH", null, new BigDecimal("100.00"),
                today, "Synthetic second collection", List.of());
        var secondPayment = inManualFinanceScope(maker, () -> finance.recordPayment(
                maker, "synthetic-close-payment-002", secondPaymentRequest));
        assertThatThrownBy(() -> finance.decideDayClose(approver, firstClose.id(),
                new DayCloseDecisionRequest(schoolId, "APPROVED",
                        "Synthetic stale approval attempt", 0)))
                .hasMessageContaining("changed after submission");
        finance.decideDayClose(approver, firstClose.id(), new DayCloseDecisionRequest(
                schoolId, "REJECTED", "Synthetic rejection after changed collection evidence", 0));

        var revisedClose = finance.proposeDayClose(maker, new DayCloseRequest(
                schoolId, today, closeDeclarations("300.00"),
                "Synthetic revised daily close", null));
        var approvedClose = finance.decideDayClose(approver, revisedClose.id(),
                new DayCloseDecisionRequest(schoolId, "APPROVED",
                        "Synthetic independent close approval", 0));
        assertThat(approvedClose.rowVersion()).isEqualTo(1);

        var retry = inManualFinanceScope(maker, () -> finance.recordPayment(
                maker, "synthetic-close-payment-002", secondPaymentRequest));
        assertThat(retry.id()).isEqualTo(secondPayment.id());
        assertThatThrownBy(() -> inManualFinanceScope(maker, () -> finance.recordPayment(maker, "synthetic-close-payment-003",
                new PaymentRequest(schoolId, account.id(), "CASH", null,
                        new BigDecimal("50.00"), today, "Synthetic forbidden late posting", List.of()))))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MANUAL_COLLECTION_POSTING_DATE_CLOSED"));
        assertThatThrownBy(() -> finance.reversePayment(maker, firstPayment.id(),
                new PaymentReversalRequest(schoolId, 0, "Synthetic forbidden late reversal")))
                .hasMessageContaining("locked by an approved finance close");

        var finalSnapshot = finance.snapshot(maker, schoolId);
        assertThat(finalSnapshot.ageing().days31To60()).isEqualByComparingTo("700.00");
        assertThat(finalSnapshot.dayCloses()).hasSize(2);
        assertThat(finalSnapshot.dayCloses().getFirst().status()).isEqualTo("APPROVED");
        assertThat(finalSnapshot.dayCloses().getFirst().revisionNumber()).isEqualTo(2);
        assertThat(finalSnapshot.dayCloses().get(1).status()).isEqualTo("REJECTED");
        assertThat(finalSnapshot.dayCloses().getFirst().evidenceSha256())
                .matches("^[0-9a-f]{64}$");
        assertThatThrownBy(() -> finance.snapshot(teacher, schoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> finance.snapshot(maker, otherSchoolId))
                .hasMessageContaining("unavailable for this school");

        Integer closeRoleGrantCount = jdbc.queryForObject("""
                SELECT count(*) FROM role_capabilities
                WHERE (role_id='00000000-0000-4000-8000-000000000004'
                    AND capability_code='fees.close.propose')
                   OR (role_id='00000000-0000-4000-8000-000000000007'
                    AND capability_code='fees.close.approve')
                """, Integer.class);
        assertThat(closeRoleGrantCount).isEqualTo(2);
        Integer auditCount = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND action IN ('finance.day-close.proposed','finance.day-close.decided')
                """, Integer.class, organisationId, schoolId);
        assertThat(auditCount).isEqualTo(4);

        jdbc.execute("CREATE ROLE educore_fee_day_close_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_fee_day_close_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE, DELETE ON fee_day_closes TO educore_fee_day_close_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_fee_day_close_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, organisationId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fee_day_closes", Integer.class))
                .isEqualTo(2);
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, uuid("4c000000", 1).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fee_day_closes", Integer.class))
                .isZero();
        assertThat(jdbc.update("UPDATE fee_day_closes SET decision_reason='Forbidden' WHERE id=?",
                revisedClose.id())).isZero();
        jdbc.execute("RESET ROLE");

        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE fee_day_closes SET evidence_sha256=? WHERE id=?",
                "f".repeat(64), revisedClose.id()),
                "fee day close evidence is immutable");
        assertDatabaseRejected(
                () -> jdbc.update("DELETE FROM fee_day_closes WHERE id=?", revisedClose.id()),
                "posted financial evidence is append-only");
    }

    @Test
    @Transactional
    void launchReadinessIsConfigurableMakerCheckerImmutableAndTenantScoped() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID administratorRoleId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID principalRoleId = UUID.fromString("00000000-0000-4000-8000-000000000007");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID makerUserId = uuid("4d000000", 1);
        UUID makerMembershipId = uuid("4d000000", 2);
        UUID approverUserId = uuid("4d000000", 3);
        UUID approverMembershipId = uuid("4d000000", 4);
        UUID teacherUserId = uuid("4d000000", 5);
        UUID teacherMembershipId = uuid("4d000000", 6);
        UUID otherSchoolId = uuid("4d000000", 7);

        insertSyntheticUser(makerUserId, "launch-maker@example.test");
        insertSyntheticMembership(makerMembershipId, makerUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                makerMembershipId, administratorRoleId);
        insertSyntheticUser(approverUserId, "launch-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, principalRoleId);
        insertSyntheticUser(teacherUserId, "launch-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,time_zone,
                  currency_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-launch-other-school', 'Synthetic Launch Other School',
                  'Synthetic Launch Other School', 'Asia/Kolkata', 'INR', 'ACTIVE',now(),now(),0)
                """, otherSchoolId, organisationId);

        AuthenticatedUser maker = new AuthenticatedUser(
                makerUserId, UUID.randomUUID(), "launch-maker@example.test",
                "launch-maker@example.test", "Synthetic launch maker", true,
                organisationId, Set.of(makerMembershipId),
                Set.of("launch.configure", "launch.approve"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(
                approverUserId, UUID.randomUUID(), "launch-approver@example.test",
                "launch-approver@example.test", "Synthetic launch approver", true,
                organisationId, Set.of(approverMembershipId),
                Set.of("launch.configure", "launch.approve"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                teacherUserId, UUID.randomUUID(), "launch-teacher@example.test",
                "launch-teacher@example.test", "Synthetic launch teacher", true,
                organisationId, Set.of(teacherMembershipId), Set.of("academics.read"), List.of());

        var initial = launchReadiness.snapshot(maker, schoolId);
        assertThat(initial.items()).hasSize(10).allSatisfy(item -> {
            assertThat(item.systemControl()).isTrue();
            assertThat(item.required()).isTrue();
            assertThat(item.revisionNumber()).isEqualTo(1);
            assertThat(item.status()).isEqualTo("NOT_STARTED");
        });
        assertThat(initial.launchState()).isEqualTo("NOT_READY");
        assertThat(initial.canConfigure()).isTrue();
        assertThat(initial.canApprove()).isTrue();
        assertThatThrownBy(() -> launchReadiness.snapshot(teacher, schoolId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> launchReadiness.snapshot(maker, otherSchoolId))
                .hasMessageContaining("unavailable for this school");

        CreateItemRequest custom = new CreateItemRequest(
                schoolId, "PROVIDER_READINESS", "PROVIDERS", "Provider readiness",
                "Confirm production provider ownership and failure handling.", false,
                "IN_PROGRESS", null, "Platform administrator", "Awaiting provider review",
                "Synthetic custom launch control");
        launchReadiness.createItem(maker, custom);
        assertDatabaseRejected(
                () -> launchReadiness.createItem(maker, custom),
                "same launch configuration");
        var firstBaseline = initial.items().getFirst();
        assertThatThrownBy(() -> launchReadiness.reviseItem(maker, firstBaseline.id(),
                new ReviseItemRequest(schoolId, firstBaseline.category(), firstBaseline.title(),
                        firstBaseline.guidance(), true, "READY", null,
                        "Principal", null, 1, "Synthetic evidence validation")))
                .hasMessageContaining("requires a safe evidence reference");
        assertThatThrownBy(() -> launchReadiness.reviseItem(maker, firstBaseline.id(),
                new ReviseItemRequest(schoolId, firstBaseline.category(), firstBaseline.title(),
                        firstBaseline.guidance(), false, "NOT_APPLICABLE", null,
                        "Principal", null, 1, "Synthetic forbidden baseline downgrade")))
                .hasMessageContaining("cannot be marked not applicable");

        for (var item : initial.items()) {
            launchReadiness.reviseItem(maker, item.id(), new ReviseItemRequest(
                    schoolId, item.category(), item.title(), item.guidance(), true, "READY",
                    "runbook:synthetic/" + item.controlCode().toLowerCase(),
                    "School launch authority", "Synthetic evidence reference checked", 1,
                    "Synthetic baseline readiness acceptance"));
        }
        var ready = launchReadiness.snapshot(maker, schoolId);
        assertThat(ready.launchState()).isEqualTo("READY_FOR_REVIEW");
        assertThat(ready.requiredCount()).isEqualTo(10);
        assertThat(ready.readyCount()).isEqualTo(10);
        assertThat(ready.blockedCount()).isZero();
        assertThat(ready.currentSnapshotSha256()).matches("^[0-9a-f]{64}$");

        SubmitReviewRequest firstSubmission = new SubmitReviewRequest(
                schoolId, "Synthetic authority confirms all required controls were reviewed.",
                true, "Synthetic first launch review");
        var firstReview = launchReadiness.submitReview(maker, firstSubmission);
        assertThatThrownBy(() -> launchReadiness.submitReview(maker, firstSubmission))
                .hasMessageContaining("already waiting");
        assertThatThrownBy(() -> launchReadiness.decideReview(maker, firstReview.id(),
                new ReviewDecisionRequest(schoolId, "APPROVED",
                        "Synthetic forbidden self-approval", 0)))
                .hasMessageContaining("different authorized person");

        UUID secondMakerMembership = uuid("4d000000", 8);
        // School-wide and campus-scoped memberships are distinct supported scopes;
        // retain the database's rejection of duplicate identical memberships.
        jdbc.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,?,'ACTIVE',now(),now(),now())
                """, secondMakerMembership, makerUserId, organisationId, schoolId,
                UUID.fromString("10000000-0000-4000-8000-000000000003"));
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                secondMakerMembership, principalRoleId);
        var samePerson = new AuthenticatedUser(makerUserId, UUID.randomUUID(),
                "launch-maker@example.test", "launch-maker@example.test", "Synthetic second membership", true,
                organisationId, Set.of(secondMakerMembership), Set.of("launch.configure", "launch.approve"), List.of());
        for (String decision : List.of("APPROVED", "REJECTED")) {
            assertThatThrownBy(() -> launchReadiness.decideReview(samePerson, firstReview.id(),
                    new ReviewDecisionRequest(schoolId, decision, "Synthetic second-membership self-decision", 0)))
                    .hasMessageContaining("different authorized person");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM school_launch_reviews WHERE id=?", String.class, firstReview.id()))
                .isEqualTo("PENDING");

        var changingItem = ready.items().stream()
                .filter(item -> item.systemControl() && item.revisionNumber() == 2)
                .findFirst().orElseThrow();
        var changed = launchReadiness.reviseItem(maker, changingItem.id(),
                new ReviseItemRequest(schoolId, changingItem.category(), changingItem.title(),
                        changingItem.guidance(), true, "IN_PROGRESS", null,
                        "School launch authority", "Synthetic evidence changed after submission",
                        2, "Synthetic stale-review rehearsal"));
        assertThatThrownBy(() -> launchReadiness.decideReview(approver, firstReview.id(),
                new ReviewDecisionRequest(schoolId, "APPROVED",
                        "Synthetic stale approval attempt", 0)))
                .hasMessageContaining("changed after submission");
        launchReadiness.decideReview(approver, firstReview.id(), new ReviewDecisionRequest(
                schoolId, "REJECTED", "Synthetic rejection of stale evidence", 0));

        launchReadiness.reviseItem(maker, changed.id(), new ReviseItemRequest(
                schoolId, changingItem.category(), changingItem.title(), changingItem.guidance(),
                true, "READY", "runbook:synthetic/rechecked-control",
                "School launch authority", "Synthetic evidence rechecked", 3,
                "Synthetic readiness resubmission"));
        var secondReview = launchReadiness.submitReview(maker, new SubmitReviewRequest(
                schoolId, "Synthetic revised evidence is complete and independently reviewable.",
                true, "Synthetic second launch review"));
        launchReadiness.decideReview(approver, secondReview.id(), new ReviewDecisionRequest(
                schoolId, "APPROVED", "Synthetic independent go-live approval", 0));
        var approved = launchReadiness.snapshot(maker, schoolId);
        assertThat(approved.launchState()).isEqualTo("GO_LIVE_APPROVED");
        assertThat(approved.reviews()).hasSize(2);
        assertThat(approved.reviews().getFirst().status()).isEqualTo("APPROVED");
        assertThat(approved.reviews().getFirst().matchesCurrentSnapshot()).isTrue();
        assertThat(approved.reviews().get(1).status()).isEqualTo("REJECTED");

        var anotherItem = approved.items().stream()
                .filter(item -> item.systemControl()
                        && !item.controlCode().equals(changingItem.controlCode()))
                .findFirst().orElseThrow();
        launchReadiness.reviseItem(maker, anotherItem.id(), new ReviseItemRequest(
                schoolId, anotherItem.category(), anotherItem.title(), anotherItem.guidance(),
                true, "IN_PROGRESS", null, "School launch authority",
                "Synthetic post-approval configuration change", anotherItem.revisionNumber(),
                "Synthetic stale-approval proof"));
        assertThat(launchReadiness.snapshot(maker, schoolId).launchState())
                .isEqualTo("APPROVAL_STALE");

        Integer roleGrantCount = jdbc.queryForObject("""
                SELECT count(*) FROM role_capabilities
                WHERE role_id IN ('00000000-0000-4000-8000-000000000001',
                    '00000000-0000-4000-8000-000000000007')
                  AND capability_code IN ('launch.configure','launch.approve')
                """, Integer.class);
        assertThat(roleGrantCount).isEqualTo(4);
        Integer auditCount = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE organisation_id=? AND school_id=? AND action IN (
                  'launch.readiness.item.created','launch.readiness.item.revised',
                  'launch.review.submitted','launch.review.decided')
                """, Integer.class, organisationId, schoolId);
        assertThat(auditCount).isEqualTo(18);

        jdbc.execute("CREATE ROLE educore_launch_readiness_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_launch_readiness_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE, DELETE ON school_launch_readiness_items, school_launch_reviews TO educore_launch_readiness_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_launch_readiness_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, organisationId.toString());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM school_launch_readiness_items WHERE school_id=?
                """, Integer.class, schoolId)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_launch_reviews",
                Integer.class)).isEqualTo(2);
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, uuid("4e000000", 1).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_launch_readiness_items",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_launch_reviews",
                Integer.class)).isZero();
        assertThat(jdbc.update("""
                UPDATE school_launch_reviews SET decision_reason='Forbidden' WHERE id=?
                """, secondReview.id())).isZero();
        jdbc.execute("RESET ROLE");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE school_launch_readiness_items SET title='Forbidden rewrite' WHERE id=?
                """, anotherItem.id()))
                .hasMessageContaining("launch readiness revisions are append-only");
    }

    @Test
    @Transactional
    void schoolCalendarGovernsClosuresApprovalsDueNotificationsAndTenantEvidence() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID administratorRoleId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID principalRoleId = UUID.fromString("00000000-0000-4000-8000-000000000007");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID sessionId = uuid("4f000000", 1);
        UUID makerUserId = uuid("4f000000", 2);
        UUID makerMembershipId = uuid("4f000000", 3);
        UUID approverUserId = uuid("4f000000", 4);
        UUID approverMembershipId = uuid("4f000000", 5);
        UUID teacherUserId = uuid("4f000000", 6);
        UUID teacherMembershipId = uuid("4f000000", 7);
        UUID otherSchoolId = uuid("4f000000", 8);
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(zone);
        LocalDate closureDate = today.plusDays(1);

        jdbc.update("""
                INSERT INTO academic_sessions(
                  id,organisation_id,school_id,name,starts_on,ends_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, 'PLANNED', now(), now(), 0)
                """, sessionId, organisationId, schoolId,
                "Synthetic calendar " + sessionId.toString().substring(0, 8),
                today.minusMonths(1), today.plusMonths(10));
        insertSyntheticUser(makerUserId, "calendar-maker@example.test");
        insertSyntheticMembership(makerMembershipId, makerUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                makerMembershipId, administratorRoleId);
        insertSyntheticUser(approverUserId, "calendar-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, principalRoleId);
        insertSyntheticUser(teacherUserId, "calendar-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,time_zone,
                  currency_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-calendar-other-school', 'Synthetic Calendar Other School',
                  'Synthetic Calendar Other School', 'Asia/Kolkata', 'INR', 'ACTIVE',now(),now(),0)
                """, otherSchoolId, organisationId);

        AuthenticatedUser maker = new AuthenticatedUser(
                makerUserId, UUID.randomUUID(), "calendar-maker@example.test",
                "calendar-maker@example.test", "Synthetic calendar maker", true,
                organisationId, Set.of(makerMembershipId),
                Set.of("calendar.manage", "calendar.approve"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(
                approverUserId, UUID.randomUUID(), "calendar-approver@example.test",
                "calendar-approver@example.test", "Synthetic calendar approver", true,
                organisationId, Set.of(approverMembershipId),
                Set.of("calendar.manage", "calendar.approve"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                teacherUserId, UUID.randomUUID(), "calendar-teacher@example.test",
                "calendar-teacher@example.test", "Synthetic calendar teacher", true,
                organisationId, Set.of(teacherMembershipId), Set.of("academics.read"), List.of());

        assertThatThrownBy(() -> schoolCalendars.snapshot(teacher, schoolId, campusId, sessionId))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> schoolCalendars.snapshot(maker, otherSchoolId, null, null))
                .hasMessageContaining("unavailable");

        CreatePolicyRequest policyRequest = new CreatePolicyRequest(
                schoolId, campusId, sessionId, LocalTime.of(8, 0), 60,
                LocalTime.of(18, 0), "Asia/Kolkata", true, true, true, true,
                "Synthetic leadership-approved calendar policy");
        var policy = schoolCalendars.createPolicy(maker, policyRequest);
        assertThat(policy.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> schoolCalendars.createPolicy(maker, policyRequest))
                .hasMessageContaining("already has a notification policy");
        assertThatThrownBy(() -> schoolCalendars.createNotice(maker, new CreateNoticeRequest(
                schoolId, campusId, sessionId, closureDate, "HOLIDAY", "GOVERNMENT_ORDER",
                "Invalid synthetic type", "अमान्य परीक्षण प्रकार", "Invalid synthetic message.",
                "अमान्य परीक्षण संदेश।", "Synthetic invalid order", true,
                "Synthetic invalid type rehearsal")))
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> schoolCalendars.createNotice(maker, new CreateNoticeRequest(
                schoolId, campusId, sessionId, today.minusDays(1), "CLOSURE",
                "EMERGENCY_CLOSURE", "Past synthetic closure", "पिछला परीक्षण अवकाश",
                "Past dates cannot change.", "पिछली तिथि नहीं बदल सकती।", null, true,
                "Synthetic past-date rehearsal")))
                .hasMessageContaining("Past school dates");

        CreateNoticeRequest closureRequest = new CreateNoticeRequest(
                schoolId, campusId, sessionId, closureDate, "CLOSURE", "GOVERNMENT_ORDER",
                "School closed by government order", "सरकारी आदेश से विद्यालय बंद",
                "The school will remain closed tomorrow. Please check the verified inbox for updates.",
                "विद्यालय कल बंद रहेगा। कृपया आगे की सूचना के लिए सत्यापित इनबॉक्स देखें।",
                "Synthetic government order GOV-001", true,
                "Synthetic emergency calendar record");
        var closure = schoolCalendars.createNotice(maker, closureRequest);
        assertThat(closure.status()).isEqualTo("DRAFT");
        assertDatabaseRejected(
                () -> schoolCalendars.createNotice(maker, closureRequest),
                "same calendar policy, date, revision, review, or notification");

        var review = schoolCalendars.submitReview(maker, closure.noticeSeriesId(),
                new com.educore.api.schoolcalendar.SchoolCalendarController.SubmitReviewRequest(
                        schoolId, 1, true, "Synthetic date and bilingual evidence reviewed"));
        assertThat(review.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> schoolCalendars.submitReview(maker, closure.noticeSeriesId(),
                new com.educore.api.schoolcalendar.SchoolCalendarController.SubmitReviewRequest(
                        schoolId, 1, true, "Synthetic duplicate review")))
                .hasMessageContaining("already");
        assertThatThrownBy(() -> schoolCalendars.decideReview(maker, review.id(),
                new com.educore.api.schoolcalendar.SchoolCalendarController.ReviewDecisionRequest(
                        schoolId, "APPROVED", 0, "Synthetic forbidden self approval")))
                .hasMessageContaining("different authorized person");

        String approvalReason = "Synthetic independent government-closure approval";
        var approval = schoolCalendars.decideReview(approver, review.id(),
                new com.educore.api.schoolcalendar.SchoolCalendarController.ReviewDecisionRequest(
                        schoolId, "APPROVED", 0, approvalReason));
        assertThat(approval.status()).isEqualTo("APPROVED");
        assertThat(approval.notificationScheduledFor()).isNotNull();
        var idempotentApproval = schoolCalendars.decideReview(approver, review.id(),
                new com.educore.api.schoolcalendar.SchoolCalendarController.ReviewDecisionRequest(
                        schoolId, "APPROVED", 0, approvalReason));
        assertThat(idempotentApproval).isEqualTo(approval);

        // A later policy revision must not rewrite an approved notice's frozen rules.
        RevisePolicyRequest policyRevisionRequest = new RevisePolicyRequest(
                schoolId, LocalTime.of(9, 0), 30, LocalTime.of(17, 0), "Asia/Kolkata",
                false, false, false, 1, true, "Synthetic new policy for later notices only");
        assertThatThrownBy(() -> schoolCalendars.revisePolicy(teacher, policy.id(), policyRevisionRequest))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> schoolCalendars.revisePolicy(maker, policy.id(), new RevisePolicyRequest(
                otherSchoolId, LocalTime.of(9, 0), 30, LocalTime.of(17, 0), "Asia/Kolkata",
                false, false, false, 1, true, "Synthetic forbidden cross-school policy revision")))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> schoolCalendars.revisePolicy(maker, policy.id(), new RevisePolicyRequest(
                schoolId, LocalTime.of(9, 0), 30, LocalTime.of(17, 0), "Asia/Kolkata",
                false, false, false, 2, true, "Synthetic stale expected revision")))
                .hasMessageContaining("changed");
        var revisedPolicy = schoolCalendars.revisePolicy(maker, policy.id(), policyRevisionRequest);
        assertThat(revisedPolicy.revisionNumber()).isEqualTo(2);
        assertThat(revisedPolicy.id()).isNotEqualTo(policy.id());
        assertThatThrownBy(() -> schoolCalendars.revisePolicy(maker, policy.id(), policyRevisionRequest))
                .hasMessageContaining("newer revision");
        var currentPolicy = schoolCalendars.snapshot(maker, schoolId, campusId, sessionId).policy();
        assertThat(currentPolicy.id()).isEqualTo(revisedPolicy.id());
        assertThat(currentPolicy.schoolStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(currentPolicy.sameDayCutoffTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(currentPolicy.studentInAppEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT policy_revision_id FROM school_calendar_notice_reviews WHERE id=?",
                UUID.class, review.id())).isEqualTo(policy.id());
        assertThat(jdbc.queryForObject("SELECT school_start_time FROM school_calendar_policy_revisions WHERE id=?",
                LocalTime.class, policy.id())).isEqualTo(LocalTime.of(8, 0));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND action='school.calendar.policy.revised'
                """, Integer.class, organisationId, schoolId)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT day_type FROM school_calendar_days
                WHERE organisation_id=? AND school_id=? AND campus_id=?
                  AND academic_session_id=? AND calendar_date=?
                """, String.class, organisationId, schoolId, campusId, sessionId, closureDate))
                .isEqualTo("CLOSURE");
        assertThat(jdbc.queryForObject("""
                SELECT authority_notice_revision_id FROM school_calendar_days
                WHERE organisation_id=? AND school_id=? AND campus_id=?
                  AND academic_session_id=? AND calendar_date=?
                """, UUID.class, organisationId, schoolId, campusId, sessionId, closureDate))
                .isEqualTo(closure.id());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_notification_jobs
                WHERE organisation_id=? AND school_id=? AND notice_revision_id=?
                  AND status='PENDING'
                """, Integer.class, organisationId, schoolId, closure.id())).isEqualTo(2);

        PortalFixture portalFixture = academyPortalFixture("9f000000");
        insertPortalFixture(portalFixture, "calendar-due-time-recipient");
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                portalFixture.guardianMembershipId(),
                UUID.fromString("00000000-0000-4000-8000-000000000006"));
        UUID enrolmentId = uuid("9f000000", 12);
        jdbc.update("""
                INSERT INTO student_enrolments(
                  id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                  origin_application_id,grade_code,admission_number,status,placement_status,
                  starts_on,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'VI', 'SYN-CAL-001', 'ACTIVE', 'PENDING',
                  ?, now(), now(), 0)
                """, enrolmentId, organisationId, schoolId, campusId, sessionId,
                portalFixture.learnerId(), portalFixture.applicationId(), today.minusMonths(1));
        UUID studentUserId = uuid("9f000000", 8);
        UUID studentMembershipId = uuid("9f000000", 9);
        UUID consentId = uuid("9f000000", 10);
        UUID portalAccountId = uuid("9f000000", 11);
        insertSyntheticLoginUser(studentUserId, "student.calendar-recipient", null);
        insertSyntheticMembership(studentMembershipId, studentUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                studentMembershipId,
                UUID.fromString("00000000-0000-4000-8000-000000000010"));
        insertPendingPortalConsent(portalFixture, consentId, "student.calendar-recipient");
        approvePortalConsent(portalFixture, consentId, studentUserId,
                portalFixture.guardianUserId(), portalFixture.guardianMembershipId());
        insertPortalAccount(portalFixture, portalAccountId, consentId, studentUserId);

        AuthenticatedUser guardian = new AuthenticatedUser(
                portalFixture.guardianUserId(), UUID.randomUUID(),
                "calendar-due-time-recipient-guardian@example.test",
                "calendar-due-time-recipient-guardian@example.test",
                "Synthetic calendar guardian", true, organisationId,
                Set.of(portalFixture.guardianMembershipId()), Set.of("portal.guardian"), List.of());
        AuthenticatedUser student = new AuthenticatedUser(
                studentUserId, UUID.randomUUID(), "student.calendar-recipient", null,
                "Synthetic calendar student", true, organisationId,
                Set.of(studentMembershipId), Set.of("portal.student"), List.of());
        var guardianCalendar = family.calendar(guardian, portalFixture.learnerId());
        var studentCalendar = studentPortal.calendar(student);
        assertThat(guardianCalendar.days()).singleElement().satisfies(day -> {
            assertThat(day.noticeRevisionId()).isEqualTo(closure.id());
            assertThat(day.noticeKind()).isEqualTo("GOVERNMENT_ORDER");
            assertThat(day.attendanceRequired()).isFalse();
        });
        assertThat(studentCalendar).isEqualTo(guardianCalendar);
        assertThatThrownBy(() -> family.calendar(guardian, UUID.randomUUID()))
                .hasMessageContaining("verified relationship");

        assertThat(schoolCalendarNotifications.processNext(organisationId)).isNotNull();
        assertThat(schoolCalendarNotifications.processNext(organisationId)).isNotNull();
        assertThat(schoolCalendarNotifications.processNext(organisationId)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_notification_jobs
                WHERE organisation_id=? AND school_id=? AND notice_revision_id=?
                  AND status='MATERIALIZED' AND recipient_count=1
                """, Integer.class, organisationId, schoolId, closure.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_announcements
                WHERE organisation_id=? AND school_id=?
                  AND source_type='SCHOOL_CALENDAR_NOTICE' AND source_id=?
                  AND status='APPROVED'
                """, Integer.class, organisationId, schoolId, closure.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_announcement_history history
                JOIN communication_announcements announcement
                  ON announcement.id=history.communication_announcement_id
                WHERE announcement.organisation_id=? AND announcement.school_id=?
                  AND announcement.source_type='SCHOOL_CALENDAR_NOTICE'
                  AND announcement.source_id=?
                  AND history.event_type IN ('RECIPIENTS_FROZEN','APPROVED')
                """, Integer.class, organisationId, schoolId, closure.id())).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_recipients recipient
                JOIN communication_announcements announcement
                  ON announcement.id=recipient.communication_announcement_id
                WHERE announcement.organisation_id=? AND announcement.school_id=?
                  AND announcement.source_type='SCHOOL_CALENDAR_NOTICE'
                  AND announcement.source_id=?
                """, Integer.class, organisationId, schoolId, closure.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_deliveries delivery
                JOIN communication_recipients recipient
                  ON recipient.id=delivery.communication_recipient_id
                JOIN communication_announcements announcement
                  ON announcement.id=recipient.communication_announcement_id
                WHERE announcement.source_type='SCHOOL_CALENDAR_NOTICE'
                  AND announcement.source_id=? AND delivery.channel='IN_APP'
                  AND delivery.status='READY'
                """, Integer.class, closure.id())).isEqualTo(2);

        var corrected = schoolCalendars.reviseNotice(maker, closure.noticeSeriesId(),
                new ReviseNoticeRequest(
                        schoolId, "INSTRUCTIONAL", "REOPENING", "School will reopen",
                        "विद्यालय पुनः खुलेगा", "Classes will resume on the published date.",
                        "प्रकाशित तिथि पर कक्षाएँ पुनः आरंभ होंगी।",
                        "Synthetic revised government order GOV-002", 1, true,
                        "Synthetic reopening correction after updated order"));
        assertThat(corrected.revisionNumber()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT day_type FROM school_calendar_days
                WHERE organisation_id=? AND school_id=? AND campus_id=?
                  AND academic_session_id=? AND calendar_date=?
                """, String.class, organisationId, schoolId, campusId, sessionId, closureDate))
                .isEqualTo("CLOSURE");
        var snapshot = schoolCalendars.snapshot(maker, schoolId, campusId, sessionId);
        assertThat(snapshot.notices()).hasSize(1);
        assertThat(snapshot.notices().getFirst().revisionNumber()).isEqualTo(2);
        assertThat(snapshot.notices().getFirst().reviewStatus()).isNull();
        assertThat(snapshot.notices().getFirst().notificationJobs()).isEmpty();
        assertThat(family.calendar(guardian, portalFixture.learnerId()).days())
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.noticeRevisionId()).isEqualTo(closure.id());
                    assertThat(day.noticeKind()).isEqualTo("GOVERNMENT_ORDER");
                });

        Integer roleGrantCount = jdbc.queryForObject("""
                SELECT count(*) FROM role_capabilities
                WHERE role_id IN ('00000000-0000-4000-8000-000000000001',
                    '00000000-0000-4000-8000-000000000007',
                    '00000000-0000-4000-8000-000000000008')
                  AND capability_code IN ('calendar.manage','calendar.approve')
                """, Integer.class);
        assertThat(roleGrantCount).isEqualTo(5);
        Integer auditCount = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE organisation_id=? AND school_id=? AND action IN (
                  'school.calendar.policy.created','school.calendar.notice.created',
                  'school.calendar.notice.submitted','school.calendar.notice.approved',
                  'school.calendar.notice.revised')
                """, Integer.class, organisationId, schoolId);
        assertThat(auditCount).isEqualTo(5);

        jdbc.execute("CREATE ROLE educore_school_calendar_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_school_calendar_rls_test");
        jdbc.execute("""
                GRANT SELECT, UPDATE, DELETE ON
                  school_calendar_policy_revisions,school_calendar_notice_series,
                  school_calendar_notice_revisions,school_calendar_notice_reviews,
                  school_calendar_notification_jobs,school_calendar_days
                TO educore_school_calendar_rls_test
                """);
        jdbc.execute("SET LOCAL ROLE educore_school_calendar_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, organisationId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_calendar_policy_revisions",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_calendar_notice_revisions",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_calendar_notice_reviews",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_calendar_notification_jobs",
                Integer.class)).isEqualTo(2);
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, uuid("4f000000", 99).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_calendar_policy_revisions",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school_calendar_notice_revisions",
                Integer.class)).isZero();
        assertThat(jdbc.update("""
                UPDATE school_calendar_notice_revisions SET title_en='Forbidden'
                WHERE id=?
                """, corrected.id())).isZero();
        jdbc.execute("RESET ROLE");

        jdbc.queryForObject("SELECT set_config('app.school_calendar_sync','',true)",
                String.class);
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE school_calendar_notice_revisions SET title_en='Forbidden rewrite'
                WHERE id=?
                """, corrected.id()),
                "school calendar revisions are append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                DELETE FROM school_calendar_days WHERE authority_notice_revision_id=?
                """, closure.id()),
                "cannot be deleted");
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO school_calendar_notice_reviews(
                  id,organisation_id,school_id,notice_series_id,notice_revision_id,
                  policy_revision_id,status,submission_reason,submitted_by_membership_id,
                  submitted_at,decided_by_membership_id,decided_at,decision_reason,row_version)
                VALUES (?, ?, ?, ?, ?, ?, 'APPROVED','Synthetic forged review',?,
                  now(),?,now(),'Synthetic forged decision',0)
                """, uuid("4f000000", 50), organisationId, schoolId,
                closure.noticeSeriesId(), corrected.id(), policy.id(),
                makerMembershipId, approverMembershipId),
                "must enter through the pending workflow");
        try(var ignored=TenantRequestContext.open(null,organisationId)) {
            assertThat(publishedSchoolCalendars.isCurrentNotificationRevision(organisationId,schoolId,closure.id())).isTrue();
            assertThat(publishedSchoolCalendars.isCurrentNotificationRevision(organisationId,schoolId,corrected.id())).isFalse();
            assertThat(publishedSchoolCalendars.isCurrentNotificationRevision(organisationId,otherSchoolId,closure.id())).isFalse();
            var correctedReview=schoolCalendars.submitReview(maker,closure.noticeSeriesId(),
                    new com.educore.api.schoolcalendar.SchoolCalendarController.SubmitReviewRequest(
                            schoolId,2,true,"Synthetic reopening independently reviewed"));
            schoolCalendars.decideReview(approver,correctedReview.id(),
                    new com.educore.api.schoolcalendar.SchoolCalendarController.ReviewDecisionRequest(
                            schoolId,"APPROVED",0,"Synthetic independent reopening approval"));
            assertThat(publishedSchoolCalendars.isCurrentNotificationRevision(organisationId,schoolId,closure.id())).isFalse();
            assertThat(publishedSchoolCalendars.isCurrentNotificationRevision(organisationId,schoolId,corrected.id())).isTrue();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(() -> publishedSchoolCalendars.isCurrentNotificationRevision(organisationId,schoolId,corrected.id()))
                        .hasMessageContaining("organisation context");
            }
        }
    }

    @Test
    @Transactional
    void encryptedPushSubscriptionsAreScopedRetrySafeAndRevocable() {
        assertThat(Arrays.stream(flyway.info().applied())
                .filter(migration -> "V026__encrypted_web_push_subscriptions.sql"
                        .equals(migration.getScript()))
                .map(migration -> migration.getScript()).toList())
                .containsExactly("V026__encrypted_web_push_subscriptions.sql");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'communication_push_subscriptions','communication_push_subscription_events')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace
                  AND relname IN ('communication_push_subscriptions',
                    'communication_push_subscription_events') AND relrowsecurity
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public'
                  AND tablename IN ('communication_push_subscriptions',
                    'communication_push_subscription_events')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'communication_push_subscriptions_guard',
                  'communication_push_subscription_events_append_only')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname IN (
                  'communication_push_subscriptions_actor_fk',
                  'communication_push_subscription_events_actor_fk')
                  AND (
                    (conname='communication_push_subscriptions_actor_fk'
                      AND pg_get_constraintdef(oid) LIKE '%user_account_id%')
                    OR (conname='communication_push_subscription_events_actor_fk'
                      AND pg_get_constraintdef(oid) LIKE '%actor_user_id%')
                  )
                """, Integer.class)).isEqualTo(2);

        PortalFixture fixture = academyPortalFixture("9e000000");
        insertPortalFixture(fixture, "push-subscription");
        UUID parentRole = UUID.fromString("00000000-0000-4000-8000-000000000006");
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                fixture.guardianMembershipId(), parentRole);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                fixture.staffMembershipId(), parentRole);
        AuthenticatedUser guardian = new AuthenticatedUser(
                fixture.guardianUserId(), UUID.randomUUID(),
                "push-subscription-guardian@example.test",
                "push-subscription-guardian@example.test", "Synthetic push guardian", true,
                fixture.organisationId(), Set.of(fixture.guardianMembershipId()),
                Set.of("portal.guardian"), List.of());
        String endpoint = "https://push.example.org/subscriptions/synthetic-capability";
        var initial = pushNotifications.status(guardian, fixture.schoolId());
        assertThat(initial.registrationEnabled()).isTrue();
        assertThat(initial.allowPush()).isFalse();
        assertThat(initial.activeDeviceCount()).isZero();
        assertThatThrownBy(() -> pushNotifications.register(guardian,
                new RegisterPushSubscriptionRequest(
                        fixture.schoolId(), "https://127.0.0.1/push",
                        syntheticP256dh(), syntheticAuth(), "Unsafe endpoint")))
                .hasMessageContaining("invalid secure push endpoint");

        var registration = new RegisterPushSubscriptionRequest(
                fixture.schoolId(), endpoint, syntheticP256dh(), syntheticAuth(), "Family phone");
        var registered = pushNotifications.register(guardian, registration);
        assertThat(registered.allowPush()).isTrue();
        assertThat(registered.activeDeviceCount()).isEqualTo(1);
        UUID subscriptionId = registered.devices().getFirst().id();
        StoredPushEvidence storedPush = jdbc.queryForObject("""
                SELECT endpoint_ciphertext<>?,endpoint_hash<>?,status,row_version
                FROM communication_push_subscriptions WHERE id=?
                """, (result, row) -> new StoredPushEvidence(
                        result.getBoolean(1), result.getBoolean(2), result.getString(3),
                        result.getLong(4)), endpoint, endpoint, subscriptionId);
        assertThat(storedPush.endpointProtected()).isTrue();
        assertThat(storedPush.hashDoesNotExposeEndpoint()).isTrue();
        assertThat(storedPush.status()).isEqualTo("ACTIVE");
        assertThat(storedPush.version()).isZero();

        var retried = pushNotifications.register(guardian, registration);
        assertThat(retried.activeDeviceCount()).isEqualTo(1);
        assertThat(retried.devices().getFirst().id()).isEqualTo(subscriptionId);
        assertThat(jdbc.queryForObject(
                "SELECT row_version FROM communication_push_subscriptions WHERE id=?",
                Long.class, subscriptionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_push_subscription_events
                WHERE communication_push_subscription_id=? AND event_type IN ('REGISTERED','REFRESHED')
                """, Integer.class, subscriptionId)).isEqualTo(2);

        AuthenticatedUser otherAccount = new AuthenticatedUser(
                fixture.staffUserId(), UUID.randomUUID(), "push-other@example.test",
                "push-other@example.test", "Synthetic second account", true,
                fixture.organisationId(), Set.of(fixture.staffMembershipId()),
                Set.of("portal.guardian"), List.of());
        assertThatThrownBy(() -> pushNotifications.register(otherAccount, registration))
                .hasMessageContaining("another account");
        AuthenticatedUser forgedTenant = new AuthenticatedUser(
                fixture.guardianUserId(), UUID.randomUUID(), "push-forged@example.test",
                "push-forged@example.test", "Synthetic forged tenant", true,
                UUID.randomUUID(), Set.of(fixture.guardianMembershipId()),
                Set.of("portal.guardian"), List.of());
        assertThatThrownBy(() -> pushNotifications.status(forgedTenant, fixture.schoolId()))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> pushNotifications.status(guardian, UUID.randomUUID()))
                .hasMessageContaining("unavailable");

        var revoked = pushNotifications.revoke(guardian,
                new RevokePushSubscriptionRequest(fixture.schoolId(), endpoint));
        assertThat(revoked.allowPush()).isFalse();
        assertThat(revoked.activeDeviceCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status='REVOKED' AND endpoint_ciphertext IS NULL
                  AND p256dh_ciphertext IS NULL AND auth_ciphertext IS NULL
                FROM communication_push_subscriptions WHERE id=?
                """, Boolean.class, subscriptionId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_consent_events
                WHERE organisation_id=? AND school_id=? AND user_account_id=?
                  AND channel='PUSH' AND purpose_code='SCHOOL_OPERATIONS'
                  AND action IN ('GRANTED','REVOKED')
                """, Integer.class, fixture.organisationId(), fixture.schoolId(),
                fixture.guardianUserId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND subject_id=? AND action IN (
                    'communication.push.subscription.registered',
                    'communication.push.subscription.refreshed',
                    'communication.push.subscription.revoked')
                """, Integer.class, fixture.organisationId(), fixture.schoolId(),
                subscriptionId.toString())).isEqualTo(3);

        var replayedRevocation = pushNotifications.revoke(guardian,
                new RevokePushSubscriptionRequest(fixture.schoolId(), endpoint));
        assertThat(replayedRevocation.activeDeviceCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM communication_push_subscription_events
                WHERE communication_push_subscription_id=?
                """, Integer.class, subscriptionId)).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM communication_push_subscriptions WHERE id=?", subscriptionId))
                .hasMessageContaining("push subscription evidence cannot be deleted");
    }

    @Test
    @Transactional
    void publishedCurriculumItemsCannotBeRewritten() {
        CurriculumFixture fixture = academyCurriculumFixture("41000000");
        insertCurriculumFixture(fixture, "curriculum-item-immutable");
        publishCurriculumFixture(fixture);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE curriculum_publication_items SET title_snapshot='Rewritten' WHERE id=?",
                fixture.itemId()))
                .hasMessageContaining("Curriculum publication items are immutable");
    }

    @Test
    @Transactional
    void publishedCurriculumPublicationIdentityCannotBeRewritten() {
        CurriculumFixture fixture = academyCurriculumFixture("42000000");
        insertCurriculumFixture(fixture, "curriculum-publication-immutable");
        publishCurriculumFixture(fixture);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE curriculum_publications SET grade_code='VII' WHERE id=?",
                fixture.publicationId()))
                .hasMessageContaining("Curriculum publication identity and evidence are immutable");
    }

    @Test
    @Transactional
    void curriculumAllowsOnlyOneDraftPerSessionAndGrade() {
        CurriculumFixture fixture = academyCurriculumFixture("43000000");
        insertCurriculumFixture(fixture, "curriculum-duplicate-draft");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO curriculum_publications(
                  id,organisation_id,school_id,academic_session_id,grade_code,
                  publication_version,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'VI', 2, 'DRAFT', ?, now(), now(), 0)
                """, uuid("43000000", 9), fixture.organisationId(), fixture.schoolId(),
                fixture.sessionId(), fixture.membershipId()))
                .hasMessageContaining("curriculum_publications_one_draft_idx");
    }

    @Test
    @Transactional
    void curriculumSnapshotRejectsAnotherSchoolsSourceResource() {
        CurriculumFixture academy = academyCurriculumFixture("44000000");
        insertCurriculumFixture(academy, "curriculum-academy-source");
        UUID otherSchool = uuid("44000000", 20);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'curriculum-other-school', 'Synthetic curriculum other school',
                  'Synthetic curriculum other school', now(), now(), 0)
                """, otherSchool, academy.organisationId());
        CurriculumFixture other = new CurriculumFixture(
                academy.organisationId(), otherSchool, uuid("44000000", 21), uuid("44000000", 22),
                uuid("44000000", 23), uuid("44000000", 24), uuid("44000000", 25),
                uuid("44000000", 26), uuid("44000000", 27));
        insertCurriculumFixture(other, "curriculum-other-source");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO curriculum_publication_items(
                  id,organisation_id,school_id,curriculum_publication_id,source_resource_id,
                  subject_code_snapshot,subject_name_snapshot,resource_type_snapshot,
                  title_snapshot,display_order_snapshot,created_at)
                VALUES (?, ?, ?, ?, ?, 'SYN', 'Synthetic subject', 'TEXTBOOK',
                  'Cross-school source', 1, now())
                """, uuid("44000000", 28), academy.organisationId(), academy.schoolId(),
                academy.publicationId(), other.resourceId()))
                .hasMessageContaining("curriculum_publication_items_resource_fk");
    }

    @Test
    @Transactional
    void curriculumPublicationsRemainOrganisationIsolatedUnderRowSecurity() {
        CurriculumFixture academy = academyCurriculumFixture("45000000");
        insertCurriculumFixture(academy, "curriculum-rls-academy");
        UUID otherOrganisation = uuid("45000000", 20);
        UUID otherSchool = uuid("45000000", 21);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'curriculum-isolation-org', 'Synthetic curriculum isolation org',
                  'Synthetic curriculum isolation org', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'curriculum-isolation-school', 'Synthetic curriculum isolation school',
                  'Synthetic curriculum isolation school', now(), now(), 0)
                """, otherSchool, otherOrganisation);
        CurriculumFixture other = new CurriculumFixture(
                otherOrganisation, otherSchool, uuid("45000000", 22), uuid("45000000", 23),
                uuid("45000000", 24), uuid("45000000", 25), uuid("45000000", 26),
                uuid("45000000", 27), uuid("45000000", 28));
        insertCurriculumFixture(other, "curriculum-rls-other");

        jdbc.execute("CREATE ROLE educore_curriculum_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_curriculum_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON curriculum_publications TO educore_curriculum_rls_test");
        jdbc.execute("GRANT SELECT ON curriculum_publication_items TO educore_curriculum_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_curriculum_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academy.organisationId().toString());

        Integer visiblePublications = jdbc.queryForObject(
                "SELECT count(*) FROM curriculum_publications", Integer.class);
        Integer visibleItems = jdbc.queryForObject(
                "SELECT count(*) FROM curriculum_publication_items", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE curriculum_publications SET updated_at=now() WHERE id=?", other.publicationId());

        assertThat(visiblePublications).isEqualTo(1);
        assertThat(visibleItems).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
    }

    @Test
    @Transactional
    void studentPortalConsentMustBeginPending() {
        PortalFixture fixture = academyPortalFixture("36000000");
        insertPortalFixture(fixture, "portal-terminal-insert");
        UUID studentUser = uuid("36000000", 9);
        insertSyntheticLoginUser(studentUser, "student.terminal", null);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO student_portal_consent_requests(
                  id,organisation_id,school_id,learner_id,guardian_id,
                  proposed_login_identifier_normalized,privacy_notice_version,purpose_summary,
                  adult_verification_method,adult_verification_reference,adult_verified_at,
                  adult_verified_by_membership_id,preparation_reason,status,expires_at,
                  created_by_membership_id,created_at,guardian_decided_by_user_id,
                  guardian_decided_by_membership_id,guardian_decided_at,
                  guardian_decision_reason,approved_user_account_id,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'student.terminal', 'child-account-v1',
                  'Synthetic own-record student portal access for integration verification.',
                  'SCHOOL_RECORD','school-record:terminal-001',now(),?,
                  'Synthetic preparation evidence','APPROVED',now()+interval '14 days',?,now(),
                  ?,?,now(),'Synthetic direct approval',?,now(),0)
                """, uuid("36000000", 10), fixture.organisationId(), fixture.schoolId(),
                fixture.learnerId(), fixture.guardianId(), fixture.staffMembershipId(),
                fixture.staffMembershipId(), fixture.guardianUserId(), fixture.guardianMembershipId(),
                studentUser))
                .hasMessageContaining("Student portal requests must begin pending");
    }

    @Test
    @Transactional
    void studentPortalAllowsOnlyOnePendingConsentPerLearner() {
        PortalFixture fixture = academyPortalFixture("37000000");
        insertPortalFixture(fixture, "portal-duplicate-pending");
        insertPendingPortalConsent(fixture, uuid("37000000", 9), "student.pending-one");

        assertThatThrownBy(() -> insertPendingPortalConsent(
                fixture, uuid("37000000", 10), "student.pending-two"))
                .hasMessageContaining("student_portal_consent_requests_one_pending_idx");
    }

    @Test
    @Transactional
    void studentPortalApprovalRequiresTheLinkedGuardianIdentity() {
        PortalFixture fixture = academyPortalFixture("38000000");
        insertPortalFixture(fixture, "portal-guardian-identity");
        UUID consent = uuid("38000000", 9);
        UUID studentUser = uuid("38000000", 10);
        insertSyntheticLoginUser(studentUser, "student.guardian-check", null);
        insertPendingPortalConsent(fixture, consent, "student.guardian-check");

        assertThatThrownBy(() -> approvePortalConsent(
                fixture, consent, studentUser, fixture.staffUserId(), fixture.staffMembershipId()))
                .hasMessageContaining("linked guardian identity and membership");
    }

    @Test
    @Transactional
    void studentPortalAccountRequiresMatchingApprovedConsent() {
        PortalFixture fixture = academyPortalFixture("39000000");
        insertPortalFixture(fixture, "portal-account-consent");
        UUID consent = uuid("39000000", 9);
        UUID approvedStudentUser = uuid("39000000", 10);
        UUID differentStudentUser = uuid("39000000", 11);
        insertSyntheticLoginUser(approvedStudentUser, "student.approved", null);
        insertSyntheticLoginUser(differentStudentUser, "student.different", null);
        insertPendingPortalConsent(fixture, consent, "student.approved");
        approvePortalConsent(fixture, consent, approvedStudentUser,
                fixture.guardianUserId(), fixture.guardianMembershipId());

        assertThatThrownBy(() -> insertPortalAccount(
                fixture, uuid("39000000", 12), consent, differentStudentUser))
                .hasMessageContaining("requires matching approved consent");
    }

    @Test
    @Transactional
    void studentPortalHistoryIsValidatedAndAppendOnly() {
        PortalFixture fixture = academyPortalFixture("41000000");
        insertPortalFixture(fixture, "portal-history");
        UUID consent = uuid("41000000", 9);
        UUID studentUser = uuid("41000000", 10);
        UUID account = uuid("41000000", 11);
        UUID history = uuid("41000000", 12);
        insertSyntheticLoginUser(studentUser, "student.history", null);
        insertPendingPortalConsent(fixture, consent, "student.history");
        approvePortalConsent(fixture, consent, studentUser,
                fixture.guardianUserId(), fixture.guardianMembershipId());
        insertPortalAccount(fixture, account, consent, studentUser);
        jdbc.update("""
                INSERT INTO learner_portal_account_history(
                  id,organisation_id,school_id,learner_portal_account_id,event_type,
                  previous_status,new_status,consent_request_id,reason,actor_user_id,
                  actor_membership_id,occurred_at)
                VALUES (?, ?, ?, ?, 'ACTIVATED', NULL, 'ACTIVE', ?,
                  'Synthetic guardian-approved activation', ?, ?, now())
                """, history, fixture.organisationId(), fixture.schoolId(), account, consent,
                fixture.guardianUserId(), fixture.guardianMembershipId());

        Integer historyCount = jdbc.queryForObject(
                "SELECT count(*) FROM learner_portal_account_history WHERE id=?",
                Integer.class, history);
        assertThat(historyCount).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE learner_portal_account_history SET reason='Rewritten evidence' WHERE id=?",
                history))
                .hasMessageContaining("append-only");
    }

    @Test
    @Transactional
    void studentPortalRecordsRemainOrganisationIsolatedUnderRowSecurity() {
        PortalFixture academy = academyPortalFixture("42000000");
        insertPortalFixture(academy, "portal-rls-academy");
        UUID otherOrganisation = uuid("43000000", 1);
        UUID otherSchool = uuid("43000000", 2);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at)
                VALUES (?, 'student-portal-isolation-org', 'Student Portal Isolation Organisation',
                  'Student Portal Isolation', now(), now())
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at)
                VALUES (?, ?, 'student-portal-isolation-school', 'Student Portal Isolation School',
                  'Student Portal Isolation School', now(), now())
                """, otherSchool, otherOrganisation);
        PortalFixture other = new PortalFixture(
                otherOrganisation, otherSchool, uuid("43000000", 3), uuid("43000000", 4),
                uuid("43000000", 5), uuid("43000000", 6), uuid("43000000", 7),
                uuid("43000000", 8), uuid("43000000", 9));
        insertPortalFixture(other, "portal-rls-other");
        UUID academyConsent = uuid("42000000", 9);
        UUID otherConsent = uuid("43000000", 10);
        insertPendingPortalConsent(academy, academyConsent, "student.rls-academy");
        insertPendingPortalConsent(other, otherConsent, "student.rls-other");

        jdbc.execute("CREATE ROLE educore_student_portal_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_student_portal_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON student_portal_consent_requests TO educore_student_portal_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_student_portal_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academy.organisationId().toString());

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM student_portal_consent_requests", Integer.class);
        int crossTenantUpdate = jdbc.update("""
                UPDATE student_portal_consent_requests
                SET preparation_reason='Forbidden cross-tenant rewrite'
                WHERE id=?
                """, otherConsent);
        String visibleConsent = jdbc.queryForObject(
                "SELECT id::text FROM student_portal_consent_requests WHERE id=?",
                String.class, academyConsent);

        assertThat(visible).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
        assertThat(visibleConsent).isEqualTo(academyConsent.toString());
    }

    @Test
    @Transactional
    void importRowsProvideExclusiveLearnerAndEnrolmentProvenance() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID userId = UUID.fromString("29000000-0000-4000-8000-000000000001");
        UUID membershipId = UUID.fromString("29000000-0000-4000-8000-000000000002");
        UUID sessionId = UUID.fromString("29000000-0000-4000-8000-000000000003");
        UUID batchId = UUID.fromString("29000000-0000-4000-8000-000000000004");
        UUID rowId = UUID.fromString("29000000-0000-4000-8000-000000000005");
        UUID learnerId = UUID.fromString("29000000-0000-4000-8000-000000000006");
        UUID enrolmentId = UUID.fromString("29000000-0000-4000-8000-000000000007");
        UUID guardianId = UUID.fromString("29000000-0000-4000-8000-000000000008");

        insertSyntheticUser(userId, "import-provenance@example.test");
        insertSyntheticMembership(membershipId, userId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO academic_sessions (
                    id, organisation_id, school_id, name, starts_on, ends_on,
                    status, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, 'Synthetic import 2026-27', DATE '2026-04-01', DATE '2027-03-31',
                    'ACTIVE', now(), now(), 0)
                """, sessionId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO student_import_batches (
                    id, organisation_id, school_id, campus_id, academic_session_id, starts_on,
                    source_file_name, file_sha256, status, total_rows, ready_rows, review_rows,
                    invalid_rows, skipped_rows, committed_rows, created_by_membership_id,
                    created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, DATE '2026-04-01', 'synthetic-import.csv', ?,
                    'READY', 1, 1, 0, 0, 0, 0, ?, now(), now(), 0)
                """, batchId, organisationId, schoolId, campusId, sessionId, "a".repeat(64), membershipId);
        jdbc.update("""
                INSERT INTO student_import_rows (
                    id, batch_id, organisation_id, school_id, source_row_number, legal_name,
                    birth_date, gender_code, grade_code, guardian_name, guardian_relationship_code,
                    guardian_email_normalized, primary_contact, pickup_authorized, status,
                    validation_errors, learner_resolution, guardian_resolution,
                    created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, 2, 'Synthetic imported learner', DATE '2015-01-15',
                    'NOT_STATED', 'VI', 'Synthetic guardian', 'PARENT_GUARDIAN',
                    'import-guardian@example.test', true, true, 'READY', '[]'::jsonb,
                    'CREATE_NEW', 'CREATE_NEW', now(), now(), 0)
                """, rowId, batchId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO guardians (
                    id, organisation_id, school_id, display_name, email_normalized,
                    status, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, 'Synthetic guardian', 'import-guardian@example.test',
                    'ACTIVE', now(), now(), 0)
                """, guardianId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO learners (
                    id, organisation_id, school_id, origin_import_row_id, legal_name,
                    birth_date, gender_code, status, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, 'Synthetic imported learner', DATE '2015-01-15',
                    'NOT_STATED', 'ACTIVE', now(), now(), 0)
                """, learnerId, organisationId, schoolId, rowId);
        jdbc.update("""
                INSERT INTO student_enrolments (
                    id, organisation_id, school_id, campus_id, academic_session_id,
                    learner_id, origin_import_row_id, grade_code, status, placement_status,
                    starts_on, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'VI', 'ACTIVE', 'PENDING',
                    DATE '2026-04-01', now(), now(), 0)
                """, enrolmentId, organisationId, schoolId, campusId, sessionId, learnerId, rowId);
        jdbc.update("""
                UPDATE student_import_rows
                SET status = 'COMMITTED', resolved_guardian_id = ?,
                    created_learner_id = ?, created_enrolment_id = ?,
                    updated_at = now(), row_version = row_version + 1
                WHERE id = ?
                """, guardianId, learnerId, enrolmentId, rowId);
        jdbc.update("""
                UPDATE student_import_batches
                SET status = 'COMMITTED', ready_rows = 0, committed_rows = 1,
                    committed_by_membership_id = ?, committed_at = now(),
                    updated_at = now(), row_version = row_version + 1
                WHERE id = ?
                """, membershipId, batchId);

        Integer provenanceCount = jdbc.queryForObject("""
                SELECT (
                    SELECT count(*) FROM learners
                    WHERE id = ? AND origin_application_id IS NULL AND origin_import_row_id = ?
                ) + (
                    SELECT count(*) FROM student_enrolments
                    WHERE id = ? AND origin_application_id IS NULL AND origin_import_row_id = ?
                ) + (
                    SELECT count(*) FROM student_import_rows
                    WHERE id = ? AND resolved_guardian_id = ?
                )
                """, Integer.class, learnerId, rowId, enrolmentId, rowId, rowId, guardianId);
        assertThat(provenanceCount).isEqualTo(3);
    }

    @Test
    @Transactional
    void rowSecurityHidesAndProtectsApplicationsFromAnotherOrganisation() {
        UUID academyOrganisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchool = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID otherOrganisation = UUID.fromString("21000000-0000-4000-8000-000000000001");
        UUID otherSchool = UUID.fromString("21000000-0000-4000-8000-000000000002");
        UUID academyUser = UUID.fromString("22000000-0000-4000-8000-000000000001");
        UUID otherUser = UUID.fromString("22000000-0000-4000-8000-000000000002");
        UUID academyMembership = UUID.fromString("23000000-0000-4000-8000-000000000001");
        UUID otherMembership = UUID.fromString("23000000-0000-4000-8000-000000000002");
        UUID academyApplication = UUID.fromString("24000000-0000-4000-8000-000000000001");
        UUID otherApplication = UUID.fromString("24000000-0000-4000-8000-000000000002");

        jdbc.update("""
                INSERT INTO organisations (id, slug, legal_name, display_name, created_at, updated_at)
                VALUES (?, 'application-isolation-org', 'Application Isolation Organisation',
                    'Application Isolation', now(), now())
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools (id, organisation_id, slug, legal_name, display_name, created_at, updated_at)
                VALUES (?, ?, 'application-isolation-school', 'Application Isolation School',
                    'Application Isolation School', now(), now())
                """, otherSchool, otherOrganisation);
        insertSyntheticUser(academyUser, "academy-application@example.test");
        insertSyntheticUser(otherUser, "other-application@example.test");
        insertSyntheticMembership(academyMembership, academyUser, academyOrganisation, academySchool);
        insertSyntheticMembership(otherMembership, otherUser, otherOrganisation, otherSchool);
        insertApplication(
                academyApplication,
                academyOrganisation,
                academySchool,
                academyMembership,
                "academy-application-key");
        insertApplication(
                otherApplication,
                otherOrganisation,
                otherSchool,
                otherMembership,
                "other-application-key");

        jdbc.execute("CREATE ROLE educore_application_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_application_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON admission_applications TO educore_application_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_application_rls_test");
        jdbc.queryForObject(
                "SELECT set_config('app.current_organisation_id', ?, true)",
                String.class,
                academyOrganisation.toString());

        Integer visibleCount = jdbc.queryForObject("SELECT count(*) FROM admission_applications", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE admission_applications SET target_grade_code = 'X' WHERE id = ?",
                otherApplication);
        String visibleApplication = jdbc.queryForObject(
                "SELECT id::text FROM admission_applications WHERE id = ?",
                String.class,
                academyApplication);

        assertThat(visibleCount).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
        assertThat(visibleApplication).isEqualTo(academyApplication.toString());
    }

    @Test
    @Transactional
    void objectAuthorizationRequiresTheCapabilityOnTheRecordsOwnSchoolMembership() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID permittedSchool = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID otherSchool = UUID.fromString("25000000-0000-4000-8000-000000000001");
        UUID userId = UUID.fromString("25000000-0000-4000-8000-000000000002");
        UUID sessionId = UUID.fromString("25000000-0000-4000-8000-000000000003");
        UUID permittedMembership = UUID.fromString("25000000-0000-4000-8000-000000000004");
        UUID otherMembership = UUID.fromString("25000000-0000-4000-8000-000000000005");
        UUID permittedEnquiry = UUID.fromString("25000000-0000-4000-8000-000000000006");
        UUID otherEnquiry = UUID.fromString("25000000-0000-4000-8000-000000000007");
        UUID permittedApplication = UUID.fromString("25000000-0000-4000-8000-000000000008");
        UUID otherApplication = UUID.fromString("25000000-0000-4000-8000-000000000009");

        jdbc.update("""
                INSERT INTO schools (
                    id, organisation_id, slug, legal_name, display_name, created_at, updated_at
                ) VALUES (?, ?, 'same-org-other-school', 'Same Organisation Other School',
                    'Same Organisation Other School', now(), now())
                """, otherSchool, organisationId);
        insertSyntheticUser(userId, "school-scope-user@example.test");
        insertSyntheticMembership(permittedMembership, userId, organisationId, permittedSchool);
        insertSyntheticMembership(otherMembership, userId, organisationId, otherSchool);
        jdbc.update(
                "INSERT INTO membership_roles (membership_id, role_id) VALUES (?, ?)",
                permittedMembership,
                UUID.fromString("00000000-0000-4000-8000-000000000001"));
        jdbc.update(
                "INSERT INTO membership_roles (membership_id, role_id) VALUES (?, ?)",
                otherMembership,
                UUID.fromString("00000000-0000-4000-8000-000000000003"));
        insertEnquiry(permittedEnquiry, organisationId, permittedSchool, "same-school-enquiry-key");
        insertEnquiry(otherEnquiry, organisationId, otherSchool, "other-school-enquiry-key");
        insertApplication(
                permittedApplication,
                organisationId,
                permittedSchool,
                permittedMembership,
                "same-school-application-key");
        insertApplication(
                otherApplication,
                organisationId,
                otherSchool,
                otherMembership,
                "other-school-application-key");

        AuthenticatedUser actor = new AuthenticatedUser(
                userId,
                sessionId,
                "school-scope-user@example.test",
                "school-scope-user@example.test",
                "Synthetic scoped user",
                true,
                organisationId,
                Set.of(permittedMembership, otherMembership),
                Set.of("admissions.enquiry.read", "admissions.application.read"),
                List.of());

        var visibleEnquiries = enquiries.list(actor, null, null, null, 0, 25);
        var visibleApplications = applications.list(actor, null, null, 0, 25);

        assertThat(visibleEnquiries.items())
                .extracting(item -> item.id())
                .containsExactly(permittedEnquiry);
        assertThat(visibleApplications.items())
                .extracting(item -> item.id())
                .containsExactly(permittedApplication);
    }

    @Test
    @Transactional
    void duplicateApplicationRetryKeysAreRejectedWithinTheSchool() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID userId = UUID.fromString("26000000-0000-4000-8000-000000000001");
        UUID membershipId = UUID.fromString("26000000-0000-4000-8000-000000000002");
        UUID firstApplication = UUID.fromString("26000000-0000-4000-8000-000000000003");
        UUID duplicateApplication = UUID.fromString("26000000-0000-4000-8000-000000000004");

        insertSyntheticUser(userId, "duplicate-application@example.test");
        insertSyntheticMembership(membershipId, userId, organisationId, schoolId);
        insertApplication(
                firstApplication,
                organisationId,
                schoolId,
                membershipId,
                "duplicate-school-retry-key");

        assertThatThrownBy(() -> insertApplication(
                duplicateApplication,
                organisationId,
                schoolId,
                membershipId,
                "duplicate-school-retry-key"))
                .hasMessageContaining("admission_applications_school_idempotency_unique");
    }

    @Test
    @Transactional
    void anApplicationCannotHaveTwoPrimaryGuardians() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID userId = UUID.fromString("27000000-0000-4000-8000-000000000001");
        UUID membershipId = UUID.fromString("27000000-0000-4000-8000-000000000002");
        UUID applicationId = UUID.fromString("27000000-0000-4000-8000-000000000003");

        insertSyntheticUser(userId, "primary-guardian@example.test");
        insertSyntheticMembership(membershipId, userId, organisationId, schoolId);
        insertApplication(
                applicationId,
                organisationId,
                schoolId,
                membershipId,
                "primary-guardian-application-key");
        insertApplicationGuardian(
                UUID.fromString("27000000-0000-4000-8000-000000000004"),
                organisationId,
                schoolId,
                applicationId,
                "First synthetic guardian",
                "first-guardian@example.test",
                true);

        assertThatThrownBy(() -> insertApplicationGuardian(
                UUID.fromString("27000000-0000-4000-8000-000000000005"),
                organisationId,
                schoolId,
                applicationId,
                "Second synthetic guardian",
                "second-guardian@example.test",
                true))
                .hasMessageContaining("admission_application_guardians_one_primary_idx");
    }

    @Test
    @Transactional
    void sectionPlacementsFreezeEndedHistory() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID userId = UUID.fromString("28000000-0000-4000-8000-000000000001");
        UUID membershipId = UUID.fromString("28000000-0000-4000-8000-000000000002");
        UUID applicationId = UUID.fromString("28000000-0000-4000-8000-000000000003");
        UUID sessionId = UUID.fromString("28000000-0000-4000-8000-000000000004");
        UUID learnerId = UUID.fromString("28000000-0000-4000-8000-000000000005");
        UUID enrolmentId = UUID.fromString("28000000-0000-4000-8000-000000000006");
        UUID sectionId = UUID.fromString("28000000-0000-4000-8000-000000000007");
        UUID placementId = UUID.fromString("28000000-0000-4000-8000-000000000008");

        insertSyntheticUser(userId, "placement-history@example.test");
        insertSyntheticMembership(membershipId, userId, organisationId, schoolId);
        insertApplication(applicationId, organisationId, schoolId, membershipId, "placement-history-application-key");
        jdbc.update("""
                INSERT INTO academic_sessions (
                    id, organisation_id, school_id, name, starts_on, ends_on,
                    status, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, 'Synthetic 2026-27', DATE '2026-04-01', DATE '2027-03-31',
                    'ACTIVE', now(), now(), 0)
                """, sessionId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO learners (
                    id, organisation_id, school_id, origin_application_id, legal_name,
                    birth_date, gender_code, status, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, 'Synthetic learner', DATE '2015-01-01',
                    'NOT_STATED', 'ACTIVE', now(), now(), 0)
                """, learnerId, organisationId, schoolId, applicationId);
        jdbc.update("""
                INSERT INTO student_enrolments (
                    id, organisation_id, school_id, campus_id, academic_session_id,
                    learner_id, origin_application_id, grade_code, status,
                    placement_status, starts_on, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'VI', 'ACTIVE', 'PENDING',
                    DATE '2026-04-01', now(), now(), 0)
                """, enrolmentId, organisationId, schoolId, campusId, sessionId, learnerId, applicationId);
        jdbc.update("""
                INSERT INTO class_sections (
                    id, organisation_id, school_id, campus_id, academic_session_id,
                    grade_code, code, display_name, capacity, status,
                    created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, 'VI', 'A', 'Synthetic VI A', 30,
                    'ACTIVE', now(), now(), 0)
                """, sectionId, organisationId, schoolId, campusId, sessionId);
        jdbc.update("""
                INSERT INTO student_section_placements (
                    id, organisation_id, school_id, enrolment_id, learner_id,
                    class_section_id, roll_number, starts_on, status,
                    placed_by_membership_id, placement_reason, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, '12', DATE '2026-04-01', 'ACTIVE',
                    ?, 'Synthetic initial allocation', now(), now(), 0)
                """, placementId, organisationId, schoolId, enrolmentId, learnerId, sectionId, membershipId);

        jdbc.update("""
                UPDATE student_section_placements
                SET status = 'ENDED', ends_on = DATE '2026-04-30',
                    ended_by_membership_id = ?, end_reason = 'Synthetic reassignment',
                    updated_at = now(), row_version = row_version + 1
                WHERE id = ?
                """, membershipId, placementId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE student_section_placements SET roll_number = '99' WHERE id = ?",
                placementId))
                .hasMessageContaining("ended or cancelled placements are immutable");
    }

    @Test
    void appliesV028WorkforceAttendanceAndLeaveWithRequiredGuards() {
        var v028 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V028__staff_attendance_and_leave.sql".equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'workforce_attendance_policies','staff_leave_types','staff_leave_requests',
                  'staff_leave_request_history','staff_leave_balance_entries',
                  'staff_attendance_registers','staff_attendance_marks',
                  'staff_attendance_mark_history','staff_attendance_correction_requests')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN (
                  'workforce_attendance_policies','staff_leave_types','staff_leave_requests',
                  'staff_leave_request_history','staff_leave_balance_entries',
                  'staff_attendance_registers','staff_attendance_marks',
                  'staff_attendance_mark_history','staff_attendance_correction_requests')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'staff_attendance_registers_guard_history','staff_attendance_marks_guard_submitted',
                  'staff_leave_requests_guard_history','staff_attendance_mark_history_immutable',
                  'staff_leave_request_history_immutable','staff_leave_balance_entries_immutable',
                  'staff_attendance_corrections_guard_history')
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code IN (
                  'workforce.self','workforce.read','workforce.attendance.record',
                  'workforce.attendance.correct','workforce.leave.approve','workforce.configure')
                """, Integer.class);

        assertThat(v028).isPresent();
        assertThat(tableCount).isEqualTo(9);
        assertThat(policyCount).isEqualTo(9);
        assertThat(guardCount).isEqualTo(7);
        assertThat(capabilityCount).isEqualTo(6);
    }

    @Test
    void appliesV029DoubleEntryAccountingWithRequiredGuards() {
        var v029 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V029__double_entry_accounting_foundation.sql".equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'accounting_financial_years','accounting_periods','accounting_accounts',
                  'accounting_vendors','accounting_journal_sequences','accounting_journals',
                  'accounting_journal_lines','accounting_journal_history','accounting_reversal_requests')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN (
                  'accounting_financial_years','accounting_periods','accounting_accounts',
                  'accounting_vendors','accounting_journal_sequences','accounting_journals',
                  'accounting_journal_lines','accounting_journal_history','accounting_reversal_requests')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name LIKE 'accounting_%'
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code IN (
                  'accounting.read','accounting.configure','accounting.prepare','accounting.post',
                  'accounting.close','accounting.reverse.request','accounting.reverse.approve')
                """, Integer.class);

        assertThat(v029).isPresent();
        assertThat(tableCount).isEqualTo(9);
        assertThat(policyCount).isEqualTo(9);
        assertThat(guardCount).isGreaterThanOrEqualTo(19);
        assertThat(capabilityCount).isEqualTo(7);
    }

    @Test
    @Transactional
    void accountingIsBalancedRetrySafeIndependentlyPostedReversedAndLedgerDerived() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID makerUserId = uuid("61000000", 1);
        UUID makerMembershipId = uuid("61000000", 2);
        UUID approverUserId = uuid("61000000", 3);
        UUID approverMembershipId = uuid("61000000", 4);
        insertSyntheticUser(makerUserId, "accounting-maker@example.test");
        insertSyntheticMembership(makerMembershipId, makerUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                makerMembershipId, UUID.fromString("00000000-0000-4000-8000-000000000001"));
        insertSyntheticUser(approverUserId, "accounting-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, UUID.fromString("00000000-0000-4000-8000-000000000007"));
        AuthenticatedUser maker = new AuthenticatedUser(makerUserId, UUID.randomUUID(),
                "accounting-maker@example.test", "accounting-maker@example.test",
                "Synthetic accounting maker", true, organisationId, Set.of(makerMembershipId),
                Set.of("accounting.read", "accounting.configure", "accounting.prepare",
                        "accounting.post", "accounting.close", "accounting.reverse.request",
                        "accounting.reverse.approve"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(approverUserId, UUID.randomUUID(),
                "accounting-approver@example.test", "accounting-approver@example.test",
                "Synthetic accounting approver", true, organisationId, Set.of(approverMembershipId),
                Set.of("accounting.read", "accounting.post", "accounting.close",
                        "accounting.reverse.approve"), List.of());

        var year = accounting.createFinancialYear(maker, new FinancialYearRequest(
                schoolId, "FY_2026_27", "Synthetic financial year 2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), "ACCRUAL", "INR",
                "Synthetic accounting integration setup"));
        var cash = accounting.createAccount(maker, new AccountRequest(
                schoolId, null, "A1000.CASH", "Synthetic cash", null, "ASSET", "CASH",
                true, "ACTIVE", null, "Synthetic posting account"));
        var income = accounting.createAccount(maker, new AccountRequest(
                schoolId, null, "I4000.FEES", "Synthetic fee income", null, "INCOME", "INCOME",
                true, "ACTIVE", null, "Synthetic posting account"));
        accounting.openFinancialYear(approver, year.id(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                schoolId, 0, "Synthetic independent financial year opening"));
        var initial = accounting.snapshot(maker, schoolId, year.id(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        UUID april = initial.financialYears().stream().filter(item -> item.id().equals(year.id()))
                .findFirst().orElseThrow().periods().getFirst().id();
        JournalRequest receipt = new JournalRequest(schoolId, year.id(), april, "RECEIPT",
                LocalDate.of(2026, 4, 10), "SYNTHETIC-RCPT", "Synthetic fee receipt",
                List.of(
                        new JournalLineRequest(cash.id(), null, null,
                                new BigDecimal("500.00"), BigDecimal.ZERO, "Synthetic cash debit"),
                        new JournalLineRequest(income.id(), null, null,
                                BigDecimal.ZERO, new BigDecimal("500.00"), "Synthetic income credit")));
        var draft = accounting.createJournal(maker, "accounting-receipt-0001", receipt);
        var retry = accounting.createJournal(maker, "accounting-receipt-0001", receipt);
        assertThat(retry.id()).isEqualTo(draft.id());
        assertThatThrownBy(() -> accounting.createJournal(maker, "accounting-unbalanced-0001",
                new JournalRequest(schoolId, year.id(), april, "JOURNAL", LocalDate.of(2026, 4, 10),
                        null, "Synthetic unbalanced voucher", List.of(
                        new JournalLineRequest(cash.id(), null, null, new BigDecimal("500.00"),
                                BigDecimal.ZERO, null),
                        new JournalLineRequest(income.id(), null, null, BigDecimal.ZERO,
                                new BigDecimal("499.00"), null)))))
                .hasMessageContaining("Total debits must equal total credits");
        var submitted = accounting.submitJournal(maker, draft.id(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                schoolId, 0, "Synthetic balanced voucher review submission"));
        assertThatThrownBy(() -> accounting.decideJournal(maker, draft.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        schoolId, "POST", "Synthetic self-post attempt", submitted.rowVersion())))
                .hasMessageContaining("different authorized person");
        var posted = accounting.decideJournal(approver, draft.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        schoolId, "POST", "Synthetic independent voucher posting", submitted.rowVersion()));
        assertThat(posted.voucherNumber()).startsWith("RCPT/FY-2026-27/");
        var postedSnapshot = accounting.snapshot(approver, schoolId, year.id(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        assertThat(postedSnapshot.statements().trialDebit()).isEqualByComparingTo("500.00");
        assertThat(postedSnapshot.statements().trialCredit()).isEqualByComparingTo("500.00");
        assertThat(postedSnapshot.statements().cash()).isEqualByComparingTo("500.00");
        assertThat(postedSnapshot.statements().periodSurplus()).isEqualByComparingTo("500.00");

        var reversal = accounting.requestReversal(maker, draft.id(), new ReversalRequest(
                schoolId, april, LocalDate.of(2026, 4, 11), "Synthetic correction by counter-voucher"));
        var approved = accounting.decideReversal(approver, reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        schoolId, "APPROVE", "Synthetic independent reversal approval", 0));
        assertThat(approved.voucherNumber()).startsWith("JV/FY-2026-27/");
        var reversedSnapshot = accounting.snapshot(approver, schoolId, year.id(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        assertThat(reversedSnapshot.statements().trialDebit()).isEqualByComparingTo("0.00");
        assertThat(reversedSnapshot.statements().trialCredit()).isEqualByComparingTo("0.00");
        assertThat(reversedSnapshot.generalLedger()).hasSize(4);
        assertThat(reversedSnapshot.journals()).extracting(item -> item.status())
                .containsExactlyInAnyOrder("REVERSED", "POSTED");
        assertThatThrownBy(() -> accounting.snapshot(maker, UUID.randomUUID(), year.id(), null, null))
                .hasMessageContaining("unavailable for this school");
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE accounting_journal_lines SET memo='Rewritten' WHERE accounting_journal_id=?",
                draft.id()), "posted or submitted accounting lines are immutable");
        assertDatabaseRejected(() -> jdbc.update(
                "DELETE FROM accounting_journals WHERE id=?", draft.id()), "append-only");
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE accounting_journal_history SET reason='Rewritten' WHERE accounting_journal_id=?",
                draft.id()), "append-only");
    }

    @Test
    @Transactional
    void accountingAccountsRemainOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID academyUserId = uuid("62000000", 1);
        UUID academyMembershipId = uuid("62000000", 2);
        insertSyntheticUser(academyUserId, "accounting-rls-academy@example.test");
        insertSyntheticMembership(
                academyMembershipId, academyUserId, academyOrganisationId, academySchoolId);

        UUID otherOrganisationId = uuid("62000000", 3);
        UUID otherSchoolId = uuid("62000000", 4);
        UUID otherUserId = uuid("62000000", 5);
        UUID otherMembershipId = uuid("62000000", 6);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'accounting-isolation-org', 'Synthetic accounting isolation org',
                  'Synthetic accounting isolation org', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'accounting-isolation-school', 'Synthetic accounting isolation school',
                  'Synthetic accounting isolation school', now(), now(), 0)
                """, otherSchoolId, otherOrganisationId);
        insertSyntheticUser(otherUserId, "accounting-rls-other@example.test");
        insertSyntheticMembership(
                otherMembershipId, otherUserId, otherOrganisationId, otherSchoolId);

        UUID academyAccountId = uuid("62000000", 7);
        UUID otherAccountId = uuid("62000000", 8);
        jdbc.update("""
                INSERT INTO accounting_accounts(
                  id,organisation_id,school_id,code,display_name,account_type,normal_balance,
                  category,allow_direct_posting,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'RLS.CASH', 'Synthetic academy cash', 'ASSET', 'DEBIT',
                  'CASH', true, 'ACTIVE', ?, now(), now(), 0)
                """, academyAccountId, academyOrganisationId, academySchoolId,
                academyMembershipId);
        jdbc.update("""
                INSERT INTO accounting_accounts(
                  id,organisation_id,school_id,code,display_name,account_type,normal_balance,
                  category,allow_direct_posting,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'RLS.CASH', 'Synthetic other cash', 'ASSET', 'DEBIT',
                  'CASH', true, 'ACTIVE', ?, now(), now(), 0)
                """, otherAccountId, otherOrganisationId, otherSchoolId,
                otherMembershipId);

        jdbc.execute("CREATE ROLE educore_accounting_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_accounting_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON accounting_accounts TO educore_accounting_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_accounting_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisationId.toString());

        Integer visibleAccounts = jdbc.queryForObject(
                "SELECT count(*) FROM accounting_accounts", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE accounting_accounts SET updated_at=now() WHERE id=?", otherAccountId);

        assertThat(visibleAccounts).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
    }

    @Test
    void appliesV030FeeAccountingPostingAndReconciliationWithRequiredGuards() {
        var v030 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V030__fee_accounting_posting_and_reconciliation.sql"
                        .equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'fee_accounting_configurations','fee_accounting_head_mappings',
                  'fee_accounting_tender_mappings','fee_accounting_posting_batches',
                  'fee_accounting_posting_items')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public'
                  AND tablename IN (
                    'fee_accounting_configurations','fee_accounting_head_mappings',
                    'fee_accounting_tender_mappings','fee_accounting_posting_batches',
                    'fee_accounting_posting_items')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public'
                  AND trigger_name LIKE 'fee_accounting_%'
                """, Integer.class);

        assertThat(v030).isPresent();
        assertThat(tableCount).isEqualTo(5);
        assertThat(policyCount).isEqualTo(5);
        assertThat(guardCount).isGreaterThanOrEqualTo(8);
    }

    @Test
    @Transactional
    void feeAccountingIsMappedMakerCheckerRetrySafeRepreparableAndLedgerReconciled() {
        RolloverFixture fixture = insertAcademicRolloverFixture();
        UUID approverUserId = uuid("63000000", 1);
        UUID approverMembershipId = uuid("63000000", 2);
        insertSyntheticUser(approverUserId, "fee-accounting-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId,
                fixture.organisationId(), fixture.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId,
                UUID.fromString("00000000-0000-4000-8000-000000000007"));
        AuthenticatedUser maker = new AuthenticatedUser(fixture.actorUserId(),
                UUID.randomUUID(), "rollover-admin@example.test",
                "rollover-admin@example.test", "Synthetic fee accounting maker", true,
                fixture.organisationId(), Set.of(fixture.actorMembershipId()),
                Set.of("fees.read", "fees.close.propose", "fees.close.approve",
                        "fees.collect", "accounting.read", "accounting.configure",
                        "accounting.prepare", "accounting.post", "accounting.close"),
                List.of());
        AuthenticatedUser approver = new AuthenticatedUser(approverUserId,
                UUID.randomUUID(), "fee-accounting-approver@example.test",
                "fee-accounting-approver@example.test",
                "Synthetic fee accounting approver", true, fixture.organisationId(),
                Set.of(approverMembershipId),
                Set.of("fees.read", "fees.close.approve", "accounting.read",
                        "accounting.post", "accounting.close"), List.of());

        var year = accounting.createFinancialYear(maker, new FinancialYearRequest(
                fixture.schoolId(), "FEE_ACCRUAL_25", "Synthetic fee accounting year",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31),
                "ACCRUAL", "INR", "Synthetic fee accounting setup"));
        var receivable = accounting.createAccount(maker, new AccountRequest(
                fixture.schoolId(), null, "A1100.FEES", "Synthetic fee receivable",
                null, "ASSET", "RECEIVABLE", true, "ACTIVE", null,
                "Synthetic fee subledger control"));
        var concession = accounting.createAccount(maker, new AccountRequest(
                fixture.schoolId(), null, "E5100.CONCESSION", "Synthetic concessions",
                null, "EXPENSE", "EXPENSE", true, "ACTIVE", null,
                "Synthetic concession expense"));
        var income = accounting.createAccount(maker, new AccountRequest(
                fixture.schoolId(), null, "I4100.TUITION", "Synthetic tuition income",
                null, "INCOME", "INCOME", true, "ACTIVE", null,
                "Synthetic fee income mapping"));
        var cash = accounting.createAccount(maker, new AccountRequest(
                fixture.schoolId(), null, "A1000.FEE_CASH", "Synthetic fee cash",
                null, "ASSET", "CASH", true, "ACTIVE", null,
                "Synthetic cash tender mapping"));
        var bank = accounting.createAccount(maker, new AccountRequest(
                fixture.schoolId(), null, "A1010.FEE_BANK", "Synthetic fee bank",
                null, "ASSET", "BANK", true, "ACTIVE", null,
                "Synthetic bank tender mapping"));
        accounting.openFinancialYear(approver, year.id(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(
                        fixture.schoolId(), 0,
                        "Synthetic independent book opening"));

        UUID feeHeadId = jdbc.queryForObject("""
                SELECT id FROM fee_heads WHERE school_id=? AND code='ROLL_TUITION'
                """, UUID.class, fixture.schoolId());
        List<TenderMappingRequest> tenders = List.of(
                new TenderMappingRequest("CASH", cash.id()),
                new TenderMappingRequest("BANK_TRANSFER", bank.id()),
                new TenderMappingRequest("CHEQUE", bank.id()),
                new TenderMappingRequest("SCHOOL_UPI", bank.id()),
                new TenderMappingRequest("ONLINE_PROVIDER", bank.id()));
        var configuration = feeAccounting.createConfiguration(maker,
                new ConfigurationRequest(fixture.schoolId(), year.id(), null, 1,
                        LocalDate.of(2025, 4, 1), receivable.id(), concession.id(),
                        List.of(new HeadMappingRequest(feeHeadId, income.id())),
                        tenders, null, "Synthetic complete fee mapping"));
        var draftMapping = feeAccounting.snapshot(maker, fixture.schoolId())
                .configurations().stream().filter(item -> item.id().equals(configuration.id()))
                .findFirst().orElseThrow();
        assertThat(draftMapping.headMappings()).hasSize(1);
        assertThat(draftMapping.headMappings().getFirst().feeHeadId()).isEqualTo(feeHeadId);
        assertThat(draftMapping.headMappings().getFirst().incomeAccountId()).isEqualTo(income.id());
        assertThat(draftMapping.tenderMappings()).extracting(item -> item.paymentMethod())
                .containsExactlyInAnyOrder("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER");
        assertThat(draftMapping.tenderMappings()).allSatisfy(item ->
                assertThat(item.depositAccountId()).isEqualTo(item.paymentMethod().equals("CASH") ? cash.id() : bank.id()));
        var submitted = feeAccounting.submitConfiguration(maker, configuration.id(),
                new com.educore.api.finance.FeeAccountingController.VersionedReasonRequest(
                        fixture.schoolId(), configuration.rowVersion(),
                        "Synthetic fee mapping review submission"));
        assertThatThrownBy(() -> feeAccounting.decideConfiguration(maker,
                configuration.id(), new ConfigurationDecisionRequest(
                        fixture.schoolId(), "APPROVE", submitted.rowVersion(),
                        "Synthetic self-approval attempt")))
                .hasMessageContaining("different authorized person");
        feeAccounting.decideConfiguration(approver, configuration.id(),
                new ConfigurationDecisionRequest(fixture.schoolId(), "APPROVE",
                        submitted.rowVersion(), "Synthetic independent mapping approval"));
        var approvedMapping = feeAccounting.snapshot(approver, fixture.schoolId())
                .configurations().stream().filter(item -> item.id().equals(configuration.id()))
                .findFirst().orElseThrow();
        assertThat(approvedMapping.status()).isEqualTo("APPROVED");
        assertThat(approvedMapping.headMappings()).isEqualTo(draftMapping.headMappings());
        assertThat(approvedMapping.tenderMappings()).isEqualTo(draftMapping.tenderMappings());

        UUID feePlanId = jdbc.queryForObject("""
                SELECT id FROM fee_plans WHERE school_id=? AND plan_code='ROLL_VI'
                """, UUID.class, fixture.schoolId());
        jdbc.update("""
                INSERT INTO fee_receipt_sequences(organisation_id,school_id,
                  academic_session_id,receipt_prefix,next_value,updated_at)
                VALUES (?, ?, ?, 'ROLL', 1, now())
                """, fixture.organisationId(), fixture.schoolId(),
                fixture.sourceSessionId());
        var assigned = finance.assignPlan(maker, new PlanAssignmentRequest(
                fixture.schoolId(), feePlanId, fixture.promotedEnrolmentId(),
                "Synthetic fee accounting enrolment"));
        UUID chargeId = jdbc.queryForObject("""
                SELECT id FROM learner_fee_charges WHERE learner_fee_account_id=?
                """, UUID.class, assigned.id());

        PostingRequest chargePosting = new PostingRequest(fixture.schoolId(), year.id(),
                LocalDate.of(2025, 4, 10), "CHARGE", 200,
                "Synthetic accrual charge preparation");
        var chargeBatch = feeAccounting.preparePosting(maker,
                "fee-charge-batch-0001", chargePosting);
        var chargeRetry = feeAccounting.preparePosting(maker,
                "fee-charge-batch-0001", chargePosting);
        assertThat(chargeRetry.id()).isEqualTo(chargeBatch.id());
        UUID chargeJournalId = jdbc.queryForObject("""
                SELECT accounting_journal_id FROM fee_accounting_posting_batches WHERE id=?
                """, UUID.class, chargeBatch.id());
        accounting.decideJournal(approver, chargeJournalId,
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        fixture.schoolId(), "POST",
                        "Synthetic independent charge posting", 1));

        inManualFinanceScope(maker, () -> finance.recordPayment(maker, "fee-payment-source-0001", new PaymentRequest(
                fixture.schoolId(), assigned.id(), "CASH", null,
                new BigDecimal("500.00"), LocalDate.of(2025, 4, 10),
                "Synthetic fee receipt",
                List.of(new com.educore.api.finance.FinanceController.PaymentAllocation(
                        chargeId, new BigDecimal("500.00"))))));
        PostingRequest paymentPosting = new PostingRequest(fixture.schoolId(), year.id(),
                LocalDate.of(2025, 4, 10), "PAYMENT", 200,
                "Synthetic receipt preparation");
        var rejectedBatch = feeAccounting.preparePosting(maker,
                "fee-payment-batch-0001", paymentPosting);
        UUID rejectedJournalId = jdbc.queryForObject("""
                SELECT accounting_journal_id FROM fee_accounting_posting_batches WHERE id=?
                """, UUID.class, rejectedBatch.id());
        accounting.decideJournal(approver, rejectedJournalId,
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        fixture.schoolId(), "REJECT",
                        "Synthetic mapping review rejection", 1));
        var replacementBatch = feeAccounting.preparePosting(maker,
                "fee-payment-batch-0002", paymentPosting);
        assertThat(replacementBatch.id()).isNotEqualTo(rejectedBatch.id());
        UUID replacementJournalId = jdbc.queryForObject("""
                SELECT accounting_journal_id FROM fee_accounting_posting_batches WHERE id=?
                """, UUID.class, replacementBatch.id());
        accounting.decideJournal(approver, replacementJournalId,
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        fixture.schoolId(), "POST",
                        "Synthetic corrected receipt posting", 1));

        var reconciliation = feeAccounting.snapshot(maker, fixture.schoolId());
        assertThat(reconciliation.reconciliation().reconciledBatches()).isEqualTo(2);
        assertThat(reconciliation.reconciliation().repreparedBatches()).isEqualTo(1);
        assertThat(reconciliation.reconciliation().requiresRepreparationBatches()).isZero();
        assertThat(reconciliation.reconciliation().unpreparedSourceCount()).isZero();
        assertThat(reconciliation.postingBatches())
                .extracting(batch -> batch.journalStatus())
                .contains("POSTED", "REJECTED");
        Integer releasedItems = jdbc.queryForObject("""
                SELECT count(*) FROM fee_accounting_posting_items WHERE status='RELEASED'
                """, Integer.class);
        assertThat(releasedItems).isEqualTo(1);
        var ledger = accounting.snapshot(maker, fixture.schoolId(), year.id(),
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        assertThat(ledger.statements().receivables()).isEqualByComparingTo("200.00");
        assertThat(ledger.statements().cash()).isEqualByComparingTo("500.00");
        assertThat(ledger.statements().income()).isEqualByComparingTo("700.00");
        assertThatThrownBy(() -> feeAccounting.snapshot(
                maker, UUID.randomUUID())).hasMessageContaining("unavailable");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE fee_accounting_posting_items
                SET source_snapshot='{}'::jsonb WHERE posting_batch_id=?
                """, replacementBatch.id()), "append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                DELETE FROM fee_accounting_posting_batches WHERE id=?
                """, replacementBatch.id()), "append-only");
    }

    @Test
    @Transactional
    void feeAccountingConfigurationsRemainOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisationId =
                UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchoolId =
                UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID academyUserId = uuid("64000000", 1);
        UUID academyMembershipId = uuid("64000000", 2);
        insertSyntheticUser(academyUserId, "fee-accounting-rls-academy@example.test");
        insertSyntheticMembership(academyMembershipId, academyUserId,
                academyOrganisationId, academySchoolId);

        UUID otherOrganisationId = uuid("64000000", 3);
        UUID otherSchoolId = uuid("64000000", 4);
        UUID otherUserId = uuid("64000000", 5);
        UUID otherMembershipId = uuid("64000000", 6);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,
                  default_locale,created_at,updated_at,row_version)
                VALUES (?, 'fee-accounting-rls-org', 'Synthetic fee RLS org',
                  'Synthetic fee RLS org', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'fee-accounting-rls-school',
                  'Synthetic fee RLS school','Synthetic fee RLS school',now(),now(),0)
                """, otherSchoolId, otherOrganisationId);
        insertSyntheticUser(otherUserId, "fee-accounting-rls-other@example.test");
        insertSyntheticMembership(otherMembershipId, otherUserId,
                otherOrganisationId, otherSchoolId);

        UUID academyYearId = uuid("64000000", 7);
        UUID otherYearId = uuid("64000000", 8);
        insertSyntheticCashYear(academyYearId, academyOrganisationId, academySchoolId,
                academyMembershipId, "RLS_ACADEMY");
        insertSyntheticCashYear(otherYearId, otherOrganisationId, otherSchoolId,
                otherMembershipId, "RLS_OTHER");
        UUID academyConfigurationId = uuid("64000000", 9);
        UUID otherConfigurationId = uuid("64000000", 10);
        insertSyntheticFeeAccountingConfiguration(academyConfigurationId,
                academyOrganisationId, academySchoolId, academyYearId,
                academyMembershipId);
        insertSyntheticFeeAccountingConfiguration(otherConfigurationId,
                otherOrganisationId, otherSchoolId, otherYearId, otherMembershipId);

        jdbc.execute("CREATE ROLE educore_fee_accounting_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_fee_accounting_rls_test");
        jdbc.execute("""
                GRANT SELECT,UPDATE ON fee_accounting_configurations
                TO educore_fee_accounting_rls_test
                """);
        jdbc.execute("SET LOCAL ROLE educore_fee_accounting_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisationId.toString());
        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM fee_accounting_configurations", Integer.class);
        int crossTenantUpdate = jdbc.update("""
                UPDATE fee_accounting_configurations SET updated_at=now() WHERE id=?
                """, otherConfigurationId);

        assertThat(visible).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
    }

    @Test
    void appliesV031GovernedPayrollConfigurationWithRequiredGuards() {
        var v031 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V031__governed_payroll_configuration.sql"
                        .equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'payroll_components','staff_salary_profiles',
                  'staff_salary_profile_lines','staff_salary_profile_history')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN (
                  'payroll_components','staff_salary_profiles',
                  'staff_salary_profile_lines','staff_salary_profile_history')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'staff_salary_profiles_validate_context','staff_salary_profile_lines_guard',
                  'staff_salary_profiles_guard_history','staff_salary_profile_history_immutable',
                  'staff_salary_profiles_no_delete','payroll_components_no_delete')
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code IN (
                  'payroll.read','payroll.configure','payroll.approve')
                """, Integer.class);

        assertThat(v031).isPresent();
        assertThat(tableCount).isEqualTo(4);
        assertThat(policyCount).isEqualTo(4);
        assertThat(guardCount).isEqualTo(6);
        assertThat(capabilityCount).isEqualTo(3);
    }

    @Test
    @Transactional
    void payrollProfilesAreScopedCalculatedMakerCheckerImmutableAndRetryableAfterRejection() {
        RolloverFixture fixture = insertAcademicRolloverFixture();
        UUID staffId = jdbc.queryForObject(
                "SELECT id FROM staff_profiles WHERE user_account_id=?", UUID.class,
                fixture.teacherUserId());
        UUID approverUserId = uuid("65000000", 1);
        UUID approverMembershipId = uuid("65000000", 2);
        insertSyntheticUser(approverUserId, "payroll-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId,
                fixture.organisationId(), fixture.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, UUID.fromString("00000000-0000-4000-8000-000000000007"));

        AuthenticatedUser maker = new AuthenticatedUser(
                fixture.actorUserId(), UUID.randomUUID(), "rollover-admin@example.test",
                "rollover-admin@example.test", "Synthetic payroll maker", true,
                fixture.organisationId(), Set.of(fixture.actorMembershipId()),
                Set.of("payroll.read", "payroll.configure", "payroll.approve"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(
                approverUserId, UUID.randomUUID(), "payroll-approver@example.test",
                "payroll-approver@example.test", "Synthetic payroll approver", true,
                fixture.organisationId(), Set.of(approverMembershipId),
                Set.of("payroll.read", "payroll.approve"), List.of());

        var basic = payroll.createComponent(maker, new PayrollController.ComponentRequest(
                fixture.schoolId(), "BASIC", "Basic salary", "EARNING", "FIXED",
                true, true, false, "Synthetic basic earning", 10, "ACTIVE", null,
                "Synthetic payroll configuration"));
        var allowance = payroll.createComponent(maker, new PayrollController.ComponentRequest(
                fixture.schoolId(), "HOUSE_ALLOWANCE", "House allowance", "EARNING",
                "PERCENT_OF_BASIC", false, false, false,
                "Synthetic configurable allowance", 20, "ACTIVE", null,
                "Synthetic payroll configuration"));
        var deduction = payroll.createComponent(maker, new PayrollController.ComponentRequest(
                fixture.schoolId(), "STAFF_RECOVERY", "Staff recovery", "DEDUCTION", "FIXED",
                false, false, false, "Synthetic configurable deduction", 30, "ACTIVE", null,
                "Synthetic payroll configuration"));
        LocalDate initialDate = LocalDate.of(2025, 4, 1);
        var initialLines = List.of(
                new PayrollController.SalaryLineRequest(
                        basic.id(), new BigDecimal("30000.00"), null),
                new PayrollController.SalaryLineRequest(
                        allowance.id(), null, new BigDecimal("20.0000")),
                new PayrollController.SalaryLineRequest(
                        deduction.id(), new BigDecimal("1000.00"), null));
        var initial = payroll.createSalaryProfile(maker,
                new PayrollController.SalaryProfileRequest(
                        fixture.schoolId(), staffId, null, 1, initialDate, "INR", "MONTHLY",
                        initialLines, null, "Synthetic initial salary terms"));
        var submitted = payroll.submitSalaryProfile(maker, initial.id(),
                new PayrollController.VersionedReasonRequest(
                        fixture.schoolId(), initial.rowVersion(), "Synthetic independent review"));
        assertThatThrownBy(() -> payroll.decideSalaryProfile(maker, initial.id(),
                new PayrollController.DecisionRequest(
                        fixture.schoolId(), "APPROVE", "Synthetic self approval attempt",
                        submitted.rowVersion())))
                .hasMessageContaining("different authorized person");
        payroll.decideSalaryProfile(approver, initial.id(),
                new PayrollController.DecisionRequest(
                        fixture.schoolId(), "APPROVE", "Synthetic independent approval",
                        submitted.rowVersion()));

        var firstSnapshot = payroll.snapshot(approver, fixture.schoolId());
        assertThat(firstSnapshot.staff()).extracting(item -> item.id()).contains(staffId);
        assertThat(firstSnapshot.salaryProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.status()).isEqualTo("APPROVED");
            assertThat(profile.estimatedGross()).isEqualByComparingTo("36000.00");
            assertThat(profile.estimatedDeductions()).isEqualByComparingTo("1000.00");
            assertThat(profile.estimatedNet()).isEqualByComparingTo("35000.00");
            assertThat(profile.history()).hasSize(3);
        });
        assertThatThrownBy(() -> payroll.snapshot(maker, UUID.randomUUID()))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payroll.createSalaryProfile(maker,
                new PayrollController.SalaryProfileRequest(
                        fixture.schoolId(), staffId, initial.id(), 2,
                        LocalDate.of(2025, 7, 1), "INR", "MONTHLY",
                        List.of(
                                new PayrollController.SalaryLineRequest(
                                        basic.id(), new BigDecimal("1000.00"), null),
                                new PayrollController.SalaryLineRequest(
                                        deduction.id(), new BigDecimal("1001.00"), null)),
                        null, "Synthetic invalid salary terms")))
                .hasMessageContaining("cannot exceed earnings");

        var revisionRequest = new PayrollController.SalaryProfileRequest(
                fixture.schoolId(), staffId, initial.id(), 2, LocalDate.of(2025, 7, 1),
                "INR", "MONTHLY", List.of(
                        new PayrollController.SalaryLineRequest(
                                basic.id(), new BigDecimal("32000.00"), null),
                        new PayrollController.SalaryLineRequest(
                                allowance.id(), null, new BigDecimal("20.0000")),
                        new PayrollController.SalaryLineRequest(
                                deduction.id(), new BigDecimal("1200.00"), null)),
                null, "Synthetic revised salary terms");
        var rejectedDraft = payroll.createSalaryProfile(maker, revisionRequest);
        var rejectedSubmission = payroll.submitSalaryProfile(maker, rejectedDraft.id(),
                new PayrollController.VersionedReasonRequest(
                        fixture.schoolId(), rejectedDraft.rowVersion(), "Synthetic review submission"));
        payroll.decideSalaryProfile(approver, rejectedDraft.id(),
                new PayrollController.DecisionRequest(
                        fixture.schoolId(), "REJECT", "Synthetic correction required",
                        rejectedSubmission.rowVersion()));

        var correctedDraft = payroll.createSalaryProfile(maker, revisionRequest);
        var correctedSubmission = payroll.submitSalaryProfile(maker, correctedDraft.id(),
                new PayrollController.VersionedReasonRequest(
                        fixture.schoolId(), correctedDraft.rowVersion(), "Synthetic corrected review"));
        payroll.decideSalaryProfile(approver, correctedDraft.id(),
                new PayrollController.DecisionRequest(
                        fixture.schoolId(), "APPROVE", "Synthetic corrected approval",
                        correctedSubmission.rowVersion()));

        var finalSnapshot = payroll.snapshot(approver, fixture.schoolId());
        assertThat(finalSnapshot.salaryProfiles()).extracting(item -> item.status())
                .containsExactlyInAnyOrder("APPROVED", "REJECTED", "SUPERSEDED");
        Integer currentProfiles = jdbc.queryForObject("""
                SELECT count(*) FROM staff_salary_profiles
                WHERE organisation_id=? AND school_id=? AND staff_profile_id=? AND status='APPROVED'
                """, Integer.class, fixture.organisationId(), fixture.schoolId(), staffId);
        assertThat(currentProfiles).isEqualTo(1);
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE staff_salary_profile_lines SET fixed_amount=1 WHERE salary_profile_id=?",
                correctedDraft.id()), "editable only while the profile is a draft");
        assertDatabaseRejected(() -> jdbc.update(
                "DELETE FROM staff_salary_profiles WHERE id=?", correctedDraft.id()),
                "append-only");
    }

    @Test
    @Transactional
    void payrollComponentsRemainOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID academyUserId = uuid("66000000", 1);
        UUID academyMembershipId = uuid("66000000", 2);
        insertSyntheticUser(academyUserId, "payroll-rls-academy@example.test");
        insertSyntheticMembership(
                academyMembershipId, academyUserId, academyOrganisationId, academySchoolId);

        UUID otherOrganisationId = uuid("66000000", 3);
        UUID otherSchoolId = uuid("66000000", 4);
        UUID otherUserId = uuid("66000000", 5);
        UUID otherMembershipId = uuid("66000000", 6);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'payroll-isolation-org', 'Synthetic payroll isolation org',
                  'Synthetic payroll isolation org', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'payroll-isolation-school', 'Synthetic payroll isolation school',
                  'Synthetic payroll isolation school', now(), now(), 0)
                """, otherSchoolId, otherOrganisationId);
        insertSyntheticUser(otherUserId, "payroll-rls-other@example.test");
        insertSyntheticMembership(
                otherMembershipId, otherUserId, otherOrganisationId, otherSchoolId);

        UUID academyComponentId = uuid("66000000", 7);
        UUID otherComponentId = uuid("66000000", 8);
        jdbc.update("""
                INSERT INTO payroll_components(
                  id,organisation_id,school_id,code,display_name,component_kind,
                  calculation_method,is_basic,attendance_prorated,statutory,display_order,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'RLS_ALLOWANCE', 'Synthetic academy allowance', 'EARNING',
                  'FIXED', false, false, false, 10, 'ACTIVE', ?, now(), now(), 0)
                """, academyComponentId, academyOrganisationId, academySchoolId,
                academyMembershipId);
        jdbc.update("""
                INSERT INTO payroll_components(
                  id,organisation_id,school_id,code,display_name,component_kind,
                  calculation_method,is_basic,attendance_prorated,statutory,display_order,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'RLS_ALLOWANCE', 'Synthetic other allowance', 'EARNING',
                  'FIXED', false, false, false, 10, 'ACTIVE', ?, now(), now(), 0)
                """, otherComponentId, otherOrganisationId, otherSchoolId,
                otherMembershipId);

        jdbc.execute("CREATE ROLE educore_payroll_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_payroll_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON payroll_components TO educore_payroll_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_payroll_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisationId.toString());

        Integer visibleComponents = jdbc.queryForObject(
                "SELECT count(*) FROM payroll_components", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE payroll_components SET updated_at=now() WHERE id=?", otherComponentId);

        assertThat(visibleComponents).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
    }

    @Test
    void appliesV032GovernedMonthlyPayrollRunsWithRequiredGuards() {
        var v032 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V032__governed_monthly_payroll_runs.sql"
                        .equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'payroll_run_policies','payroll_runs','payroll_run_staff_results',
                  'payroll_run_staff_lines','payroll_run_attendance_sources','payroll_run_history')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN (
                  'payroll_run_policies','payroll_runs','payroll_run_staff_results',
                  'payroll_run_staff_lines','payroll_run_attendance_sources','payroll_run_history')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'payroll_run_staff_results_immutable','payroll_run_staff_lines_immutable',
                  'payroll_run_attendance_sources_immutable','payroll_runs_guard_history',
                  'payroll_runs_no_delete','payroll_run_history_immutable',
                  'payroll_run_policies_no_delete')
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code IN (
                  'payroll.run.prepare','payroll.run.approve')
                """, Integer.class);

        assertThat(v032).isPresent();
        assertThat(tableCount).isEqualTo(6);
        assertThat(policyCount).isEqualTo(6);
        assertThat(guardCount).isEqualTo(7);
        assertThat(capabilityCount).isEqualTo(2);
    }

    @Test
    @Transactional
    void payrollRunsArePreviewBoundAttendanceProratedRetrySafeMakerCheckerAndImmutable() {
        RolloverFixture fixture = insertAcademicRolloverFixture();
        UUID staffId = jdbc.queryForObject(
                "SELECT id FROM staff_profiles WHERE user_account_id=?", UUID.class,
                fixture.teacherUserId());
        UUID approverUserId = uuid("67000000", 1);
        UUID approverMembershipId = uuid("67000000", 2);
        insertSyntheticUser(approverUserId, "payroll-run-approver@example.test");
        insertSyntheticMembership(approverMembershipId, approverUserId,
                fixture.organisationId(), fixture.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembershipId, UUID.fromString("00000000-0000-4000-8000-000000000007"));

        AuthenticatedUser maker = new AuthenticatedUser(
                fixture.actorUserId(), UUID.randomUUID(), "payroll-run-maker@example.test",
                "payroll-run-maker@example.test", "Synthetic payroll run maker", true,
                fixture.organisationId(), Set.of(fixture.actorMembershipId()),
                Set.of("payroll.read", "payroll.configure", "payroll.approve",
                        "payroll.run.prepare", "payroll.run.approve", "workforce.configure",
                        "workforce.attendance.record"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(
                approverUserId, UUID.randomUUID(), "payroll-run-approver@example.test",
                "payroll-run-approver@example.test", "Synthetic payroll run approver", true,
                fixture.organisationId(), Set.of(approverMembershipId),
                Set.of("payroll.read", "payroll.run.approve"), List.of());

        var basic = payroll.createComponent(maker, new PayrollController.ComponentRequest(
                fixture.schoolId(), "RUN_BASIC", "Run basic salary", "EARNING", "FIXED",
                true, true, false, "Synthetic attendance-prorated basic", 10, "ACTIVE", null,
                "Synthetic payroll run configuration"));
        var allowance = payroll.createComponent(maker, new PayrollController.ComponentRequest(
                fixture.schoolId(), "RUN_ALLOWANCE", "Run fixed allowance", "EARNING", "FIXED",
                false, false, false, "Synthetic non-prorated allowance", 20, "ACTIVE", null,
                "Synthetic payroll run configuration"));
        var deduction = payroll.createComponent(maker, new PayrollController.ComponentRequest(
                fixture.schoolId(), "RUN_RECOVERY", "Run staff recovery", "DEDUCTION", "FIXED",
                false, false, false, "Synthetic fixed deduction", 30, "ACTIVE", null,
                "Synthetic payroll run configuration"));
        var salary = payroll.createSalaryProfile(maker,
                new PayrollController.SalaryProfileRequest(
                        fixture.schoolId(), staffId, null, 1, LocalDate.of(2025, 4, 1),
                        "INR", "MONTHLY", List.of(
                                new PayrollController.SalaryLineRequest(
                                        basic.id(), new BigDecimal("30000.00"), null),
                                new PayrollController.SalaryLineRequest(
                                        allowance.id(), new BigDecimal("2000.00"), null),
                                new PayrollController.SalaryLineRequest(
                                        deduction.id(), new BigDecimal("1000.00"), null)),
                        null, "Synthetic payroll run salary terms"));
        var salarySubmission = payroll.submitSalaryProfile(maker, salary.id(),
                new PayrollController.VersionedReasonRequest(
                        fixture.schoolId(), salary.rowVersion(), "Synthetic salary review"));
        payroll.decideSalaryProfile(approver, salary.id(),
                new PayrollController.DecisionRequest(
                        fixture.schoolId(), "APPROVE", "Synthetic salary approval",
                        salarySubmission.rowVersion()));

        workforce.createPolicy(maker, new PolicyRequest(
                fixture.schoolId(), fixture.sourceSessionId(), fixture.campusId(),
                "Synthetic payroll attendance", List.of("PRESENT", "ABSENT", "LEAVE"),
                java.util.Map.of("PRESENT", BigDecimal.ONE, "ABSENT", BigDecimal.ZERO,
                        "LEAVE", BigDecimal.ZERO),
                24, new BigDecimal("75.00"), 3, "ACTIVE", null,
                "Synthetic payroll attendance configuration"));
        List<LocalDate> dates = List.of(LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 5));
        List<String> states = List.of("PRESENT", "ABSENT");
        for (int index = 0; index < dates.size(); index++) {
            UUID dayId = uuid("67000000", 10 + index);
            jdbc.update("""
                    INSERT INTO school_calendar_days(id,organisation_id,school_id,campus_id,
                      academic_session_id,calendar_date,day_type,display_name,attendance_required,
                      status,created_at,updated_at,row_version)
                    VALUES (?, ?, ?, ?, ?, ?,'INSTRUCTIONAL','Synthetic payroll day',true,
                      'ACTIVE',now(),now(),0)
                    """, dayId, fixture.organisationId(), fixture.schoolId(), fixture.campusId(),
                    fixture.sourceSessionId(), dates.get(index));
            var opened = workforce.openRegister(maker,
                    new OpenRegisterRequest(fixture.schoolId(), dayId));
            var recorded = workforce.recordMarks(maker, opened.id(), new RecordMarksRequest(
                    fixture.schoolId(), opened.rowVersion(),
                    List.of(new MarkInput(staffId, states.get(index), null, null, null, null)),
                    "Synthetic payroll attendance evidence"));
            workforce.submitRegister(maker, opened.id(), new VersionedReasonRequest(
                    fixture.schoolId(), recorded.rowVersion(),
                    "Synthetic submitted payroll attendance"));
        }

        var policy = payrollRuns.createPolicy(maker, new PayrollRunController.RunPolicyRequest(
                fixture.schoolId(), "Synthetic monthly attendance payroll", "INR", "FULL_PAY",
                6, "ACTIVE", null, "Synthetic governed run policy"));
        LocalDate payrollMonth = LocalDate.of(2025, 8, 1);
        var stalePreview = payrollRuns.preview(
                maker, fixture.schoolId(), payrollMonth, "payroll.run.prepare");
        payrollRuns.updatePolicy(maker, policy.id(), new PayrollRunController.RunPolicyRequest(
                fixture.schoolId(), "Synthetic monthly attendance payroll", "INR", "FULL_PAY",
                5, "ACTIVE", policy.rowVersion(), "Synthetic precision review"));
        assertThatThrownBy(() -> payrollRuns.prepare(maker,
                new PayrollRunController.PrepareRunRequest(
                        fixture.schoolId(), payrollMonth, stalePreview.inputFingerprint(),
                        "synthetic-payroll-run-stale", "Synthetic stale preview attempt")))
                .hasMessageContaining("changed after preview");

        var preview = payrollRuns.preview(
                maker, fixture.schoolId(), payrollMonth, "payroll.run.prepare");
        assertThat(preview.ready()).isTrue();
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.staffCount()).isEqualTo(1);
        assertThat(preview.scheduledWorkingDays()).isEqualTo(2);
        assertThat(preview.recordedDays()).isEqualTo(2);
        assertThat(preview.payableDays()).isEqualByComparingTo("1.0000");
        assertThat(preview.fullGross()).isEqualByComparingTo("32000.00");
        assertThat(preview.payableGross()).isEqualByComparingTo("17000.00");
        assertThat(preview.deductions()).isEqualByComparingTo("1000.00");
        assertThat(preview.net()).isEqualByComparingTo("16000.00");

        var prepareRequest = new PayrollRunController.PrepareRunRequest(
                fixture.schoolId(), payrollMonth, preview.inputFingerprint(),
                "synthetic-payroll-run-2025-08", "Synthetic reviewed monthly payroll");
        var prepared = payrollRuns.prepare(maker, prepareRequest);
        var retry = payrollRuns.prepare(maker, prepareRequest);
        assertThat(retry.id()).isEqualTo(prepared.id());
        assertThat(retry.rowVersion()).isEqualTo(prepared.rowVersion());
        assertThat(jdbc.queryForMap("""
                SELECT assignment_snapshot_status,assignment_snapshot_date,designation_name_snapshot
                FROM payroll_run_staff_results WHERE payroll_run_id=?
                """, prepared.id()))
                .containsEntry("assignment_snapshot_status", "CAPTURED")
                .containsEntry("designation_name_snapshot", "Synthetic Rollover Teacher");
        jdbc.update("UPDATE staff_designations SET display_name='Synthetic renamed designation' WHERE id=?",
                uuid("50000000", 12));
        assertThat(payrollRuns.prepare(maker, prepareRequest).id()).isEqualTo(prepared.id());
        assertThat(jdbc.queryForObject("SELECT designation_name_snapshot FROM payroll_run_staff_results WHERE payroll_run_id=?",
                String.class, prepared.id())).isEqualTo("Synthetic Rollover Teacher");

        var workspace = payrollRuns.workspace(maker, fixture.schoolId());
        assertThat(workspace.runs()).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("DRAFT");
            assertThat(run.canSubmit()).isTrue();
            assertThat(run.canCancel()).isTrue();
            assertThat(run.canDecide()).isFalse();
            assertThat(run.net()).isEqualByComparingTo("16000.00");
            assertThat(run.staff()).singleElement().satisfies(result -> {
                assertThat(result.prorationFactor()).isEqualByComparingTo("0.500000");
                assertThat(result.lines()).hasSize(3);
            });
            assertThat(run.history()).singleElement()
                    .satisfies(history -> assertThat(history.eventType()).isEqualTo("PREPARED"));
        });
        assertThatThrownBy(() -> payrollRuns.workspace(maker, UUID.randomUUID()))
                .hasMessageContaining("unavailable for this school");

        UUID submitterUser = UUID.randomUUID(), submitterMembership = UUID.randomUUID();
        insertSyntheticUser(submitterUser, "synthetic-run-submitter@example.test");
        insertSyntheticMembership(submitterMembership, submitterUser, fixture.organisationId(), fixture.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                submitterMembership, UUID.fromString("00000000-0000-4000-8000-000000000001"));
        var submitter = payrollAccountingActor(submitterUser, submitterMembership, fixture.organisationId());
        var submitted = payrollRuns.submit(submitter, prepared.id(),
                new PayrollRunController.RunTransitionRequest(
                        fixture.schoolId(), prepared.rowVersion(), "Synthetic submission review"));
        assertThat(payrollRuns.workspace(maker, fixture.schoolId()).runs())
                .singleElement().satisfies(run -> assertThat(run.canDecide()).isFalse());
        assertThat(payrollRuns.workspace(approver, fixture.schoolId()).runs())
                .singleElement().satisfies(run -> assertThat(run.canDecide()).isTrue());
        assertThatThrownBy(() -> payrollRuns.decide(maker, prepared.id(),
                new PayrollRunController.RunDecisionRequest(
                        fixture.schoolId(), "FINALIZE", submitted.rowVersion(),
                        "Synthetic self finalization attempt")))
                .hasMessageContaining("cannot make its final decision");
        UUID alternateMakerMembership = alternatePayrollMembership(fixture.actorMembershipId(), fixture.campusId());
        var alternateMaker = payrollAccountingActor(fixture.actorUserId(), alternateMakerMembership, fixture.organisationId());
        UUID alternateSubmitterMembership = alternatePayrollMembership(submitterMembership, fixture.campusId());
        var alternateSubmitter = payrollAccountingActor(submitterUser, alternateSubmitterMembership, fixture.organisationId());
        for (var contributor : List.of(alternateMaker, submitter, alternateSubmitter)) {
            assertThat(payrollRuns.workspace(contributor, fixture.schoolId()).runs())
                    .singleElement().satisfies(run -> assertThat(run.canDecide()).isFalse());
            for (String decision : List.of("FINALIZE", "REJECT")) {
                assertThatThrownBy(() -> payrollRuns.decide(contributor, prepared.id(),
                        new PayrollRunController.RunDecisionRequest(fixture.schoolId(), decision,
                                submitted.rowVersion(), "Synthetic person-level self decision")))
                        .hasMessageContaining("cannot make its final decision");
            }
        }
        for (UUID contributor : List.of(alternateMakerMembership, submitterMembership, alternateSubmitterMembership)) {
            assertDatabaseRejected(() -> jdbc.update("""
                    UPDATE payroll_runs SET status='REJECTED',decided_by_membership_id=?,
                      decided_at=now(),decision_reason='Synthetic direct self decision',
                      updated_at=now(),row_version=row_version+1 WHERE id=?
                    """, contributor, prepared.id()), "independent person");
        }
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO payroll_runs
                SELECT (jsonb_populate_record(NULL::payroll_runs,to_jsonb(run)||jsonb_build_object(
                  'id',CAST(? AS text),'status','FINALIZED','decided_by_membership_id',CAST(? AS text),
                  'decided_at',now(),'decision_reason','Synthetic inserted self decision'))).*
                FROM payroll_runs run WHERE id=?
                """, UUID.randomUUID(), alternateMakerMembership, prepared.id()), "independent person");
        // Ending the earlier access assignment must not erase the contributor's identity.
        jdbc.update("UPDATE memberships SET status='ENDED',updated_at=now(),row_version=row_version+1 WHERE id=?",
                submitterMembership);
        assertThatThrownBy(() -> payrollRuns.decide(alternateSubmitter, prepared.id(),
                new PayrollRunController.RunDecisionRequest(fixture.schoolId(), "REJECT",
                        submitted.rowVersion(), "Synthetic ended contributor membership")))
                .hasMessageContaining("cannot make its final decision");
        var finalized = payrollRuns.decide(approver, prepared.id(),
                new PayrollRunController.RunDecisionRequest(
                        fixture.schoolId(), "FINALIZE", submitted.rowVersion(),
                        "Synthetic independent payroll finalization"));
        assertThat(finalized.rowVersion()).isEqualTo(2);
        assertThat(payrollRuns.workspace(approver, fixture.schoolId()).runs())
                .singleElement().satisfies(run -> {
                    assertThat(run.status()).isEqualTo("FINALIZED");
                    assertThat(run.history()).extracting(item -> item.eventType())
                            .containsExactly("PREPARED", "SUBMITTED", "FINALIZED");
                });
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_run_staff_results SET net_amount=1 WHERE payroll_run_id=?
                """, prepared.id()), "immutable");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_run_staff_results SET designation_name_snapshot='Synthetic changed' WHERE payroll_run_id=?
                """, prepared.id()), "immutable");
        var reportActor = payrollAccountingActor(fixture.actorUserId(), fixture.actorMembershipId(), fixture.organisationId());
        assertThat(payrollReports.register(reportActor, fixture.schoolId(),
                new com.educore.api.payroll.PayrollReports.Query(payrollMonth, payrollMonth, null, "", 0, 25)).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.assignmentSnapshotStatus()).isEqualTo("CAPTURED");
                    assertThat(row.assignmentSnapshotDate()).isEqualTo(LocalDate.of(2025, 8, 31));
                    assertThat(row.designationName()).isEqualTo("Synthetic Rollover Teacher");
                    assertThat(row.net()).isEqualByComparingTo("16000.00");
                });
        var reportQuery = new com.educore.api.payroll.PayrollReports.Query(
                payrollMonth, payrollMonth, null, "", 0, 25);
        assertThat(payrollReports.summary(reportActor, fixture.schoolId(), reportQuery,
                com.educore.api.payroll.PayrollReports.GroupBy.DESIGNATION).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.dimensionStatus()).isEqualTo("CAPTURED");
                    assertThat(row.dimensionLabel()).isEqualTo("Synthetic Rollover Teacher");
                    assertThat(row.runStatus()).isEqualTo("FINALIZED");
                    assertThat(row.currencyCode()).isEqualTo("INR");
                    assertThat(row.gross()).isEqualByComparingTo("17000.00");
                    assertThat(row.deductions()).isEqualByComparingTo("1000.00");
                    assertThat(row.net()).isEqualByComparingTo("16000.00");
                });
        assertThat(payrollReports.summary(reportActor, fixture.schoolId(), reportQuery,
                com.educore.api.payroll.PayrollReports.GroupBy.DEPARTMENT).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.dimensionStatus()).isEqualTo("NO_DEPARTMENT");
                    assertThat(row.dimensionLabel()).isEqualTo("No department recorded");
                });
        assertThat(payrollReports.summary(reportActor, fixture.schoolId(), reportQuery,
                com.educore.api.payroll.PayrollReports.GroupBy.DEDUCTION_COMPONENT).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.dimensionStatus()).isEqualTo("CAPTURED");
                    assertThat(row.dimensionCode()).isEqualTo("RUN_RECOVERY");
                    assertThat(row.dimensionLabel()).isEqualTo("Run staff recovery");
                    assertThat(row.deductions()).isEqualByComparingTo("1000.00");
                    assertThat(row.gross()).isNull();
                    assertThat(row.employerContributions()).isNull();
                    assertThat(row.net()).isNull();
                });
        assertDatabaseRejected(() -> jdbc.update(
                "DELETE FROM payroll_runs WHERE id=?", prepared.id()), "append-only");
    }

    @Test
    @Transactional
    void payrollRunPoliciesRemainOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID academyUserId = uuid("68000000", 1);
        UUID academyMembershipId = uuid("68000000", 2);
        insertSyntheticUser(academyUserId, "payroll-run-rls-academy@example.test");
        insertSyntheticMembership(academyMembershipId, academyUserId,
                academyOrganisationId, academySchoolId);

        UUID otherOrganisationId = uuid("68000000", 3);
        UUID otherSchoolId = uuid("68000000", 4);
        UUID otherUserId = uuid("68000000", 5);
        UUID otherMembershipId = uuid("68000000", 6);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'payroll-run-isolation-org', 'Synthetic payroll run isolation org',
                  'Synthetic payroll run isolation org', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, otherOrganisationId);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'payroll-run-isolation-school', 'Synthetic payroll run isolation school',
                  'Synthetic payroll run isolation school', now(), now(), 0)
                """, otherSchoolId, otherOrganisationId);
        insertSyntheticUser(otherUserId, "payroll-run-rls-other@example.test");
        insertSyntheticMembership(otherMembershipId, otherUserId,
                otherOrganisationId, otherSchoolId);

        UUID academyPolicyId = uuid("68000000", 7);
        UUID otherPolicyId = uuid("68000000", 8);
        jdbc.update("""
                INSERT INTO payroll_run_policies(id,organisation_id,school_id,display_name,
                  currency_code,zero_scheduled_days_action,proration_scale,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic academy run policy','INR','FULL_PAY',6,'ACTIVE',
                  ?,now(),now(),0)
                """, academyPolicyId, academyOrganisationId, academySchoolId, academyMembershipId);
        jdbc.update("""
                INSERT INTO payroll_run_policies(id,organisation_id,school_id,display_name,
                  currency_code,zero_scheduled_days_action,proration_scale,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic other run policy','INR','FULL_PAY',6,'ACTIVE',
                  ?,now(),now(),0)
                """, otherPolicyId, otherOrganisationId, otherSchoolId, otherMembershipId);

        jdbc.execute("CREATE ROLE educore_payroll_run_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_payroll_run_rls_test");
        jdbc.execute("GRANT SELECT,UPDATE ON payroll_run_policies TO educore_payroll_run_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_payroll_run_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisationId.toString());

        Integer visible = jdbc.queryForObject("SELECT count(*) FROM payroll_run_policies", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE payroll_run_policies SET updated_at=now() WHERE id=?", otherPolicyId);

        assertThat(visible).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
    }

    @Test
    void appliesV033PrivatePayrollPayslipsWithRequiredGuards() {
        var v033 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V033__private_payroll_payslips.sql"
                        .equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'payroll_payslip_batches','payroll_payslips')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN (
                  'payroll_payslip_batches','payroll_payslips')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'payroll_payslip_batches_immutable','payroll_payslips_immutable',
                  'payroll_payslip_batches_complete')
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code IN (
                  'payroll.payslip.generate','payroll.payslip.self')
                """, Integer.class);

        assertThat(v033).isPresent();
        assertThat(tableCount).isEqualTo(2);
        assertThat(policyCount).isEqualTo(2);
        assertThat(guardCount).isEqualTo(3);
        assertThat(capabilityCount).isEqualTo(2);
    }

    @Test
    @Transactional
    void payrollPayslipsAreFinalizedRetrySafePrivateAndOwnRecordScoped() {
        PayslipFixture fixture = insertFinalizedPayslipFixture();
        when(payrollPayslipObjectStore.bucket()).thenReturn("synthetic-private-bucket");
        AuthenticatedUser administrator = new AuthenticatedUser(
                fixture.rollover().actorUserId(), UUID.randomUUID(), "payslip-admin@example.test",
                "payslip-admin@example.test", "Synthetic payslip administrator", true,
                fixture.rollover().organisationId(), Set.of(fixture.rollover().actorMembershipId()),
                Set.of("payroll.read", "payroll.payslip.generate"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                fixture.rollover().teacherUserId(), UUID.randomUUID(), "rollover-teacher@example.test",
                "rollover-teacher@example.test", "Synthetic payslip teacher", true,
                fixture.rollover().organisationId(), Set.of(fixture.rollover().teacherMembershipId()),
                Set.of("payroll.payslip.self"), List.of());
        var request = new PayrollPayslipController.GeneratePayslipsRequest(
                fixture.rollover().schoolId(), "hi-IN", "Synthetic finalized salary delivery");

        var generated = payrollPayslips.generate(
                administrator, fixture.runId(), "synthetic-payslip-batch-2025-08", request);
        var retry = payrollPayslips.generate(
                administrator, fixture.runId(), "synthetic-payslip-batch-2025-08", request);

        assertThat(generated.generatedCount()).isEqualTo(1);
        assertThat(generated.existing()).isFalse();
        assertThat(retry.id()).isEqualTo(generated.id());
        assertThat(retry.existing()).isTrue();
        assertThatThrownBy(() -> payrollPayslips.generate(administrator, fixture.runId(),
                "synthetic-payslip-other-request", request))
                .hasMessageContaining("already has its immutable payslip batch");
        assertThatThrownBy(() -> payrollPayslips.generate(teacher, fixture.runId(),
                "synthetic-payslip-teacher-request", request))
                .hasMessageContaining("outside your authorized role");

        var management = payrollPayslips.workspace(administrator, fixture.rollover().schoolId());
        assertThat(management.canReadAll()).isTrue();
        assertThat(management.canGenerate()).isTrue();
        assertThat(management.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.id()).isEqualTo(generated.id());
            assertThat(batch.payrollRunId()).isEqualTo(fixture.runId());
            assertThat(batch.locale()).isEqualTo("hi-IN");
        });
        assertThat(management.payslips()).singleElement().satisfies(payslip -> {
            assertThat(payslip.staffProfileId()).isEqualTo(fixture.staffId());
            assertThat(payslip.payslipNumber()).isEqualTo("PSL-202508-001");
            assertThat(payslip.net()).isEqualByComparingTo("20000.00");
        });

        var self = payrollPayslips.workspace(teacher, fixture.rollover().schoolId());
        assertThat(self.canReadAll()).isFalse();
        assertThat(self.batches()).isEmpty();
        assertThat(self.payslips()).singleElement()
                .satisfies(item -> assertThat(item.staffProfileId()).isEqualTo(fixture.staffId()));
        var download = payrollPayslips.download(
                teacher, fixture.rollover().schoolId(), self.payslips().getFirst().id());
        assertThat(download.filename()).isEqualTo("payslip-PSL-202508-001.pdf");
        assertThat(download.sizeBytes()).isGreaterThan(1_000);
        UUID verifiedPayslipId=self.payslips().getFirst().id();
        String recordedPayslipDigest=jdbc.queryForObject("SELECT pdf_sha256 FROM payroll_payslips WHERE id=?",String.class,verifiedPayslipId);
        var payslipOutput=new java.io.ByteArrayOutputStream();
        download.writer().writeTo(payslipOutput);
        org.mockito.Mockito.verify(payrollPayslipObjectStore).copyVerifiedTo(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(download.sizeBytes()),org.mockito.ArgumentMatchers.eq(recordedPayslipDigest),
                org.mockito.ArgumentMatchers.same(payslipOutput));
        org.mockito.Mockito.verify(payrollPayslipObjectStore,org.mockito.Mockito.never()).copyTo(
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());

        UUID unlinkedUser = uuid("69000000", 20);
        UUID unlinkedMembership = uuid("69000000", 21);
        insertSyntheticUser(unlinkedUser, "payslip-unlinked@example.test");
        insertSyntheticMembership(unlinkedMembership, unlinkedUser,
                fixture.rollover().organisationId(), fixture.rollover().schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                unlinkedMembership, UUID.fromString("00000000-0000-4000-8000-000000000003"));
        AuthenticatedUser unlinked = new AuthenticatedUser(
                unlinkedUser, UUID.randomUUID(), "payslip-unlinked@example.test",
                "payslip-unlinked@example.test", "Synthetic unlinked staff", true,
                fixture.rollover().organisationId(), Set.of(unlinkedMembership),
                Set.of("payroll.payslip.self"), List.of());
        assertThatThrownBy(() -> payrollPayslips.workspace(unlinked, fixture.rollover().schoolId()))
                .hasMessageContaining("not linked to an active staff profile");

        UUID payslipId = self.payslips().getFirst().id();
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE payroll_payslips SET pdf_size_bytes=pdf_size_bytes+1 WHERE id=?", payslipId),
                "append-only");
        assertDatabaseRejected(() -> jdbc.update(
                "DELETE FROM payroll_payslip_batches WHERE id=?", generated.id()), "append-only");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND action IN ('payroll.payslip.generated','payroll.payslip.downloaded')
                """, Integer.class, fixture.rollover().organisationId(),
                fixture.rollover().schoolId())).isEqualTo(2);
    }

    @Test
    @Transactional
    void payrollPayslipEvidenceRemainsOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchool = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID otherOrganisation = uuid("69100000", 1);
        UUID otherSchool = uuid("69100000", 2);
        UUID academyMaker = uuid("69100000", 3);
        UUID academyMakerMembership = uuid("69100000", 4);
        UUID academyApprover = uuid("69100000", 5);
        UUID academyApproverMembership = uuid("69100000", 6);
        UUID otherMaker = uuid("69100000", 7);
        UUID otherMakerMembership = uuid("69100000", 8);
        UUID otherApprover = uuid("69100000", 9);
        UUID otherApproverMembership = uuid("69100000", 10);
        insertSyntheticUser(academyMaker, "payslip-rls-academy-maker@example.test");
        insertSyntheticMembership(academyMakerMembership, academyMaker, academyOrganisation, academySchool);
        insertSyntheticUser(academyApprover, "payslip-rls-academy-approver@example.test");
        insertSyntheticMembership(academyApproverMembership, academyApprover,
                academyOrganisation, academySchool);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'payslip-rls-org', 'Synthetic payslip RLS org',
                  'Synthetic payslip RLS org','ACTIVE','en-IN',now(),now(),0)
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'payslip-rls-school', 'Synthetic payslip RLS school',
                  'Synthetic payslip RLS school',now(),now(),0)
                """, otherSchool, otherOrganisation);
        insertSyntheticUser(otherMaker, "payslip-rls-other-maker@example.test");
        insertSyntheticMembership(otherMakerMembership, otherMaker, otherOrganisation, otherSchool);
        insertSyntheticUser(otherApprover, "payslip-rls-other-approver@example.test");
        insertSyntheticMembership(otherApproverMembership, otherApprover, otherOrganisation, otherSchool);

        UUID academyPolicy = uuid("69100000", 11);
        UUID otherPolicy = uuid("69100000", 12);
        UUID academyRun = uuid("69100000", 13);
        UUID otherRun = uuid("69100000", 14);
        insertSyntheticFinalizedRunHeader(academyPolicy, academyRun, academyOrganisation,
                academySchool, academyMakerMembership, academyApproverMembership,
                "PAY-202601-001", "payslip-rls-academy-run");
        insertSyntheticFinalizedRunHeader(otherPolicy, otherRun, otherOrganisation,
                otherSchool, otherMakerMembership, otherApproverMembership,
                "PAY-202601-001", "payslip-rls-other-run");
        jdbc.queryForObject("SELECT set_config('app.payroll_payslip_generate','approved',true)",
                String.class);
        UUID academyBatch = uuid("69100000", 15);
        UUID otherBatch = uuid("69100000", 16);
        insertSyntheticPayslipBatch(academyBatch, academyRun, academyOrganisation, academySchool,
                academyMakerMembership, "payslip-rls-academy-batch");
        insertSyntheticPayslipBatch(otherBatch, otherRun, otherOrganisation, otherSchool,
                otherMakerMembership, "payslip-rls-other-batch");

        jdbc.execute("CREATE ROLE educore_payroll_payslip_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_payroll_payslip_rls_test");
        jdbc.execute("GRANT SELECT,UPDATE ON payroll_payslip_batches TO educore_payroll_payslip_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_payroll_payslip_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisation.toString());

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM payroll_payslip_batches", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE payroll_payslip_batches SET locale='en-IN' WHERE id=?", otherBatch);

        assertThat(visible).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT id FROM payroll_payslip_batches WHERE id=?", UUID.class, academyBatch))
                .isEqualTo(academyBatch);
    }

    @Test
    void appliesV034ControlledPayrollRunReversalWithRequiredGuards() {
        var v034 = Arrays.stream(flyway.info().applied())
                .filter(migration -> "V034__controlled_payroll_run_reversal.sql"
                        .equals(migration.getScript()))
                .findFirst();
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'payroll_run_reversal_requests','payroll_run_reversal_history')
                """, Integer.class);
        Integer policyCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN (
                  'payroll_run_reversal_requests','payroll_run_reversal_history')
                """, Integer.class);
        Integer guardCount = jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'payroll_run_reversal_requests_guard','payroll_run_reversal_history_immutable')
                """, Integer.class);
        Integer capabilityCount = jdbc.queryForObject("""
                SELECT count(*) FROM capabilities WHERE code IN (
                  'payroll.run.reverse.request','payroll.run.reverse.approve')
                """, Integer.class);
        Integer runColumnCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='payroll_runs' AND column_name IN (
                  'reversal_request_id','reversed_by_membership_id','reversed_at','reversal_reason')
                """, Integer.class);
        String statuses = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname='payroll_runs_status_valid'
                """, String.class);

        assertThat(v034).isPresent();
        assertThat(tableCount).isEqualTo(2);
        assertThat(policyCount).isEqualTo(2);
        assertThat(guardCount).isEqualTo(2);
        assertThat(capabilityCount).isEqualTo(2);
        assertThat(runColumnCount).isEqualTo(4);
        assertThat(statuses).contains("REVERSED");
    }

    @Test
    @Transactional
    void payrollRunReversalsAreMakerCheckerRetrySafeImmutableAndReleaseReplacementAttempt() {
        PayslipFixture fixture = insertFinalizedPayslipFixture();
        when(payrollPayslipObjectStore.bucket()).thenReturn("synthetic-private-bucket");
        AuthenticatedUser requester = new AuthenticatedUser(
                fixture.rollover().actorUserId(), UUID.randomUUID(), "payroll-reversal@example.test",
                "payroll-reversal@example.test", "Synthetic payroll reversal requester", true,
                fixture.rollover().organisationId(), Set.of(fixture.rollover().actorMembershipId()),
                Set.of("payroll.read", "payroll.payslip.generate",
                        "payroll.run.reverse.request", "payroll.run.reverse.approve"), List.of());
        AuthenticatedUser approver = new AuthenticatedUser(
                fixture.approverUserId(), UUID.randomUUID(), "payslip-approver@example.test",
                "payslip-approver@example.test", "Synthetic payroll reversal approver", true,
                fixture.rollover().organisationId(), Set.of(fixture.approverMembershipId()),
                Set.of("payroll.read", "payroll.run.reverse.approve"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                fixture.rollover().teacherUserId(), UUID.randomUUID(), "rollover-teacher@example.test",
                "rollover-teacher@example.test", "Synthetic payroll teacher", true,
                fixture.rollover().organisationId(), Set.of(fixture.rollover().teacherMembershipId()),
                Set.of("payroll.payslip.self"), List.of());
        var payslipBatch = payrollPayslips.generate(requester, fixture.runId(),
                "synthetic-reversal-payslip", new PayrollPayslipController.GeneratePayslipsRequest(
                        fixture.rollover().schoolId(), "en-IN", "Synthetic original payroll evidence"));
        var reversalRequest = new PayrollRunController.RunReversalRequest(
                fixture.rollover().schoolId(), 2L, "Synthetic finalized payroll correction");

        var requested = payrollRuns.requestReversal(requester, fixture.runId(),
                "synthetic-payroll-reversal", reversalRequest);
        var retry = payrollRuns.requestReversal(requester, fixture.runId(),
                "synthetic-payroll-reversal", reversalRequest);

        assertThat(retry).isEqualTo(requested);
        assertThatThrownBy(() -> payrollRuns.requestReversal(requester, fixture.runId(),
                "synthetic-payroll-reversal-other", reversalRequest))
                .hasMessageContaining("pending reversal request");
        assertThatThrownBy(() -> payrollRuns.decideReversal(requester, requested.id(),
                new PayrollRunController.RunReversalDecisionRequest(
                        fixture.rollover().schoolId(), "APPROVE", 0L,
                        "Synthetic self approval attempt")))
                .hasMessageContaining("requested reversal cannot make its decision");

        UUID alternateRequesterMembership = alternatePayrollMembership(
                fixture.rollover().actorMembershipId(), fixture.rollover().campusId());
        var alternateRequester = payrollAccountingActor(fixture.rollover().actorUserId(),
                alternateRequesterMembership, fixture.rollover().organisationId());
        assertThat(payrollRuns.workspace(alternateRequester, fixture.rollover().schoolId()).runs())
                .singleElement().satisfies(run -> {
                    assertThat(run.canDecideReversal()).isFalse();
                    assertThat(run.reversals()).singleElement()
                            .satisfies(item -> assertThat(item.canDecide()).isFalse());
                });
        for (String decision : List.of("APPROVE", "REJECT")) {
            assertThatThrownBy(() -> payrollRuns.decideReversal(alternateRequester, requested.id(),
                    new PayrollRunController.RunReversalDecisionRequest(fixture.rollover().schoolId(),
                            decision, 0L, "Synthetic alternate-membership reversal decision")))
                    .hasMessageContaining("requested reversal cannot make its decision");
        }
        assertDatabaseRejected(() -> {
            jdbc.queryForObject("SELECT set_config('app.payroll_run_reverse_decide','approved',true)", String.class);
            jdbc.update("""
                    UPDATE payroll_run_reversal_requests SET status='REJECTED',
                      decided_by_membership_id=?,decided_at=now(),
                      decision_reason='Synthetic direct alternate membership decision',
                      row_version=row_version+1 WHERE id=?
                    """, alternateRequesterMembership, requested.id());
        }, "independent person");
        payrollRuns.decideReversal(approver, requested.id(),
                new PayrollRunController.RunReversalDecisionRequest(
                        fixture.rollover().schoolId(), "APPROVE", 0L,
                        "Synthetic independent payroll reversal"));

        var workspace = payrollRuns.workspace(requester, fixture.rollover().schoolId());
        assertThat(workspace.runs()).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("REVERSED");
            assertThat(run.rowVersion()).isEqualTo(3);
            assertThat(run.canRequestReversal()).isFalse();
            assertThat(run.history()).extracting(item -> item.eventType())
                    .contains("REVERSED");
            assertThat(run.reversals()).singleElement().satisfies(reversal -> {
                assertThat(reversal.id()).isEqualTo(requested.id());
                assertThat(reversal.status()).isEqualTo("APPROVED");
                assertThat(reversal.canDecide()).isFalse();
            });
        });

        var self = payrollPayslips.workspace(teacher, fixture.rollover().schoolId());
        assertThat(self.payslips()).singleElement()
                .satisfies(item -> assertThat(item.runStatus()).isEqualTo("REVERSED"));
        UUID payslipId = self.payslips().getFirst().id();
        assertThatThrownBy(() -> payrollPayslips.download(
                teacher, fixture.rollover().schoolId(), payslipId))
                .hasMessageContaining("invalidated by an approved payroll reversal");
        var archived = payrollPayslips.download(
                requester, fixture.rollover().schoolId(), payslipId);
        assertThat(archived.filename()).isEqualTo(
                "reversed-evidence-payslip-PSL-202508-001.pdf");
        assertThat(payslipBatch.generatedCount()).isEqualTo(1);

        assertDatabaseRejected(() -> jdbc.update(
                "DELETE FROM payroll_run_reversal_requests WHERE id=?", requested.id()),
                "append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_run_reversal_history SET reason='Synthetic altered history'
                WHERE payroll_run_reversal_request_id=?
                """, requested.id()), "append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_runs SET status='FINALIZED',reversal_request_id=NULL,
                  reversed_by_membership_id=NULL,reversed_at=NULL,reversal_reason=NULL,
                  row_version=row_version+1 WHERE id=?
                """, fixture.runId()), "invalid payroll run transition");

        UUID replacementRun = uuid("69200000", 1);
        int inserted = jdbc.update("""
                INSERT INTO payroll_runs(
                  id,organisation_id,school_id,payroll_run_policy_id,policy_snapshot,payroll_month,
                  period_start,period_end,attempt_number,run_code,idempotency_key,input_fingerprint,
                  currency_code,status,staff_count,scheduled_working_days_total,recorded_days_total,
                  payable_days_total,full_gross_total,payable_gross_total,deduction_total,
                  employer_contribution_total,net_total,creation_reason,created_by_membership_id,
                  created_at,updated_at,row_version)
                SELECT ?,organisation_id,school_id,payroll_run_policy_id,policy_snapshot,payroll_month,
                  period_start,period_end,attempt_number+1,'PAY-202508-002',
                  'synthetic-payroll-replacement',input_fingerprint,currency_code,'DRAFT',staff_count,
                  scheduled_working_days_total,recorded_days_total,payable_days_total,
                  full_gross_total,payable_gross_total,deduction_total,
                  employer_contribution_total,net_total,'Synthetic replacement payroll attempt',
                  ?,now(),now(),0 FROM payroll_runs WHERE id=?
                """, replacementRun, fixture.rollover().actorMembershipId(), fixture.runId());
        assertThat(inserted).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payroll_runs WHERE school_id=? AND payroll_month=DATE '2025-08-01'
                """, Integer.class, fixture.rollover().schoolId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payroll_run_reversal_history
                WHERE payroll_run_reversal_request_id=?
                """, Integer.class, requested.id())).isEqualTo(2);
    }

    @Test
    @Transactional
    void payrollRunReversalEvidenceRemainsOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchool = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID otherOrganisation = uuid("69300000", 1);
        UUID otherSchool = uuid("69300000", 2);
        UUID academyMaker = uuid("69300000", 3);
        UUID academyMembership = uuid("69300000", 4);
        UUID academyApprover = uuid("69300000", 5);
        UUID academyApproverMembership = uuid("69300000", 6);
        UUID otherMaker = uuid("69300000", 7);
        UUID otherMembership = uuid("69300000", 8);
        UUID otherApprover = uuid("69300000", 9);
        UUID otherApproverMembership = uuid("69300000", 10);
        insertSyntheticUser(academyMaker, "reversal-rls-academy-maker@example.test");
        insertSyntheticMembership(academyMembership, academyMaker, academyOrganisation, academySchool);
        insertSyntheticUser(academyApprover, "reversal-rls-academy-approver@example.test");
        insertSyntheticMembership(academyApproverMembership, academyApprover,
                academyOrganisation, academySchool);
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'reversal-rls-org', 'Synthetic reversal RLS org',
                  'Synthetic reversal RLS org','ACTIVE','en-IN',now(),now(),0)
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,
                  created_at,updated_at,row_version)
                VALUES (?, ?, 'reversal-rls-school', 'Synthetic reversal RLS school',
                  'Synthetic reversal RLS school',now(),now(),0)
                """, otherSchool, otherOrganisation);
        insertSyntheticUser(otherMaker, "reversal-rls-other-maker@example.test");
        insertSyntheticMembership(otherMembership, otherMaker, otherOrganisation, otherSchool);
        insertSyntheticUser(otherApprover, "reversal-rls-other-approver@example.test");
        insertSyntheticMembership(otherApproverMembership, otherApprover,
                otherOrganisation, otherSchool);

        UUID academyRun = uuid("69300000", 11);
        UUID otherRun = uuid("69300000", 12);
        insertSyntheticFinalizedRunHeader(uuid("69300000", 13), academyRun,
                academyOrganisation, academySchool, academyMembership,
                academyApproverMembership, "PAY-202601-001", "reversal-rls-academy-run");
        insertSyntheticFinalizedRunHeader(uuid("69300000", 14), otherRun,
                otherOrganisation, otherSchool, otherMembership,
                otherApproverMembership, "PAY-202601-001", "reversal-rls-other-run");
        jdbc.queryForObject("SELECT set_config('app.payroll_run_reverse_request','approved',true)",
                String.class);
        UUID academyRequest = uuid("69300000", 15);
        UUID otherRequest = uuid("69300000", 16);
        insertSyntheticPayrollReversalRequest(academyRequest, academyRun, academyOrganisation,
                academySchool, academyMembership, "reversal-rls-academy-request");
        insertSyntheticPayrollReversalRequest(otherRequest, otherRun, otherOrganisation,
                otherSchool, otherMembership, "reversal-rls-other-request");

        jdbc.execute("CREATE ROLE educore_payroll_reversal_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_payroll_reversal_rls_test");
        jdbc.execute("GRANT SELECT,UPDATE ON payroll_run_reversal_requests TO educore_payroll_reversal_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_payroll_reversal_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisation.toString());

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM payroll_run_reversal_requests", Integer.class);
        int crossTenantUpdate = jdbc.update("""
                UPDATE payroll_run_reversal_requests SET request_reason='Synthetic cross tenant edit'
                WHERE id=?
                """, otherRequest);

        assertThat(visible).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT id FROM payroll_run_reversal_requests WHERE id=?
                """, UUID.class, academyRequest)).isEqualTo(academyRequest);
    }

    @Test
    @Transactional
    void workforceLeaveIsSelfScopedRetrySafeBalancedAndNeverSilentlyRewritesSubmittedAttendance() {
        RolloverFixture fixture = insertAcademicRolloverFixture();
        UUID staffId = jdbc.queryForObject(
                "SELECT id FROM staff_profiles WHERE user_account_id=?", UUID.class,
                fixture.teacherUserId());
        UUID dayId = uuid("60000000", 1);
        LocalDate day = LocalDate.of(2025, 8, 28);
        jdbc.update("""
                INSERT INTO school_calendar_days(id,organisation_id,school_id,campus_id,
                  academic_session_id,calendar_date,day_type,display_name,attendance_required,
                  status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?,'INSTRUCTIONAL','Synthetic workforce day',true,
                  'ACTIVE',now(),now(),0)
                """, dayId, fixture.organisationId(), fixture.schoolId(), fixture.campusId(),
                fixture.sourceSessionId(), day);

        AuthenticatedUser administrator = new AuthenticatedUser(
                fixture.actorUserId(), UUID.randomUUID(), "workforce-admin@example.test",
                "workforce-admin@example.test", "Synthetic workforce administrator", true,
                fixture.organisationId(), Set.of(fixture.actorMembershipId()),
                Set.of("workforce.read", "workforce.attendance.record", "workforce.attendance.correct",
                        "workforce.leave.approve", "workforce.configure"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                fixture.teacherUserId(), UUID.randomUUID(), "rollover-teacher@example.test",
                "rollover-teacher@example.test", "Synthetic rollover teacher", true,
                fixture.organisationId(), Set.of(fixture.teacherMembershipId()),
                Set.of("workforce.self"), List.of());

        workforce.createPolicy(administrator, new PolicyRequest(
                fixture.schoolId(), fixture.sourceSessionId(), fixture.campusId(),
                "Synthetic staff attendance", List.of("PRESENT", "ABSENT", "LEAVE", "HALF_DAY"),
                java.util.Map.of("PRESENT", BigDecimal.ONE, "ABSENT", BigDecimal.ZERO,
                        "LEAVE", BigDecimal.ONE, "HALF_DAY", new BigDecimal("0.50")),
                24, new BigDecimal("75.00"), 3, "ACTIVE", null,
                "Synthetic policy configuration"));
        var leaveType = workforce.createLeaveType(administrator, new LeaveTypeRequest(
                fixture.schoolId(), "CASUAL_LEAVE", "Casual leave", true, true,
                new BigDecimal("5.00"), "ACTIVE", null, "Synthetic leave configuration"));
        LeaveBalanceRequest opening = new LeaveBalanceRequest(
                fixture.schoolId(), fixture.sourceSessionId(), staffId, leaveType.id(), "OPENING",
                new BigDecimal("10.00"), "workforce-balance-0001", "Synthetic approved balance");
        var balance = workforce.adjustLeaveBalance(administrator, opening);
        var balanceRetry = workforce.adjustLeaveBalance(administrator, opening);
        assertThat(balanceRetry.id()).isEqualTo(balance.id());

        var opened = workforce.openRegister(administrator,
                new OpenRegisterRequest(fixture.schoolId(), dayId));
        var recorded = workforce.recordMarks(administrator, opened.id(), new RecordMarksRequest(
                fixture.schoolId(), opened.rowVersion(),
                List.of(new MarkInput(staffId, "PRESENT", LocalTime.of(8, 0),
                        LocalTime.of(15, 0), null, null)), "Synthetic staff roll call"));
        workforce.submitRegister(administrator, opened.id(), new VersionedReasonRequest(
                fixture.schoolId(), recorded.rowVersion(), "Synthetic completed staff register"));

        LeaveRequest leaveRequest = new LeaveRequest(fixture.schoolId(), fixture.sourceSessionId(),
                leaveType.id(), day, day, "FULL_DAY", "workforce-leave-0001",
                "Synthetic personal leave request");
        var leave = workforce.requestLeave(teacher, leaveRequest);
        var leaveRetry = workforce.requestLeave(teacher, leaveRequest);
        assertThat(leaveRetry.id()).isEqualTo(leave.id());
        assertThatThrownBy(() -> workforce.decideLeave(teacher, leave.id(),
                new com.educore.api.workforce.WorkforceController.DecisionRequest(
                        fixture.schoolId(), "APPROVED", "Synthetic self approval attempt", 0)))
                .hasMessageContaining("outside your authorized role");
        workforce.decideLeave(administrator, leave.id(),
                new com.educore.api.workforce.WorkforceController.DecisionRequest(
                        fixture.schoolId(), "APPROVED", "Synthetic independent leave approval", 0));

        BigDecimal available = jdbc.queryForObject("""
                SELECT sum(quantity) FROM staff_leave_balance_entries
                WHERE academic_session_id=? AND staff_profile_id=? AND leave_type_id=?
                """, BigDecimal.class, fixture.sourceSessionId(), staffId, leaveType.id());
        String submittedMark = jdbc.queryForObject("""
                SELECT state_code FROM staff_attendance_marks
                WHERE staff_attendance_register_id=? AND staff_profile_id=?
                """, String.class, opened.id(), staffId);
        Integer pendingCorrection = jdbc.queryForObject("""
                SELECT count(*) FROM staff_attendance_correction_requests correction
                JOIN staff_attendance_marks mark ON mark.id=correction.staff_attendance_mark_id
                WHERE mark.staff_attendance_register_id=? AND mark.staff_profile_id=?
                  AND correction.status='PENDING' AND correction.requested_state_code='LEAVE'
                """, Integer.class, opened.id(), staffId);
        Integer leaveHistory = jdbc.queryForObject(
                "SELECT count(*) FROM staff_leave_request_history WHERE staff_leave_request_id=?",
                Integer.class, leave.id());
        var ownSnapshot = workforce.snapshot(teacher, fixture.schoolId(), day);

        assertThat(available).isEqualByComparingTo("9.00");
        assertThat(submittedMark).isEqualTo("PRESENT");
        assertThat(pendingCorrection).isEqualTo(1);
        assertThat(leaveHistory).isEqualTo(2);
        assertThat(ownSnapshot.staff()).extracting(item -> item.id()).containsExactly(staffId);
        assertThat(ownSnapshot.sessions()).extracting(item -> item.name())
                .contains("Synthetic rollover 2025-2026");
        assertThat(ownSnapshot.leaveRequests()).singleElement()
                .satisfies(item -> assertThat(item.requesterName()).isEqualTo("Synthetic user"));
        assertThat(ownSnapshot.corrections()).singleElement()
                .satisfies(item -> assertThat(item.requesterName()).isEqualTo("Synthetic user"));
        assertThatThrownBy(() -> workforce.snapshot(teacher, UUID.randomUUID(), day))
                .hasMessageContaining("unavailable for this school");
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE staff_attendance_marks SET state_code='LEAVE' WHERE staff_attendance_register_id=?",
                opened.id()), "submitted staff attendance marks require an approved correction");
        assertDatabaseRejected(() -> jdbc.update(
                "DELETE FROM staff_attendance_marks WHERE staff_attendance_register_id=?",
                opened.id()), "staff attendance marks cannot be deleted");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE staff_attendance_registers SET submitted_at=now()+interval '1 hour'
                WHERE id=?
                """, opened.id()), "staff attendance submission evidence is immutable");
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE staff_leave_request_history SET reason='rewritten' WHERE staff_leave_request_id=?",
                leave.id()), "append-only");
    }

    @Test
    @Transactional
    void academicYearRolloverIsPreviewBoundIdempotentImmutableAndTenantScoped() {
        RolloverFixture fixture = insertAcademicRolloverFixture();
        AuthenticatedUser administrator = new AuthenticatedUser(
                fixture.actorUserId(), UUID.randomUUID(), "rollover-admin@example.test",
                "rollover-admin@example.test", "Synthetic rollover administrator", true,
                fixture.organisationId(), Set.of(fixture.actorMembershipId()),
                Set.of("academic.rollover.manage"), List.of());
        AuthenticatedUser teacher = new AuthenticatedUser(
                fixture.teacherUserId(), UUID.randomUUID(), "rollover-teacher@example.test",
                "rollover-teacher@example.test", "Synthetic rollover teacher", true,
                fixture.organisationId(), Set.of(fixture.teacherMembershipId()),
                Set.of("academic.rollover.manage"), List.of());

        List<StudentRolloverDecisionRequest> decisions = List.of(
                new StudentRolloverDecisionRequest(
                        fixture.promotedEnrolmentId(), "PROMOTE", "VII", "A", "11", null,
                        "Synthetic promotion after session completion"),
                new StudentRolloverDecisionRequest(
                        fixture.retainedEnrolmentId(), "RETAIN", "VII", "A", "12", null,
                        "Synthetic retention approved by school authority"),
                new StudentRolloverDecisionRequest(
                        fixture.leavingEnrolmentId(), "LEAVE", null, null, null, "ALUMNI",
                        "Synthetic learner completed the school journey"));
        RolloverPreviewRequest request = new RolloverPreviewRequest(
                fixture.schoolId(), fixture.sourceSessionId(), "Synthetic rollover 2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                true, true, true, true, decisions);
        RolloverPreviewRequest incomplete = new RolloverPreviewRequest(
                fixture.schoolId(), fixture.sourceSessionId(), "Synthetic incomplete target",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                false, false, false, false, List.of(decisions.getFirst()));

        var workspace = academicRollovers.workspace(administrator, fixture.schoolId());
        assertThat(workspace.students()).hasSize(3);
        assertThat(workspace.students()).allSatisfy(student ->
                assertThat(student.academicSessionId()).isEqualTo(fixture.sourceSessionId()));
        assertThat(workspace.history()).isEmpty();
        assertThatThrownBy(() -> academicRollovers.preview(administrator, incomplete))
                .hasMessageContaining("exactly one decision for every active student");
        assertThatThrownBy(() -> academicRollovers.workspace(teacher, fixture.schoolId()))
                .hasMessageContaining("not available for the selected school");
        assertThatThrownBy(() -> academicRollovers.workspace(
                administrator, uuid("50000000", 99)))
                .hasMessageContaining("not available for the selected school");

        var stalePreview = academicRollovers.preview(administrator, request);
        assertThat(stalePreview.counts().students()).isEqualTo(3);
        assertThat(stalePreview.counts().promoted()).isEqualTo(1);
        assertThat(stalePreview.counts().retained()).isEqualTo(1);
        assertThat(stalePreview.counts().leaving()).isEqualTo(1);
        assertThat(stalePreview.counts().sections()).isEqualTo(2);
        assertThat(stalePreview.counts().terms()).isEqualTo(1);
        assertThat(stalePreview.counts().resources()).isEqualTo(1);
        assertThat(stalePreview.counts().offerings()).isEqualTo(1);
        assertThat(stalePreview.counts().allocations()).isEqualTo(1);
        assertThat(stalePreview.counts().periods()).isEqualTo(1);
        assertThat(stalePreview.counts().timetablePlans()).isEqualTo(1);
        assertThat(stalePreview.counts().timetableEntries()).isEqualTo(1);
        assertThat(stalePreview.counts().feePlans()).isEqualTo(1);
        assertThat(stalePreview.counts().feeItems()).isEqualTo(1);
        assertThat(stalePreview.counts().calendarPolicies()).isEqualTo(1);
        assertThat(stalePreview.warnings()).isEmpty();

        jdbc.update("""
                UPDATE academic_subject_resources
                SET updated_at=updated_at+interval '1 second',row_version=row_version+1
                WHERE organisation_id=? AND school_id=? AND academic_session_id=?
                """, fixture.organisationId(), fixture.schoolId(), fixture.sourceSessionId());
        assertThatThrownBy(() -> academicRollovers.execute(
                administrator,
                new RolloverExecutionRequest(
                        request, "rollover-stale-preview-0001", stalePreview.snapshotHash(),
                        "Synthetic stale-preview rejection evidence")))
                .hasMessageContaining("changed after preview");

        var freshPreview = academicRollovers.preview(administrator, request);
        var executionRequest = new RolloverExecutionRequest(
                request, "rollover-2026-2027-0001", freshPreview.snapshotHash(),
                "Synthetic approved annual rollover after record review");
        var execution = academicRollovers.execute(administrator, executionRequest);
        var retry = academicRollovers.execute(administrator, executionRequest);

        assertThat(execution.status()).isEqualTo("EXECUTED");
        assertThat(execution.snapshotHash()).isEqualTo(freshPreview.snapshotHash());
        assertThat(retry.batchId()).isEqualTo(execution.batchId());
        assertThat(retry.targetSessionId()).isEqualTo(execution.targetSessionId());
        assertThat(retry.counts()).isEqualTo(execution.counts());
        assertThatThrownBy(() -> academicRollovers.execute(
                administrator,
                new RolloverExecutionRequest(
                        request, "rollover-2026-2027-0001", "0".repeat(64),
                        "Synthetic conflicting retry evidence")))
                .hasMessageContaining("different rollover preview");

        String sourceStatus = jdbc.queryForObject(
                "SELECT status FROM academic_sessions WHERE id=?",
                String.class, fixture.sourceSessionId());
        String targetStatus = jdbc.queryForObject(
                "SELECT status FROM academic_sessions WHERE id=?",
                String.class, execution.targetSessionId());
        Integer decisionCount = jdbc.queryForObject("""
                SELECT count(*) FROM academic_rollover_student_decisions
                WHERE rollover_batch_id=? AND organisation_id=? AND school_id=?
                """, Integer.class, execution.batchId(), fixture.organisationId(), fixture.schoolId());
        Integer outcomeCount = jdbc.queryForObject("""
                SELECT count(DISTINCT outcome) FROM academic_rollover_student_decisions
                WHERE rollover_batch_id=?
                """, Integer.class, execution.batchId());
        Integer endedSourceEnrolments = jdbc.queryForObject("""
                SELECT count(*) FROM student_enrolments
                WHERE academic_session_id=? AND status='ENDED' AND ends_on=DATE '2026-03-31'
                """, Integer.class, fixture.sourceSessionId());
        Integer targetEnrolments = jdbc.queryForObject("""
                SELECT count(*) FROM student_enrolments
                WHERE academic_session_id=? AND origin_rollover_batch_id=?
                  AND prior_enrolment_id IS NOT NULL AND status='ACTIVE'
                """, Integer.class, execution.targetSessionId(), execution.batchId());
        Integer targetPlacements = jdbc.queryForObject("""
                SELECT count(*) FROM student_section_placements placement
                JOIN student_enrolments enrolment ON enrolment.id=placement.enrolment_id
                WHERE enrolment.academic_session_id=? AND placement.status='ACTIVE'
                """, Integer.class, execution.targetSessionId());
        Integer identifierCount = jdbc.queryForObject("""
                SELECT count(*) FROM learner_admission_identifiers
                WHERE organisation_id=? AND school_id=?
                  AND admission_number_normalized LIKE 'syn-roll-%'
                """, Integer.class, fixture.organisationId(), fixture.schoolId());
        String leavingStatus = jdbc.queryForObject(
                "SELECT status FROM learners WHERE id=?",
                String.class, fixture.leavingLearnerId());

        assertThat(sourceStatus).isEqualTo("CLOSED");
        assertThat(targetStatus).isEqualTo("ACTIVE");
        assertThat(decisionCount).isEqualTo(3);
        assertThat(outcomeCount).isEqualTo(3);
        assertThat(endedSourceEnrolments).isEqualTo(3);
        assertThat(targetEnrolments).isEqualTo(2);
        assertThat(targetPlacements).isEqualTo(2);
        assertThat(identifierCount).isEqualTo(3);
        assertThat(leavingStatus).isEqualTo("ALUMNI");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM class_sections
                WHERE academic_session_id=? AND status='ACTIVE'
                """, Integer.class, execution.targetSessionId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM academic_terms WHERE academic_session_id=?
                  AND starts_on=DATE '2026-04-01' AND ends_on=DATE '2026-09-30'
                """, Integer.class, execution.targetSessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM academic_subject_resources
                WHERE academic_session_id=? AND status='ACTIVE'
                """, Integer.class, execution.targetSessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM teaching_allocations allocation
                JOIN section_subject_offerings offering
                  ON offering.id=allocation.section_subject_offering_id
                JOIN class_sections section ON section.id=offering.class_section_id
                WHERE section.academic_session_id=? AND allocation.status='ACTIVE'
                """, Integer.class, execution.targetSessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM timetable_entries entry
                JOIN timetable_plans plan ON plan.id=entry.timetable_plan_id
                WHERE plan.academic_session_id=? AND plan.status='DRAFT'
                """, Integer.class, execution.targetSessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fee_plan_items item
                JOIN fee_plans plan ON plan.id=item.fee_plan_id
                WHERE plan.academic_session_id=? AND plan.status='DRAFT'
                  AND item.due_on=DATE '2026-04-10'
                """, Integer.class, execution.targetSessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_policy_revisions
                WHERE academic_session_id=? AND revision_number=1
                """, Integer.class, execution.targetSessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE organisation_id=? AND school_id=? AND actor_membership_id=?
                  AND action='academic.rollover.executed'
                """, Integer.class, fixture.organisationId(), fixture.schoolId(),
                fixture.actorMembershipId())).isEqualTo(1);

        assertDatabaseRejected(
                () -> jdbc.update(
                        "UPDATE academic_rollover_batches SET row_version=row_version+1 WHERE id=?",
                        execution.batchId()),
                "academic rollover evidence is immutable");

        UUID duplicateApplicationId = uuid("50000000", 50);
        UUID duplicateLearnerId = uuid("50000000", 51);
        insertApplication(
                duplicateApplicationId, fixture.organisationId(), fixture.schoolId(),
                fixture.actorMembershipId(), "rollover-duplicate-admission");
        jdbc.update("""
                INSERT INTO learners(
                  id,organisation_id,school_id,origin_application_id,legal_name,birth_date,
                  gender_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'Synthetic duplicate admission learner',
                  DATE '2015-01-01','NOT_STATED','ACTIVE',now(),now(),0)
                """, duplicateLearnerId, fixture.organisationId(), fixture.schoolId(),
                duplicateApplicationId);
        assertDatabaseRejected(
                () -> jdbc.update("""
                        INSERT INTO student_enrolments(
                          id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                          origin_application_id,grade_code,admission_number,status,placement_status,
                          starts_on,created_at,updated_at,row_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?,'VII','SYN-ROLL-001','ACTIVE','PENDING',
                          DATE '2026-04-01',now(),now(),0)
                        """, uuid("50000000", 52), fixture.organisationId(), fixture.schoolId(),
                        fixture.campusId(), execution.targetSessionId(), duplicateLearnerId,
                        duplicateApplicationId),
                "admission number already belongs to another learner");
    }

    @Test
    @Transactional
    void rowSecurityHidesAndProtectsEnquiriesFromAnotherOrganisation() {
        UUID academyOrganisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchool = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID otherOrganisation = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID otherSchool = UUID.fromString("20000000-0000-4000-8000-000000000002");
        UUID academyEnquiry = UUID.fromString("30000000-0000-4000-8000-000000000001");
        UUID otherEnquiry = UUID.fromString("30000000-0000-4000-8000-000000000002");

        jdbc.update("""
                INSERT INTO organisations (id, slug, legal_name, display_name, created_at, updated_at)
                VALUES (?, 'isolation-test-org', 'Isolation Test Organisation', 'Isolation Test', now(), now())
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools (id, organisation_id, slug, legal_name, display_name, created_at, updated_at)
                VALUES (?, ?, 'isolation-test-school', 'Isolation Test School', 'Isolation Test School', now(), now())
                """, otherSchool, otherOrganisation);
        insertEnquiry(academyEnquiry, academyOrganisation, academySchool, "academy-isolation-key");
        insertEnquiry(otherEnquiry, otherOrganisation, otherSchool, "other-isolation-key");

        jdbc.execute("CREATE ROLE educore_enquiry_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_enquiry_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON public_enquiries TO educore_enquiry_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_enquiry_rls_test");
        jdbc.queryForObject(
                "SELECT set_config('app.current_organisation_id', ?, true)",
                String.class,
                academyOrganisation.toString());

        Integer visibleCount = jdbc.queryForObject("SELECT count(*) FROM public_enquiries", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE public_enquiries SET topic = 'forbidden change' WHERE id = ?",
                otherEnquiry);
        String visibleReference = jdbc.queryForObject(
                "SELECT id::text FROM public_enquiries WHERE id = ?",
                String.class,
                academyEnquiry);

        assertThat(visibleCount).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
        assertThat(visibleReference).isEqualTo(academyEnquiry.toString());
    }

    @Test
    @Transactional
    void transportStopsRemainOrganisationIsolatedUnderRowSecurity() {
        UUID academyOrganisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID academySchool = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID academyCampus = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID otherOrganisation = UUID.fromString("31000000-0000-4000-8000-000000000001");
        UUID otherSchool = UUID.fromString("31000000-0000-4000-8000-000000000002");
        UUID otherCampus = UUID.fromString("31000000-0000-4000-8000-000000000003");
        UUID academyUser = UUID.fromString("31000000-0000-4000-8000-000000000004");
        UUID otherUser = UUID.fromString("31000000-0000-4000-8000-000000000005");
        UUID academyMembership = UUID.fromString("31000000-0000-4000-8000-000000000006");
        UUID otherMembership = UUID.fromString("31000000-0000-4000-8000-000000000007");
        UUID academyStop = UUID.fromString("31000000-0000-4000-8000-000000000008");
        UUID otherStop = UUID.fromString("31000000-0000-4000-8000-000000000009");

        jdbc.update("""
                INSERT INTO organisations (id, slug, legal_name, display_name, created_at, updated_at)
                VALUES (?, 'transport-isolation-org', 'Transport Isolation Organisation',
                    'Transport Isolation', now(), now())
                """, otherOrganisation);
        jdbc.update("""
                INSERT INTO schools (id, organisation_id, slug, legal_name, display_name, created_at, updated_at)
                VALUES (?, ?, 'transport-isolation-school', 'Transport Isolation School',
                    'Transport Isolation School', now(), now())
                """, otherSchool, otherOrganisation);
        jdbc.update("""
                INSERT INTO campuses (id, organisation_id, school_id, code, display_name, time_zone,
                    status, created_at, updated_at, row_version)
                VALUES (?, ?, ?, 'TEST', 'Synthetic campus', 'Asia/Kolkata', 'ACTIVE', now(), now(), 0)
                """, otherCampus, otherOrganisation, otherSchool);
        insertSyntheticUser(academyUser, "transport-academy@example.test");
        insertSyntheticUser(otherUser, "transport-other@example.test");
        insertSyntheticMembership(academyMembership, academyUser, academyOrganisation, academySchool);
        insertSyntheticMembership(otherMembership, otherUser, otherOrganisation, otherSchool);
        insertTransportStop(academyStop, academyOrganisation, academySchool, academyCampus,
                academyMembership, "ACADEMY_STOP");
        insertTransportStop(otherStop, otherOrganisation, otherSchool, otherCampus,
                otherMembership, "OTHER_STOP");

        jdbc.execute("CREATE ROLE educore_transport_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_transport_rls_test");
        jdbc.execute("GRANT SELECT, UPDATE ON transport_stops TO educore_transport_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_transport_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, academyOrganisation.toString());

        Integer visibleCount = jdbc.queryForObject("SELECT count(*) FROM transport_stops", Integer.class);
        int crossTenantUpdate = jdbc.update(
                "UPDATE transport_stops SET display_name='Forbidden change' WHERE id=?", otherStop);
        String visibleStop = jdbc.queryForObject(
                "SELECT id::text FROM transport_stops WHERE id=?", String.class, academyStop);

        assertThat(visibleCount).isEqualTo(1);
        assertThat(crossTenantUpdate).isZero();
        assertThat(visibleStop).isEqualTo(academyStop.toString());
    }

    @Test
    @Transactional
    void transportConfigurationRejectsDuplicateStopCodesWithinASchool() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID school = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campus = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID user = UUID.fromString("32000000-0000-4000-8000-000000000001");
        UUID membership = UUID.fromString("32000000-0000-4000-8000-000000000002");
        insertSyntheticUser(user, "transport-duplicate@example.test");
        insertSyntheticMembership(membership, user, organisation, school);
        insertTransportStop(UUID.fromString("32000000-0000-4000-8000-000000000003"),
                organisation, school, campus, membership, "DUPLICATE_STOP");

        assertThatThrownBy(() -> insertTransportStop(
                UUID.fromString("32000000-0000-4000-8000-000000000004"),
                organisation, school, campus, membership, "DUPLICATE_STOP"))
                .hasMessageContaining("transport_stops_code_unique");
    }

    @Test
    @Transactional
    void draftTransportRouteStopCanBeDeletedWithinItsTenantPolicy() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID school = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campus = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID user = UUID.fromString("34000000-0000-4000-8000-000000000001");
        UUID membership = UUID.fromString("34000000-0000-4000-8000-000000000002");
        UUID stop = UUID.fromString("34000000-0000-4000-8000-000000000003");
        UUID route = UUID.fromString("34000000-0000-4000-8000-000000000004");
        UUID revision = UUID.fromString("34000000-0000-4000-8000-000000000005");
        UUID routeStop = UUID.fromString("34000000-0000-4000-8000-000000000006");
        insertSyntheticUser(user, "transport-route-stop-delete@example.test");
        insertSyntheticMembership(membership, user, organisation, school);
        insertTransportStop(stop, organisation, school, campus, membership, "DELETE_STOP");
        jdbc.update("""
                INSERT INTO transport_routes (id,organisation_id,school_id,campus_id,code,
                    display_name,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'DELETE_ROUTE', 'Synthetic draft route', 'ACTIVE', ?, now(), now(), 0)
                """, route, organisation, school, campus, membership);
        jdbc.update("""
                INSERT INTO transport_route_revisions (id,organisation_id,school_id,transport_route_id,
                    revision_number,journey_kind,service_label,operating_days,effective_from,status,
                    created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 1, 'PICKUP', 'Synthetic pickup', 'MON', current_date,
                    'DRAFT', ?, now(), now(), 0)
                """, revision, organisation, school, route, membership);
        jdbc.update("""
                INSERT INTO transport_route_revision_stops (id,organisation_id,school_id,
                    transport_route_revision_id,transport_stop_id,sequence_number,scheduled_time,
                    boarding_window_minutes,stop_code_snapshot,stop_name_snapshot,
                    family_label_snapshot,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 1, '07:30', 5, 'DELETE_STOP', 'Synthetic stop',
                    'Synthetic family label', ?, now(), now(), 0)
                """, routeStop, organisation, school, revision, stop, membership);

        jdbc.execute("CREATE ROLE educore_transport_route_stop_delete_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_transport_route_stop_delete_test");
        jdbc.execute("GRANT SELECT ON transport_route_revisions TO educore_transport_route_stop_delete_test");
        jdbc.execute("GRANT SELECT, DELETE ON transport_route_revision_stops TO educore_transport_route_stop_delete_test");
        jdbc.execute("SET LOCAL ROLE educore_transport_route_stop_delete_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id', ?, true)",
                String.class, organisation.toString());

        int deleted = jdbc.update("DELETE FROM transport_route_revision_stops WHERE id=?", routeStop);

        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @Transactional
    void transportHistoryCannotBeRewritten() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID school = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID user = UUID.fromString("33000000-0000-4000-8000-000000000001");
        UUID membership = UUID.fromString("33000000-0000-4000-8000-000000000002");
        UUID history = UUID.fromString("33000000-0000-4000-8000-000000000003");
        insertSyntheticUser(user, "transport-history@example.test");
        insertSyntheticMembership(membership, user, organisation, school);
        jdbc.update("""
                INSERT INTO transport_history (id,organisation_id,school_id,subject_type,subject_id,
                    event_type,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES (?, ?, ?, 'ROUTE', ?, 'CREATED', NULL, 'ACTIVE',
                    'Synthetic immutable evidence', ?, now())
                """, history, organisation, school, UUID.randomUUID(), membership);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE transport_history SET reason='Rewritten evidence' WHERE id=?", history))
                .hasMessageContaining("transport history is append-only");
    }

    @Test
    @Transactional
    void transportIncidentCannotBeResolvedByItsReporter() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID school = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID user = UUID.fromString("35000000-0000-4000-8000-000000000001");
        UUID membership = UUID.fromString("35000000-0000-4000-8000-000000000002");
        UUID incident = UUID.fromString("35000000-0000-4000-8000-000000000003");
        insertSyntheticUser(user, "transport-incident-review@example.test");
        insertSyntheticMembership(membership, user, organisation, school);
        jdbc.update("""
                INSERT INTO transport_incidents (id,organisation_id,school_id,category,severity,
                    summary,restricted_details,status,reported_by_membership_id,reported_at,
                    created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'DELAY', 'LOW', 'Synthetic incident',
                    'Synthetic restricted evidence', 'OPEN', ?, now(), now(), now(), 0)
                """, incident, organisation, school, membership);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE transport_incidents SET status='RESOLVED',resolved_by_membership_id=?,
                    resolved_at=now(),resolution_summary='Synthetic resolution',updated_at=now(),
                    row_version=row_version+1 WHERE id=?
                """, membership, incident))
                .hasMessageContaining("transport_incidents_state_valid");
    }

    @Test
    @Transactional
    void parentRequestsAreRelationshipAssignedRetrySafePrivateAndAudited() {
        PortalFixture fixture = academyPortalFixture("ab000000");
        insertPortalFixture(fixture, "parent-request-workflow");
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                fixture.guardianMembershipId(),
                UUID.fromString("00000000-0000-4000-8000-000000000006"));
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                fixture.staffMembershipId(),
                UUID.fromString("00000000-0000-4000-8000-000000000001"));
        UUID staffProfileId = uuid("ab000000", 8);
        jdbc.update("""
                INSERT INTO staff_profiles(id,organisation_id,school_id,employee_code,legal_name,
                  display_name,user_account_id,joined_on,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN-REQUEST', 'Synthetic request administrator',
                  'Synthetic request administrator', ?, DATE '2026-04-01', 'ACTIVE', ?, now(), now(), 0)
                """, staffProfileId, fixture.organisationId(), fixture.schoolId(),
                fixture.staffUserId(), fixture.staffMembershipId());

        AuthenticatedUser guardian = new AuthenticatedUser(
                fixture.guardianUserId(), UUID.randomUUID(),
                "parent-request-workflow-guardian@example.test",
                "parent-request-workflow-guardian@example.test", "Synthetic portal guardian", true,
                fixture.organisationId(), Set.of(fixture.guardianMembershipId()),
                Set.of("portal.guardian"), List.of());
        AuthenticatedUser administrator = new AuthenticatedUser(
                fixture.staffUserId(), UUID.randomUUID(),
                "parent-request-workflow-staff@example.test",
                "parent-request-workflow-staff@example.test", "Synthetic request administrator", true,
                fixture.organisationId(), Set.of(fixture.staffMembershipId()),
                Set.of("parent_requests.read", "parent_requests.respond", "parent_requests.manage"), List.of());

        var openRequest = new com.educore.api.parentrequests.FamilyParentRequestController.OpenRequest(
                "ATTENDANCE", "Synthetic attendance question",
                "Please review the synthetic attendance record.", "parent-open-retry-key");
        var opened = parentRequests.open(guardian, fixture.learnerId(), openRequest);
        var retried = parentRequests.open(guardian, fixture.learnerId(), openRequest);
        assertThat(retried).isEqualTo(opened);
        assertThatThrownBy(() -> parentRequests.open(guardian, fixture.learnerId(),
                new com.educore.api.parentrequests.FamilyParentRequestController.OpenRequest(
                        "FEES", "Different retry payload", "Different synthetic detail",
                        "parent-open-retry-key")))
                .hasMessageContaining("retry key");
        assertThat(parentRequests.familyList(guardian, fixture.learnerId()).requests())
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo("OPEN");
                    assertThat(item.guardianName()).isEqualTo("Synthetic portal guardian");
                });
        assertThatThrownBy(() -> parentRequests.familyList(guardian, UUID.randomUUID()))
                .hasMessageContaining("verified relationship");

        var workspace = parentRequests.workspace(administrator, fixture.schoolId(), null, null, null);
        assertThat(workspace.canManage()).isTrue();
        assertThat(workspace.requests()).extracting(item -> item.id()).contains(opened.id());
        assertThat(workspace.assignees()).extracting(item -> item.staffProfileId()).contains(staffProfileId);

        var assigned = parentRequests.assign(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.AssignmentRequest(
                        fixture.schoolId(), staffProfileId, "HIGH", 0,
                        "Assigned to the synthetic responsible staff member"));
        assertThat(assigned.status()).isEqualTo("ASSIGNED");
        UUID schoolAdminRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        jdbc.update("DELETE FROM role_capabilities WHERE role_id=? AND capability_code='parent_requests.respond'",
                schoolAdminRole);
        assertThatThrownBy(() -> parentRequests.staffReply(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.StaffReplyRequest(
                        fixture.schoolId(), "Revoked responders cannot write.", false,
                        "parent-staff-revoked-reply", 1)))
                .hasMessageContaining("access is unavailable");
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?, 'parent_requests.respond')",
                schoolAdminRole);
        var visibleReply = parentRequests.staffReply(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.StaffReplyRequest(
                        fixture.schoolId(), "The synthetic record was reviewed.", false,
                        "parent-staff-visible-reply", 1));
        assertThat(visibleReply.status()).isEqualTo("IN_PROGRESS");
        var internalReply = parentRequests.staffReply(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.StaffReplyRequest(
                        fixture.schoolId(), "Internal synthetic follow-up evidence.", true,
                        "parent-staff-internal-note", 2));
        assertThat(internalReply.rowVersion()).isEqualTo(3);

        var familyDetail = parentRequests.familyDetail(guardian, fixture.learnerId(), opened.id());
        assertThat(familyDetail.messages()).hasSize(2)
                .noneMatch(message -> message.internalNote()
                        || message.body().contains("Internal synthetic"));
        var staffDetail = parentRequests.staffDetail(administrator, fixture.schoolId(), opened.id());
        assertThat(staffDetail.messages()).hasSize(3)
                .anyMatch(message -> message.internalNote()
                        && message.body().contains("Internal synthetic"));
        assertThat(staffDetail.history()).extracting(event -> event.eventType())
                .contains("OPENED", "ASSIGNED", "STAFF_REPLIED");

        var resolved = parentRequests.transition(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.TransitionRequest(
                        fixture.schoolId(), "RESOLVE", 3,
                        "Synthetic attendance question resolved after record review"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        var reopened = parentRequests.familyReply(guardian, fixture.learnerId(), opened.id(),
                new com.educore.api.parentrequests.FamilyParentRequestController.ReplyRequest(
                        "Please check one additional synthetic date.",
                        "parent-guardian-follow-up", 4));
        assertThat(reopened.status()).isEqualTo("ASSIGNED");
        var resolvedAgain = parentRequests.transition(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.TransitionRequest(
                        fixture.schoolId(), "RESOLVE", 5,
                        "Additional synthetic date was reviewed and confirmed"));
        var closed = parentRequests.transition(administrator, opened.id(),
                new com.educore.api.parentrequests.ParentRequestController.TransitionRequest(
                        fixture.schoolId(), "CLOSE", resolvedAgain.rowVersion(),
                        "Family-visible resolution completed and reviewed"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThatThrownBy(() -> parentRequests.familyReply(guardian, fixture.learnerId(), opened.id(),
                new com.educore.api.parentrequests.FamilyParentRequestController.ReplyRequest(
                        "A closed request cannot accept this.", "parent-closed-reply", 7)))
                .hasMessageContaining("closed");

        assertDatabaseRejected(() -> jdbc.update("DELETE FROM parent_requests WHERE id=?", opened.id()),
                "cannot be deleted");
        assertDatabaseRejected(() -> jdbc.update(
                "UPDATE parent_request_messages SET body='rewritten' WHERE parent_request_id=?", opened.id()),
                "append-only");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE aggregate_type='PARENT_REQUEST'
                  AND aggregate_id=?
                """, Integer.class, opened.id().toString())).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE aggregate_type='PARENT_REQUEST' AND aggregate_id=?
                  AND (payload->>'organisationId' IS DISTINCT FROM ? OR payload->>'schoolId' IS DISTINCT FROM ?)
                """, Integer.class, opened.id().toString(),fixture.organisationId().toString(),fixture.schoolId().toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events evidence
                LEFT JOIN parent_request_history history ON history.id::text=evidence.payload->>'historyId'
                  AND history.parent_request_id::text=evidence.aggregate_id
                  AND history.organisation_id::text=evidence.payload->>'organisationId'
                  AND history.school_id::text=evidence.payload->>'schoolId'
                  AND history.occurred_at=evidence.occurred_at
                WHERE evidence.aggregate_type='PARENT_REQUEST' AND evidence.aggregate_id=?
                  AND (history.id IS NULL OR history.event_type IS DISTINCT FROM CASE evidence.event_type
                    WHEN 'parent_request.opened' THEN 'OPENED'
                    WHEN 'parent_request.assigned' THEN 'ASSIGNED'
                    WHEN 'parent_request.staff_replied' THEN 'STAFF_REPLIED'
                    WHEN 'parent_request.guardian_replied' THEN 'PARENT_REPLIED'
                    WHEN 'parent_request.resolve' THEN 'RESOLVED'
                    WHEN 'parent_request.close' THEN 'CLOSED'
                    WHEN 'parent_request.reopen' THEN 'REOPENED' END)
                """, Integer.class, opened.id().toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events evidence JOIN parent_request_history history
                  ON history.id::text=evidence.payload->>'historyId'
                WHERE evidence.aggregate_type='PARENT_REQUEST' AND evidence.aggregate_id=?
                  AND history.reason='Internal staff note added'
                """, Integer.class, opened.id().toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events evidence
                LEFT JOIN parent_request_messages message ON message.id::text=evidence.payload->>'messageId'
                  AND message.parent_request_id::text=evidence.aggregate_id
                  AND message.organisation_id::text=evidence.payload->>'organisationId'
                  AND message.school_id::text=evidence.payload->>'schoolId'
                  AND message.created_at=evidence.occurred_at AND NOT message.internal_note
                WHERE evidence.aggregate_type='PARENT_REQUEST' AND evidence.aggregate_id=?
                  AND evidence.event_type IN ('parent_request.opened','parent_request.guardian_replied','parent_request.staff_replied')
                  AND message.id IS NULL
                """, Integer.class, opened.id().toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE subject_type='PARENT_REQUEST' AND subject_id=?
                """, Integer.class, opened.id().toString())).isGreaterThanOrEqualTo(7);
    }

    private AuthenticatedUser syntheticAcademicUser(
            UUID userId, UUID membershipId, UUID organisationId) {
        return new AuthenticatedUser(
                userId, UUID.randomUUID(), "synthetic-teacher@example.test",
                "synthetic-teacher@example.test", "Synthetic teacher", true,
                organisationId, Set.of(membershipId), Set.of("academics.read"), List.of());
    }

    private void insertSyntheticTimetableEntry(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID planId,
            UUID sectionId,
            UUID offeringId,
            UUID periodId,
            UUID teacherMembershipId) {
        jdbc.update("""
                INSERT INTO timetable_entries(
                  id,organisation_id,school_id,timetable_plan_id,class_section_id,
                  section_subject_offering_id,bell_schedule_period_id,teacher_membership_id,
                  subject_name_snapshot,subject_short_name_snapshot,period_name_snapshot,
                  day_of_week_snapshot,sequence_number_snapshot,starts_at_snapshot,
                  ends_at_snapshot,room_label,note,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Synthetic Mathematics', 'Maths',
                  'Period 1', 1, 1, TIME '08:00', TIME '08:40', 'S-1',
                  'Synthetic timetable evidence', now(), now(), 0)
                """, id, organisationId, schoolId, planId, sectionId, offeringId,
                periodId, teacherMembershipId);
    }

    private RolloverFixture insertAcademicRolloverFixture() {
        UUID organisationId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID schoolId = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID campusId = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID adminRoleId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID teacherRoleId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID actorUserId = uuid("50000000", 1);
        UUID actorMembershipId = uuid("50000000", 2);
        UUID teacherUserId = uuid("50000000", 3);
        UUID teacherMembershipId = uuid("50000000", 4);
        UUID sourceSessionId = uuid("50000000", 5);
        UUID gradeSixSectionId = uuid("50000000", 6);
        UUID gradeSevenSectionId = uuid("50000000", 7);
        UUID termId = uuid("50000000", 8);
        UUID subjectId = uuid("50000000", 9);
        UUID resourceId = uuid("50000000", 10);
        UUID offeringId = uuid("50000000", 11);
        UUID designationId = uuid("50000000", 12);
        UUID staffProfileId = uuid("50000000", 13);
        UUID staffAssignmentId = uuid("50000000", 14);
        UUID allocationId = uuid("50000000", 15);
        UUID periodId = uuid("50000000", 16);
        UUID timetablePlanId = uuid("50000000", 17);
        UUID timetableEntryId = uuid("50000000", 18);
        UUID feeHeadId = uuid("50000000", 19);
        UUID feePlanId = uuid("50000000", 20);
        UUID feeItemId = uuid("50000000", 21);
        UUID policyId = uuid("50000000", 22);

        insertSyntheticUser(actorUserId, "rollover-admin@example.test");
        insertSyntheticMembership(actorMembershipId, actorUserId, organisationId, schoolId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                actorMembershipId, adminRoleId);
        insertSyntheticUser(teacherUserId, "rollover-teacher@example.test");
        insertSyntheticMembership(teacherMembershipId, teacherUserId, organisationId, schoolId);
        jdbc.update("UPDATE memberships SET valid_from=TIMESTAMPTZ '2025-01-01 00:00:00Z' WHERE id=?",
                teacherMembershipId);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                teacherMembershipId, teacherRoleId);

        jdbc.update("""
                INSERT INTO staff_designations(
                  id,organisation_id,school_id,code,display_name,staff_category,
                  cbse_staff_category,teaching_role,leadership_role,display_order,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'ROLLOVER_TEACHER', 'Synthetic Rollover Teacher', 'TEACHING',
                  'OTHER_TEACHING',true,false,0,'ACTIVE',?,now(),now(),0)
                """, designationId, organisationId, schoolId, actorMembershipId);
        jdbc.update("""
                INSERT INTO staff_profiles(
                  id,organisation_id,school_id,employee_code,legal_name,display_name,
                  primary_language,work_email_normalized,user_account_id,joined_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN-ROLL-T01', 'Synthetic Rollover Teacher',
                  'Synthetic Rollover Teacher','EN','rollover-teacher@example.test',?,
                  DATE '2025-01-01','ACTIVE',?,now(),now(),0)
                """, staffProfileId, organisationId, schoolId, teacherUserId, actorMembershipId);
        jdbc.update("""
                INSERT INTO staff_assignments(
                  id,organisation_id,school_id,staff_profile_id,staff_designation_id,campus_id,
                  employment_type,employer_kind,primary_assignment,starts_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?,'FULL_TIME','SCHOOL',true,DATE '2025-01-01',
                  'ACTIVE',?,now(),now(),0)
                """, staffAssignmentId, organisationId, schoolId, staffProfileId,
                designationId, campusId, actorMembershipId);
        jdbc.update("""
                INSERT INTO academic_sessions(
                  id,organisation_id,school_id,name,starts_on,ends_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic rollover 2025-2026',DATE '2025-04-01',
                  DATE '2026-03-31','ACTIVE',now(),now(),0)
                """, sourceSessionId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO class_sections(
                  id,organisation_id,school_id,campus_id,academic_session_id,grade_code,
                  code,display_name,capacity,status,created_at,updated_at,row_version)
                VALUES
                  (?, ?, ?, ?, ?, 'VI','A','Synthetic Class VI A',30,'ACTIVE',now(),now(),0),
                  (?, ?, ?, ?, ?, 'VII','A','Synthetic Class VII A',30,'ACTIVE',now(),now(),0)
                """, gradeSixSectionId, organisationId, schoolId, campusId, sourceSessionId,
                gradeSevenSectionId, organisationId, schoolId, campusId, sourceSessionId);
        jdbc.update("""
                INSERT INTO academic_terms(
                  id,organisation_id,school_id,academic_session_id,code,display_name,
                  starts_on,ends_on,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?,'TERM_1','Synthetic Term 1',DATE '2025-04-01',
                  DATE '2025-09-30','ACTIVE',now(),now(),0)
                """, termId, organisationId, schoolId, sourceSessionId);
        jdbc.update("""
                INSERT INTO school_subjects(
                  id,organisation_id,school_id,code,display_name,short_name,
                  subject_category,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?,'ROLL_MATH','Synthetic Mathematics','Maths',
                  'CORE','ACTIVE',now(),now(),0)
                """, subjectId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO academic_subject_resources(
                  id,organisation_id,school_id,academic_session_id,subject_id,grade_code,
                  resource_type,title,description,display_order,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?,'VI','TEXTBOOK','Synthetic rollover textbook',
                  'Synthetic integration evidence only',1,'ACTIVE',now(),now(),0)
                """, resourceId, organisationId, schoolId, sourceSessionId, subjectId);
        jdbc.update("""
                INSERT INTO section_subject_offerings(
                  id,organisation_id,school_id,class_section_id,subject_id,
                  weekly_target_periods,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?,5,'ACTIVE',now(),now(),0)
                """, offeringId, organisationId, schoolId, gradeSixSectionId, subjectId);
        jdbc.update("""
                INSERT INTO teaching_allocations(
                  id,organisation_id,school_id,section_subject_offering_id,
                  teacher_membership_id,allocation_role,starts_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?,'PRIMARY',DATE '2025-04-01','ACTIVE',now(),now(),0)
                """, allocationId, organisationId, schoolId, offeringId, teacherMembershipId);
        jdbc.update("""
                INSERT INTO bell_schedule_periods(
                  id,organisation_id,school_id,campus_id,academic_session_id,day_of_week,
                  sequence_number,code,display_name,starts_at,ends_at,period_kind,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?,1,1,'P1','Period 1',TIME '08:00',TIME '08:40',
                  'TEACHING','ACTIVE',now(),now(),0)
                """, periodId, organisationId, schoolId, campusId, sourceSessionId);
        jdbc.update("""
                INSERT INTO timetable_plans(
                  id,organisation_id,school_id,campus_id,academic_session_id,academic_term_id,
                  plan_name,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?,'Synthetic rollover timetable','DRAFT',?,
                  now(),now(),0)
                """, timetablePlanId, organisationId, schoolId, campusId, sourceSessionId,
                termId, actorMembershipId);
        insertSyntheticTimetableEntry(
                timetableEntryId, organisationId, schoolId, timetablePlanId,
                gradeSixSectionId, offeringId, periodId, teacherMembershipId);
        jdbc.update("""
                UPDATE timetable_plans SET status='PUBLISHED',published_by_membership_id=?,
                  published_at=now(),updated_at=now(),row_version=row_version+1 WHERE id=?
                """, actorMembershipId, timetablePlanId);
        jdbc.update("""
                INSERT INTO fee_heads(
                  id,organisation_id,school_id,code,display_name,category,refundable,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?,'ROLL_TUITION','Synthetic Tuition','TUITION',false,
                  'ACTIVE',now(),now(),0)
                """, feeHeadId, organisationId, schoolId);
        jdbc.update("""
                INSERT INTO fee_plans(
                  id,organisation_id,school_id,academic_session_id,plan_code,revision_number,
                  display_name,grade_code,currency_code,public_disclosure,receipt_prefix,status,
                  activated_by_membership_id,activated_at,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?,'ROLL_VI',1,'Synthetic Class VI fees','VI','INR',false,
                  'ROLL','DRAFT',NULL,NULL,?,now(),now(),0)
                """, feePlanId, organisationId, schoolId, sourceSessionId,
                actorMembershipId);
        jdbc.update("""
                INSERT INTO fee_plan_items(
                  id,organisation_id,school_id,fee_plan_id,fee_head_id,sequence_number,
                  label,due_on,amount,allow_partial,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?,1,'Synthetic April tuition',DATE '2025-04-10',
                  700.00,true,now(),now(),0)
                """, feeItemId, organisationId, schoolId, feePlanId, feeHeadId);
        jdbc.update("""
                UPDATE fee_plans SET status='ACTIVE',activated_by_membership_id=?,
                  activated_at=now(),updated_at=now(),row_version=row_version+1 WHERE id=?
                """, actorMembershipId, feePlanId);
        jdbc.update("""
                INSERT INTO school_calendar_policy_revisions(
                  id,organisation_id,school_id,campus_id,academic_session_id,revision_number,
                  school_start_time,same_day_cutoff_minutes,reminder_time,time_zone,
                  guardian_push_enabled,student_in_app_enabled,emergency_ack_required,
                  change_reason,revised_by_membership_id,revised_at,row_version)
                VALUES (?, ?, ?, ?, ?,1,TIME '08:00',60,TIME '18:00','Asia/Kolkata',
                  true,true,true,'Synthetic source-session policy',?,now(),0)
                """, policyId, organisationId, schoolId, campusId, sourceSessionId,
                actorMembershipId);

        insertRolloverStudent(
                uuid("50000000", 30), uuid("50000000", 31), uuid("50000000", 32),
                uuid("50000000", 33), organisationId, schoolId, campusId, sourceSessionId,
                gradeSixSectionId, actorMembershipId, "promote", "VI", "SYN-ROLL-001", "1");
        insertRolloverStudent(
                uuid("50000000", 34), uuid("50000000", 35), uuid("50000000", 36),
                uuid("50000000", 37), organisationId, schoolId, campusId, sourceSessionId,
                gradeSevenSectionId, actorMembershipId, "retain", "VII", "SYN-ROLL-002", "2");
        insertRolloverStudent(
                uuid("50000000", 38), uuid("50000000", 39), uuid("50000000", 40),
                uuid("50000000", 41), organisationId, schoolId, campusId, sourceSessionId,
                gradeSixSectionId, actorMembershipId, "leave", "VI", "SYN-ROLL-003", "3");

        return new RolloverFixture(
                organisationId, schoolId, campusId, actorUserId, actorMembershipId,
                teacherUserId, teacherMembershipId, sourceSessionId,
                gradeSixSectionId, gradeSevenSectionId,
                uuid("50000000", 31), uuid("50000000", 32),
                uuid("50000000", 35), uuid("50000000", 36),
                uuid("50000000", 39), uuid("50000000", 40));
    }

    private void insertRolloverStudent(
            UUID applicationId,
            UUID learnerId,
            UUID enrolmentId,
            UUID placementId,
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID sessionId,
            UUID sectionId,
            UUID actorMembershipId,
            String key,
            String gradeCode,
            String admissionNumber,
            String rollNumber) {
        insertApplication(
                applicationId, organisationId, schoolId, actorMembershipId,
                "rollover-" + key);
        jdbc.update("""
                INSERT INTO learners(
                  id,organisation_id,school_id,origin_application_id,legal_name,birth_date,
                  gender_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, DATE '2014-01-01',
                  'NOT_STATED','ACTIVE',now(),now(),0)
                """, learnerId, organisationId, schoolId, applicationId,
                "Synthetic rollover learner " + key);
        jdbc.update("""
                INSERT INTO student_enrolments(
                  id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                  origin_application_id,grade_code,admission_number,status,placement_status,
                  starts_on,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE','PLACED',
                  DATE '2025-04-01',now(),now(),0)
                """, enrolmentId, organisationId, schoolId, campusId, sessionId,
                learnerId, applicationId, gradeCode, admissionNumber);
        jdbc.update("""
                INSERT INTO student_section_placements(
                  id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,
                  roll_number,starts_on,status,placed_by_membership_id,placement_reason,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, DATE '2025-04-01','ACTIVE',?,
                  'Synthetic source-session placement',now(),now(),0)
                """, placementId, organisationId, schoolId, enrolmentId, learnerId,
                sectionId, rollNumber, actorMembershipId);
    }

    private void insertEnquiry(UUID id, UUID organisationId, UUID schoolId, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO public_enquiries (
                    id, organisation_id, school_id, idempotency_key, request_fingerprint,
                    name, email_normalized, message, intent, source, privacy_notice_version,
                    consented_at, status, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, 'Synthetic parent', 'synthetic@example.test',
                    'Synthetic test enquiry only.', 'GENERAL', 'WEBSITE', 'test', now(),
                    'NEW', now(), now(), 0)
                """, id, organisationId, schoolId, idempotencyKey, "0".repeat(64));
    }

    @Test
    @Transactional
    void launchReadinessRechecksCurrentAuthorityAndRejectsForeignRoleGrants() {
        UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID school = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID adminRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        insertSyntheticUser(user, "synthetic-launch-current-" + user + "@example.test");
        insertSyntheticMembership(membership, user, organisation, school);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership, adminRole);
        var actor = new AuthenticatedUser(user, UUID.randomUUID(), "synthetic-launch", null,
                "Synthetic launch authority", true, organisation, Set.of(membership),
                Set.of("launch.configure", "launch.approve"), List.of());
        assertThat(launchReadiness.snapshot(actor, school).items()).hasSize(10);

        for (String table : List.of("user_accounts", "schools", "organisations", "memberships")) {
            UUID id = switch (table) {
                case "user_accounts" -> user;
                case "schools" -> school;
                case "organisations" -> organisation;
                default -> membership;
            };
            jdbc.update("UPDATE " + table + " SET status='SUSPENDED' WHERE id=?", id);
            assertLaunchScopeDenied(actor, school);
            jdbc.update("UPDATE " + table + " SET status='ACTIVE' WHERE id=?", id);
        }
        jdbc.update("UPDATE memberships SET valid_from=now()-interval '1 day',valid_until=now()-interval '1 second' WHERE id=?", membership);
        assertLaunchScopeDenied(actor, school);
        jdbc.update("UPDATE memberships SET valid_from=now()+interval '1 day',valid_until=NULL WHERE id=?", membership);
        assertLaunchScopeDenied(actor, school);
        jdbc.update("UPDATE memberships SET valid_from=now()-interval '1 day' WHERE id=?", membership);

        assertLaunchScopeDenied(actor, UUID.randomUUID());
        var forgedActor = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-forged", null,
                "Synthetic foreign identity", true, organisation, Set.of(membership), actor.capabilities(), List.of());
        assertLaunchScopeDenied(forgedActor, school);

        UUID foreignOrganisation = UUID.randomUUID();
        UUID foreignRole = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES (?,?,'Synthetic foreign launch organisation','Synthetic foreign launch organisation','ACTIVE',now(),now())
                """, foreignOrganisation, "synthetic-launch-" + foreignOrganisation);
        jdbc.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,system_role,created_at,updated_at)
                VALUES (?,?,'SYNTHETIC_FOREIGN_LAUNCH','Synthetic foreign launch role',false,now(),now())
                """, foreignRole, foreignOrganisation);
        for (String capability : List.of("launch.configure", "launch.approve")) {
            jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,?)", foreignRole, capability);
        }
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?", membership);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership, foreignRole);
        assertLaunchScopeDenied(actor, school);
        UUID localConfigureRole = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,system_role,created_at,updated_at)
                VALUES (?,?,'SYNTHETIC_LAUNCH_CONFIGURER','Synthetic local launch configurer',false,now(),now())
                """, localConfigureRole, organisation);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES (?,'launch.configure')", localConfigureRole);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership, localConfigureRole);
        assertThat(launchReadiness.snapshot(actor, school).canConfigure()).isTrue();
        assertThat(launchReadiness.snapshot(actor, school).canApprove()).isFalse();
        assertThatThrownBy(() -> launchReadiness.decideReview(actor, UUID.randomUUID(), new ReviewDecisionRequest(
                school, "APPROVED", "Synthetic forbidden foreign-role approval", 0)))
                .hasMessageContaining("unavailable for this school");
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?", membership);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership, adminRole);
        assertThat(launchReadiness.snapshot(actor, school).canApprove()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void appliesV064AndPreservesHistoricalLaunchReviewsWhileEnforcingIndependentPeople() throws Exception {
        String sourceUrl = System.getenv("TEST_DATABASE_URL");
        String ownerUser;
        String ownerPassword;
        if (sourceUrl == null || sourceUrl.isBlank()) {
            assertThat(managedPostgres).isNotNull();
            sourceUrl = managedPostgres.getJdbcUrl();
            ownerUser = managedPostgres.getUsername();
            ownerPassword = managedPostgres.getPassword();
        } else {
            assertThat(sourceUrl).matches("jdbc:postgresql://[^/]+/educore_integration(?:\\?.*)?");
            ownerUser = requiredEnvironment("TEST_DATABASE_USER");
            ownerPassword = requiredEnvironment("TEST_DATABASE_PASSWORD");
        }
        String database = "educore_launch_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String upgradeUrl = sourceUrl.replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
        jdbc.execute("CREATE DATABASE " + database);
        try {
            Flyway.configure().dataSource(upgradeUrl, ownerUser, ownerPassword).target("063").load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(upgradeUrl, ownerUser, ownerPassword)) {
                assertThat(connection.getCatalog()).isEqualTo(database);
                connection.setAutoCommit(false);
                var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
                var scopedSource = (javax.sql.DataSource) new com.educore.api.tenancy.TenantAwareDataSourceBeanPostProcessor()
                        .postProcessAfterInitialization(source, "syntheticLaunchUpgradeDataSource");
                var upgrade = new JdbcTemplate(scopedSource);
                var upgradeClock = java.time.Clock.systemUTC();
                var service = new LaunchReadinessService(
                        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(upgrade),
                        new com.educore.api.identity.SecurityAuditService(upgrade, launchUpgradeMapper, upgradeClock),
                        launchUpgradeMapper, upgradeClock);
                UUID organisation = UUID.fromString("10000000-0000-4000-8000-000000000001");
                UUID school = UUID.fromString("10000000-0000-4000-8000-000000000002");
                UUID campus = UUID.fromString("10000000-0000-4000-8000-000000000003");
                UUID makerUser = UUID.randomUUID();
                UUID approverUser = UUID.randomUUID();
                UUID makerMembership = UUID.randomUUID();
                UUID alternateMembership = UUID.randomUUID();
                UUID approverMembership = UUID.randomUUID();
                for (UUID user : List.of(makerUser, approverUser)) {
                    upgrade.update("""
                            INSERT INTO user_accounts(id,login_identifier_normalized,display_name,password_hash,status,created_at,updated_at)
                            VALUES (?,?,'Synthetic launch upgrade user','not-a-login-hash','ACTIVE',now(),now())
                            """, user, "synthetic-launch-upgrade-" + user + "@example.test");
                }
                for (UUID membership : List.of(makerMembership, alternateMembership, approverMembership)) {
                    upgrade.update("""
                            INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                            VALUES (?,?,?,?,?,'ACTIVE',now(),now(),now())
                            """, membership, membership.equals(approverMembership) ? approverUser : makerUser,
                            organisation, school, membership.equals(alternateMembership) ? campus : null);
                    upgrade.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)", membership,
                            UUID.fromString("00000000-0000-4000-8000-000000000001"));
                }
                var maker = new AuthenticatedUser(makerUser, UUID.randomUUID(), "synthetic-upgrade-maker", null,
                        "Synthetic upgrade maker", true, organisation, Set.of(makerMembership),
                        Set.of("launch.configure", "launch.approve"), List.of());
                var alternate = new AuthenticatedUser(makerUser, UUID.randomUUID(), "synthetic-upgrade-maker", null,
                        "Synthetic alternate membership", true, organisation, Set.of(alternateMembership), maker.capabilities(), List.of());
                var approver = new AuthenticatedUser(approverUser, UUID.randomUUID(), "synthetic-upgrade-approver", null,
                        "Synthetic upgrade approver", true, organisation, Set.of(approverMembership), maker.capabilities(), List.of());
                for (var item : service.snapshot(maker, school).items()) {
                    service.reviseItem(maker, item.id(), new ReviseItemRequest(school, item.category(), item.title(),
                            item.guidance(), true, "READY", "runbook:synthetic/launch-upgrade", "Synthetic owner",
                            "Synthetic evidence only", item.revisionNumber(), "Synthetic upgrade fixture"));
                }
                var original = service.submitReview(maker, new SubmitReviewRequest(school,
                        "Synthetic legacy evidence", true, "Synthetic legacy submission"));
                // Real pre-V064 lifecycle accepts a different membership owned by the same person.
                upgrade.queryForObject("SELECT set_config('app.launch_review_decision','approved',true)", String.class);
                upgrade.update("""
                        UPDATE school_launch_reviews SET status='APPROVED',decided_by_membership_id=?,decided_at=now(),
                          decision_reason='Synthetic historical self-decision',row_version=row_version+1 WHERE id=?
                        """, alternateMembership, original.id());
                String originalEvidence = upgrade.queryForObject(
                        "SELECT to_jsonb(review)::text FROM school_launch_reviews review WHERE id=?", String.class, original.id());
                connection.commit();

                var migration = Flyway.configure().dataSource(upgradeUrl, ownerUser, ownerPassword).target("064").load().migrate();
                assertThat(migration.migrationsExecuted).isEqualTo(1);
                assertThat(upgrade.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='064' AND success", Integer.class)).isEqualTo(1);
                assertThat(upgrade.queryForObject("SELECT to_jsonb(review)::text FROM school_launch_reviews review WHERE id=?", String.class, original.id()))
                        .isEqualTo(originalEvidence);
                var legacy = service.snapshot(maker, school);
                assertThat(legacy.launchState()).isEqualTo("APPROVAL_STALE");
                assertThat(legacy.reviews().getFirst().status()).isEqualTo("APPROVED");
                assertThat(legacy.reviews().getFirst().matchesCurrentSnapshot()).isTrue();

                var replacement = service.submitReview(maker, new SubmitReviewRequest(school,
                        "Synthetic independent resubmission", true, "Synthetic preserved-history repair"));
                for (String decision : List.of("APPROVED", "REJECTED")) {
                    assertThatThrownBy(() -> service.decideReview(alternate, replacement.id(),
                            new ReviewDecisionRequest(school, decision, "Synthetic self-decision denied", 0)))
                            .hasMessageContaining("different authorized person");
                    upgrade.queryForObject("SELECT set_config('app.launch_review_decision','approved',true)", String.class);
                    var savepoint = connection.setSavepoint();
                    assertThatThrownBy(() -> upgrade.update("""
                            UPDATE school_launch_reviews SET status=?,decided_by_membership_id=?,decided_at=now(),
                              decision_reason='Synthetic direct self-decision',row_version=row_version+1 WHERE id=?
                            """, decision, alternateMembership, replacement.id()))
                            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                            .hasMessageContaining("different authorized person");
                    connection.rollback(savepoint);
                    connection.releaseSavepoint(savepoint);
                }
                var insertSavepoint = connection.setSavepoint();
                assertThatThrownBy(() -> upgrade.update("""
                        INSERT INTO school_launch_reviews(id,organisation_id,school_id,review_number,supersedes_review_id,
                          status,item_count,required_count,ready_count,blocked_count,readiness_snapshot,snapshot_sha256,
                          submission_statement,submission_reason,submitted_by_membership_id,submitted_at,
                          decided_by_membership_id,decided_at,decision_reason,row_version)
                        SELECT ?,organisation_id,school_id,3,id,status,item_count,required_count,ready_count,blocked_count,
                          readiness_snapshot,snapshot_sha256,submission_statement,submission_reason,submitted_by_membership_id,
                          submitted_at,decided_by_membership_id,decided_at,decision_reason,row_version
                        FROM school_launch_reviews WHERE id=?
                        """, UUID.randomUUID(), original.id()))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                        .hasMessageContaining("different authorized person");
                connection.rollback(insertSavepoint);
                connection.releaseSavepoint(insertSavepoint);
                service.decideReview(approver, replacement.id(), new ReviewDecisionRequest(
                        school, "APPROVED", "Synthetic independent approval after real upgrade", 0));
                assertThatThrownBy(() -> service.decideReview(approver, replacement.id(), new ReviewDecisionRequest(
                        school, "APPROVED", "Synthetic duplicate decision denied", 0))).hasMessageContaining("already changed");
                assertThat(service.snapshot(maker, school).launchState()).isEqualTo("GO_LIVE_APPROVED");
                assertThat(upgrade.queryForObject("SELECT to_jsonb(review)::text FROM school_launch_reviews review WHERE id=?", String.class, original.id()))
                        .isEqualTo(originalEvidence);
                connection.commit();
            }
        } finally {
            // Only this test's generated database; never an existing school/source database.
            if (!database.matches("educore_launch_upgrade_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe generated database name");
            jdbc.execute("DROP DATABASE " + database);
        }
    }

    private void assertLaunchScopeDenied(AuthenticatedUser actor, UUID school) {
        assertThatThrownBy(() -> launchReadiness.snapshot(actor, school)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> launchReadiness.createItem(actor, new CreateItemRequest(
                school, "SYNTHETIC_DENIED", "OTHER", "Synthetic denied control", "Synthetic denied control only",
                false, "NOT_STARTED", null, "Synthetic owner", null, "Synthetic unauthorized mutation")))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> launchReadiness.submitReview(actor, new SubmitReviewRequest(
                school, "Synthetic denied submission", true, "Synthetic denied submission")))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> launchReadiness.decideReview(actor, UUID.randomUUID(), new ReviewDecisionRequest(
                school, "APPROVED", "Synthetic denied decision", 0)))
                .hasMessageContaining("unavailable for this school");
    }

    private void insertSyntheticUser(UUID id, String email) {
        insertSyntheticLoginUser(id, email, email);
    }

    private void insertSyntheticLoginUser(UUID id, String loginIdentifier, String email) {
        jdbc.update("""
                INSERT INTO user_accounts (
                    id, login_identifier_normalized, email_normalized, display_name,
                    password_hash, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'Synthetic user', 'not-a-real-password-hash',
                    'ACTIVE', now(), now())
                """, id, loginIdentifier, email);
    }

    @Test
    @Transactional
    void familyScheduleAllowsPendingPlacementAndKeepsPublishedEntriesScoped() {
        var fixture = insertFamilyScheduleFixture();
        var portal = fixture.portal();
        var pending = family.schedule(fixture.guardian(), portal.learnerId());
        assertThat(pending.learnerId()).isEqualTo(portal.learnerId());
        assertThat(pending.entries()).isEmpty();
        assertThat(pending.timetablePlanId()).isNull();
        assertThat(pending.timetableName()).isNull();
        assertThat(pending.publishedAt()).isNull();
        assertThat(family.schedule(fixture.guardian(), portal.learnerId())).isEqualTo(pending);

        var academic = fixture.academic();
        jdbc.update("UPDATE student_enrolments SET placement_status='PLACED' WHERE id=?", fixture.enrolmentId());
        jdbc.update("""
                INSERT INTO student_section_placements(
                  id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,
                  starts_on,status,placed_by_membership_id,placement_reason,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, DATE '2025-04-01','ACTIVE',?,
                  'Synthetic timetable placement',now(),now(),0)
                """, UUID.randomUUID(), portal.organisationId(), portal.schoolId(),
                fixture.enrolmentId(), portal.learnerId(), academic.gradeSixSectionId(), academic.actorMembershipId());
        UUID draftPlan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO timetable_plans(id,organisation_id,school_id,campus_id,academic_session_id,
                  plan_name,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'Synthetic unpublished replacement','DRAFT',?,now(),now(),0)
                """, draftPlan, portal.organisationId(), portal.schoolId(), academic.campusId(),
                academic.sourceSessionId(), academic.actorMembershipId());
        insertSyntheticTimetableEntry(UUID.randomUUID(), portal.organisationId(), portal.schoolId(),
                draftPlan, academic.gradeSixSectionId(), uuid("50000000", 11),
                uuid("50000000", 16), academic.teacherMembershipId());
        var published = family.schedule(fixture.guardian(), portal.learnerId());
        assertThat(published.timetablePlanId()).isEqualTo(uuid("50000000", 17));
        assertThat(published.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(uuid("50000000", 18));
            assertThat(entry.subjectName()).isEqualTo("Synthetic Mathematics");
            assertThat(entry.startsAt()).isEqualTo(java.time.LocalTime.of(8, 0));
        });
        assertThat(family.schedule(fixture.guardian(), portal.learnerId())).isEqualTo(published);
    }

    @Test
    @Transactional
    void familyScheduleRechecksOwnChildAndLiveAuthorityBeforePendingState() {
        var fixture = insertFamilyScheduleFixture();
        var portal = fixture.portal();
        var guardian = fixture.guardian();
        assertThat(family.schedule(guardian, portal.learnerId()).entries()).isEmpty();
        assertFamilyScheduleDenied(guardian, UUID.randomUUID());
        assertFamilyScheduleDenied(guardian, fixture.academic().promotedLearnerId());
        var foreign = new AuthenticatedUser(guardian.userId(), guardian.sessionId(),
                guardian.loginIdentifier(), guardian.email(), guardian.displayName(), true,
                UUID.randomUUID(), guardian.membershipIds(), guardian.capabilities(), guardian.organisations());
        assertFamilyScheduleDenied(foreign, portal.learnerId());
        var wrongMembership = new AuthenticatedUser(guardian.userId(), guardian.sessionId(),
                guardian.loginIdentifier(), guardian.email(), guardian.displayName(), true,
                guardian.activeOrganisationId(), Set.of(portal.staffMembershipId()), guardian.capabilities(), guardian.organisations());
        assertFamilyScheduleDenied(wrongMembership, portal.learnerId());
        jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=? AND guardian_id=?",
                portal.learnerId(), portal.guardianId());
        assertFamilyScheduleDenied(guardian, portal.learnerId());
        jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE' WHERE learner_id=? AND guardian_id=?",
                portal.learnerId(), portal.guardianId());
        jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",
                portal.guardianMembershipId());
        assertFamilyScheduleDenied(guardian, portal.learnerId());
        jdbc.update("UPDATE memberships SET valid_until=NULL WHERE id=?", portal.guardianMembershipId());
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?", portal.guardianMembershipId());
        assertFamilyScheduleDenied(guardian, portal.learnerId());
    }

    private void assertFamilyScheduleDenied(AuthenticatedUser guardian, UUID learnerId) {
        assertThatThrownBy(() -> family.schedule(guardian, learnerId))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND");
                });
    }

    private FamilyScheduleFixture insertFamilyScheduleFixture() {
        var academic = insertAcademicRolloverFixture();
        var portal = academyPortalFixture("ec000000");
        insertPortalFixture(portal, "synthetic-family-timetable");
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                portal.guardianMembershipId(), UUID.fromString("00000000-0000-4000-8000-000000000006"));
        UUID enrolment = uuid("ec000000", 8);
        jdbc.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,
                  learner_id,origin_application_id,grade_code,status,placement_status,starts_on,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'VI','ACTIVE','PENDING',DATE '2025-04-01',now(),now(),0)
                """, enrolment, portal.organisationId(), portal.schoolId(), academic.campusId(),
                academic.sourceSessionId(), portal.learnerId(), portal.applicationId());
        var guardian = new AuthenticatedUser(portal.guardianUserId(), UUID.randomUUID(),
                "synthetic-family-timetable-guardian@example.test", "synthetic-family-timetable-guardian@example.test",
                "Synthetic timetable guardian", true, portal.organisationId(),
                Set.of(portal.guardianMembershipId()), Set.of("portal.guardian"), List.of());
        return new FamilyScheduleFixture(portal, academic, enrolment, guardian);
    }

    private record FamilyScheduleFixture(PortalFixture portal, RolloverFixture academic,
            UUID enrolmentId, AuthenticatedUser guardian) {}

    private PortalFixture academyPortalFixture(String prefix) {
        return new PortalFixture(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000002"),
                uuid(prefix, 1), uuid(prefix, 2), uuid(prefix, 3), uuid(prefix, 4),
                uuid(prefix, 5), uuid(prefix, 6), uuid(prefix, 7));
    }

    private CurriculumFixture academyCurriculumFixture(String prefix) {
        return new CurriculumFixture(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000002"),
                uuid(prefix, 1), uuid(prefix, 2), uuid(prefix, 3), uuid(prefix, 4),
                uuid(prefix, 5), uuid(prefix, 6), uuid(prefix, 7));
    }

    private void insertCurriculumFixture(CurriculumFixture fixture, String key) {
        insertSyntheticUser(fixture.userId(), key + "@example.test");
        insertSyntheticMembership(fixture.membershipId(), fixture.userId(),
                fixture.organisationId(), fixture.schoolId());
        jdbc.update("""
                INSERT INTO academic_sessions(
                  id,organisation_id,school_id,name,starts_on,ends_on,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, DATE '2026-04-01', DATE '2027-03-31',
                  'PLANNED', now(), now(), 0)
                """, fixture.sessionId(), fixture.organisationId(), fixture.schoolId(),
                "Synthetic " + key);
        jdbc.update("""
                INSERT INTO school_subjects(
                  id,organisation_id,school_id,code,display_name,short_name,
                  subject_category,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'Synthetic curriculum subject', 'SYN',
                  'CORE','ACTIVE',now(),now(),0)
                """, fixture.subjectId(), fixture.organisationId(), fixture.schoolId(),
                "SYN" + fixture.subjectId().toString().substring(0, 8).toUpperCase());
        jdbc.update("""
                INSERT INTO academic_subject_resources(
                  id,organisation_id,school_id,academic_session_id,subject_id,grade_code,
                  resource_type,title,description,display_order,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'VI', 'TEXTBOOK', 'Synthetic curriculum book',
                  'Synthetic integration evidence only', 1, 'ACTIVE', now(), now(), 0)
                """, fixture.resourceId(), fixture.organisationId(), fixture.schoolId(),
                fixture.sessionId(), fixture.subjectId());
        jdbc.update("""
                INSERT INTO curriculum_publications(
                  id,organisation_id,school_id,academic_session_id,grade_code,
                  publication_version,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'VI', 1, 'DRAFT', ?, now(), now(), 0)
                """, fixture.publicationId(), fixture.organisationId(), fixture.schoolId(),
                fixture.sessionId(), fixture.membershipId());
        jdbc.update("""
                INSERT INTO curriculum_publication_items(
                  id,organisation_id,school_id,curriculum_publication_id,source_resource_id,
                  subject_code_snapshot,subject_name_snapshot,subject_short_name_snapshot,
                  resource_type_snapshot,title_snapshot,description_snapshot,
                  display_order_snapshot,created_at)
                VALUES (?, ?, ?, ?, ?, 'SYN', 'Synthetic curriculum subject', 'SYN',
                  'TEXTBOOK', 'Synthetic curriculum book',
                  'Synthetic integration evidence only', 1, now())
                """, fixture.itemId(), fixture.organisationId(), fixture.schoolId(),
                fixture.publicationId(), fixture.resourceId());
    }

    private void publishCurriculumFixture(CurriculumFixture fixture) {
        jdbc.update("""
                UPDATE curriculum_publications
                SET status='PUBLISHED',published_by_membership_id=?,published_at=now(),
                  updated_at=now(),row_version=row_version+1
                WHERE id=? AND organisation_id=? AND school_id=?
                """, fixture.membershipId(), fixture.publicationId(),
                fixture.organisationId(), fixture.schoolId());
    }

    private void insertPortalFixture(PortalFixture fixture, String key) {
        insertSyntheticUser(fixture.staffUserId(), key + "-staff@example.test");
        insertSyntheticMembership(fixture.staffMembershipId(), fixture.staffUserId(),
                fixture.organisationId(), fixture.schoolId());
        insertSyntheticUser(fixture.guardianUserId(), key + "-guardian@example.test");
        insertSyntheticMembership(fixture.guardianMembershipId(), fixture.guardianUserId(),
                fixture.organisationId(), fixture.schoolId());
        insertApplication(fixture.applicationId(), fixture.organisationId(), fixture.schoolId(),
                fixture.staffMembershipId(), key);
        jdbc.update("""
                INSERT INTO learners(
                  id,organisation_id,school_id,origin_application_id,legal_name,birth_date,
                  gender_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'Synthetic portal learner', DATE '2015-01-01',
                  'NOT_STATED','ACTIVE',now(),now(),0)
                """, fixture.learnerId(), fixture.organisationId(), fixture.schoolId(),
                fixture.applicationId());
        jdbc.update("""
                INSERT INTO guardians(
                  id,organisation_id,school_id,display_name,email_normalized,status,
                  created_at,updated_at,row_version,user_account_id)
                VALUES (?, ?, ?, 'Synthetic portal guardian', ?, 'ACTIVE',now(),now(),0,?)
                """, fixture.guardianId(), fixture.organisationId(), fixture.schoolId(),
                key + "-guardian@example.test", fixture.guardianUserId());
        jdbc.update("""
                INSERT INTO learner_guardian_relationships(
                  learner_id,guardian_id,organisation_id,school_id,relationship_code,
                  primary_contact,pickup_authorized,portal_access_status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'PARENT_GUARDIAN',true,true,'ACTIVE',now(),now(),0)
                """, fixture.learnerId(), fixture.guardianId(), fixture.organisationId(),
                fixture.schoolId());
    }

    private void insertPendingPortalConsent(
            PortalFixture fixture, UUID consentId, String loginIdentifier) {
        jdbc.update("""
                INSERT INTO student_portal_consent_requests(
                  id,organisation_id,school_id,learner_id,guardian_id,
                  proposed_login_identifier_normalized,privacy_notice_version,purpose_summary,
                  adult_verification_method,adult_verification_reference,adult_verified_at,
                  adult_verified_by_membership_id,preparation_reason,status,expires_at,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, 'child-account-v1',
                  'Synthetic own-record student portal access for integration verification.',
                  'SCHOOL_RECORD','school-record:synthetic-portal-001',now(),?,
                  'Synthetic adult verification evidence','PENDING',now()+interval '14 days',
                  ?,now(),now(),0)
                """, consentId, fixture.organisationId(), fixture.schoolId(), fixture.learnerId(),
                fixture.guardianId(), loginIdentifier, fixture.staffMembershipId(),
                fixture.staffMembershipId());
    }

    private void approvePortalConsent(
            PortalFixture fixture,
            UUID consentId,
            UUID studentUserId,
            UUID decisionUserId,
            UUID decisionMembershipId) {
        jdbc.update("""
                UPDATE student_portal_consent_requests
                SET status='APPROVED',guardian_decided_by_user_id=?,
                  guardian_decided_by_membership_id=?,guardian_decided_at=now(),
                  guardian_decision_reason='Synthetic guardian approval',
                  approved_user_account_id=?,updated_at=now(),row_version=row_version+1
                WHERE id=? AND organisation_id=? AND school_id=?
                """, decisionUserId, decisionMembershipId, studentUserId, consentId,
                fixture.organisationId(), fixture.schoolId());
    }

    private void insertPortalAccount(
            PortalFixture fixture, UUID accountId, UUID consentId, UUID studentUserId) {
        jdbc.update("""
                INSERT INTO learner_portal_accounts(
                  id,organisation_id,school_id,learner_id,user_account_id,
                  current_consent_request_id,status,activated_at,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE',now(),now(),now(),0)
                """, accountId, fixture.organisationId(), fixture.schoolId(), fixture.learnerId(),
                studentUserId, consentId);
    }

    private AuthenticatedUser syntheticDocumentUser(
            UUID userId, UUID membershipId, UUID organisationId, String displayName) {
        return new AuthenticatedUser(
                userId, UUID.randomUUID(), "synthetic-documents@example.test",
                "synthetic-documents@example.test", displayName, true, organisationId,
                Set.of(membershipId), Set.of(
                        "documents.read", "documents.configure", "documents.issue",
                        "documents.approve", "documents.revoke", "fees.read"),
                List.of());
    }

    /** Match the actual human request's thread and PostgreSQL identities; do not manufacture another actor. */
    private com.educore.api.documents.OfficialDocumentMutationResponse requestReceiptDocument(
            AuthenticatedUser actor, String key, IssueRequest request) {
        return inManualFinanceScope(actor, () -> officialDocuments.requestIssue(actor, key, request));
    }

    private com.educore.api.documents.OfficialDocumentMutationResponse decideReceiptDocument(
            AuthenticatedUser actor, UUID document, DecisionRequest request) {
        return inManualFinanceScope(actor, () -> officialDocuments.decideIssue(actor, document, request));
    }

    private TemplateRequest documentTemplate(
            UUID schoolId, String code, String locale, String title) {
        return new TemplateRequest(
                schoolId, code, 1, null, "FEE_RECEIPT", code + " synthetic template",
                locale, title, "Synthetic controlled financial evidence",
                "Verify this synthetic document before relying on it.", "Principal",
                "#23483F", true, java.util.Map.of(), null,
                "Synthetic document template configuration");
    }

    private UUID insertSyntheticFeePaymentSource(
            String prefix,
            int base,
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID sessionId,
            UUID actorMembershipId) {
        UUID applicationId = uuid(prefix, base);
        UUID learnerId = uuid(prefix, base + 1);
        UUID enrolmentId = uuid(prefix, base + 2);
        insertApplication(applicationId, organisationId, schoolId, actorMembershipId,
                "synthetic-document-source-" + base);
        jdbc.update("""
                INSERT INTO learners(
                  id,organisation_id,school_id,origin_application_id,legal_name,birth_date,
                  gender_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'Synthetic document learner', DATE '2015-01-01',
                  'NOT_STATED', 'ACTIVE', now(), now(), 0)
                """, learnerId, organisationId, schoolId, applicationId);
        jdbc.update("""
                INSERT INTO student_enrolments(
                  id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                  origin_application_id,grade_code,admission_number,status,placement_status,
                  starts_on,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'VI', ?, 'ACTIVE', 'PENDING',
                  DATE '2026-04-01', now(), now(), 0)
                """, enrolmentId, organisationId, schoolId, campusId, sessionId, learnerId,
                applicationId, "SYN-DOC-" + base);
        UUID actorUserId=jdbc.queryForObject("SELECT user_id FROM memberships WHERE id=? AND organisation_id=? AND school_id=?",
                UUID.class,actorMembershipId,organisationId,schoolId);
        var actor=new AuthenticatedUser(actorUserId,UUID.randomUUID(),"synthetic-fee-document@example.test",null,
                "Synthetic document-source collector",true,organisationId,Set.of(actorMembershipId),
                Set.of("fees.read","fees.configure","fees.approve","fees.collect"),List.of());
        return inManualFinanceScope(actor, () -> {
            var heads=jdbc.queryForList("SELECT id FROM fee_heads WHERE organisation_id=? AND school_id=? AND code='SYN_DOC_SOURCE'",
                    UUID.class,organisationId,schoolId);
            UUID headId=heads.isEmpty() ? finance.createFeeHead(actor,new FeeHeadRequest(schoolId,"SYN_DOC_SOURCE",
                    "Synthetic document-source tuition",null,"TUITION",false,"ACTIVE",null,"Synthetic receipt source setup")).id() : heads.getFirst();
            var plans=jdbc.queryForList("SELECT id FROM fee_plans WHERE organisation_id=? AND school_id=? AND academic_session_id=? AND plan_code='SYN_DOC_SOURCE' AND status='ACTIVE'",
                    UUID.class,organisationId,schoolId,sessionId);
            UUID planId;
            if(plans.isEmpty()) {
                planId=finance.createPlan(actor,new FeePlanRequest(schoolId,sessionId,null,"SYN_DOC_SOURCE",1,
                        "Synthetic receipt-source fees","VI","INR","SYN/DOC",null,null,"Synthetic configured receipt source")).id();
                finance.addPlanItem(actor,planId,new PlanItemRequest(schoolId,headId,1,"Synthetic document-source tuition",
                        LocalDate.of(2026,8,22),new BigDecimal("1850.00"),true,"Synthetic charge source"));
                finance.activatePlan(actor,planId,new PlanActivationRequest(schoolId,0,false,"Synthetic authorized fee plan activation"));
            } else planId=plans.getFirst();
            var account=finance.assignPlan(actor,new PlanAssignmentRequest(schoolId,planId,enrolmentId,"Synthetic document-source account"));
            String reference="SYN_DOC_BANK_"+prefix+"_"+base;
            return finance.recordPayment(actor,"synthetic-document-payment-"+prefix+"-"+base,
                    new PaymentRequest(schoolId,account.id(),"BANK_TRANSFER",reference,new BigDecimal("1850.00"),
                            LocalDate.of(2026,8,22),"Synthetic bank-credit fixture only; no real bank payment",List.of(),
                            Instant.parse("2026-08-22T04:31:00Z"),null,
                            new com.educore.api.finance.ManualCollectionEvidence.BankCreditVerification("BANK_STATEMENT_CREDIT",true,reference),null)).id();
        });
    }

    /** Narrow test context matching a real human collection; never disable its deferred integrity checks. */
    private <T> T inManualFinanceScope(AuthenticatedUser actor,java.util.function.Supplier<T> action) {
        String previousUser=jdbc.queryForObject("SELECT current_setting('app.current_user_id',true)",String.class);
        String previousOrganisation=jdbc.queryForObject("SELECT current_setting('app.current_organisation_id',true)",String.class);
        String manualConstraints="fee_payments_manual_evidence_provenance,fee_manual_evidence_receipt_provenance,"
                +"fee_payment_allocations_manual_evidence_provenance,fee_ledger_entries_manual_evidence_provenance";
        try(var ignored=TenantRequestContext.open(actor.userId(),actor.activeOrganisationId())) {
            jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)",String.class,actor.userId().toString());
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,actor.activeOrganisationId().toString());
            T result=action.get();
            jdbc.execute("SET CONSTRAINTS "+manualConstraints+" IMMEDIATE");
            return result;
        } finally {
            jdbc.execute("SET CONSTRAINTS "+manualConstraints+" DEFERRED");
            jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)",String.class,previousUser==null?"":previousUser);
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,previousOrganisation==null?"":previousOrganisation);
        }
    }

    private static List<TenderDeclaration> closeDeclarations(String cashAmount) {
        return List.of(
                new TenderDeclaration("CASH", new BigDecimal(cashAmount)),
                new TenderDeclaration("BANK_TRANSFER", BigDecimal.ZERO),
                new TenderDeclaration("CHEQUE", BigDecimal.ZERO),
                new TenderDeclaration("SCHOOL_UPI", BigDecimal.ZERO),
                new TenderDeclaration("ONLINE_PROVIDER", BigDecimal.ZERO));
    }

    private static UUID uuid(String prefix, int suffix) {
        return UUID.fromString(prefix + "-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @Test
    void appliesV035PayrollAccountingWithRequiredGuards() {
        assertThat(Arrays.stream(flyway.info().applied()).map(item -> item.getScript()))
                .contains("V035__payroll_accounting_posting_and_payments.sql");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public'
                  AND tablename IN ('payroll_accounting_configurations',
                    'payroll_accounting_component_mappings','payroll_accounting_tender_mappings',
                    'payroll_accounting_posting_batches','payroll_accounting_posting_items')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal
                  AND tgname LIKE 'payroll_accounting_%'
                """, Integer.class)).isEqualTo(8);
    }

    @Test
    @Transactional
    void payrollAccountingIsMakerCheckerRetrySafeLedgerDerivedAndReversedInDependencyOrder() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId(), run = f.payroll().runId();
        var accrualRequest = new PayrollAccountingController.AccrualRequest(school, f.yearId(),
                LocalDate.of(2025, 8, 31), "Synthetic finalized salary accrual");
        var paymentRequest = new PayrollAccountingController.PaymentRequest(school, f.yearId(),
                LocalDate.of(2025, 9, 1), "CASH", null, List.of(f.resultId()), "Synthetic salary settlement");
        assertThatThrownBy(() -> payrollAccounting.workspace(f.maker(), UUID.randomUUID()))
                .hasMessageContaining("unavailable for this school");
        var teacher = payrollAccountingActor(f.payroll().rollover().teacherUserId(),
                f.payroll().rollover().teacherMembershipId(), f.payroll().rollover().organisationId());
        assertThatThrownBy(() -> payrollAccounting.workspace(teacher, school))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollAccounting.preparePayment(f.maker(), run,
                "synthetic-payment-too-early", paymentRequest)).hasMessageContaining("accrual must receive");
        assertThatThrownBy(() -> payrollAccounting.prepareAccrual(f.maker(), run, "synthetic-wrong-date",
                new PayrollAccountingController.AccrualRequest(school, f.yearId(),
                        LocalDate.of(2025, 8, 30), "Synthetic incorrect accrual date")))
                .hasMessageContaining("period end date");

        var first = payrollAccounting.prepareAccrual(f.maker(), run, "synthetic-payroll-accrual-1", accrualRequest);
        assertThat(payrollAccounting.prepareAccrual(f.maker(), run,
                "synthetic-payroll-accrual-1", accrualRequest)).isEqualTo(first);
        assertThatThrownBy(() -> payrollAccounting.prepareAccrual(f.maker(), run,
                "synthetic-payroll-accrual-2", accrualRequest)).hasMessageContaining("active accrual");
        UUID firstJournal = payrollJournal(first.id());
        accounting.decideJournal(f.approver(), firstJournal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "REJECT", "Synthetic independent accrual rejection", 1));
        var accrual = payrollAccounting.prepareAccrual(f.maker(), run,
                "synthetic-payroll-accrual-2", accrualRequest);
        UUID accrualJournal = payrollJournal(accrual.id());
        assertThat(jdbc.queryForObject("SELECT status FROM payroll_accounting_posting_items WHERE posting_batch_id=?",
                String.class, first.id())).isEqualTo("RELEASED");
        assertThatThrownBy(() -> accounting.decideJournal(f.maker(), accrualJournal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "POST", "Synthetic denied self posting", 1)))
                .hasMessageContaining("different authorized person");
        postPayrollJournal(f, accrualJournal);
        assertThat(payrollAccounting.workspace(f.maker(), school).runs()).singleElement()
                .satisfies(item -> assertThat(item.accrualStatus()).isEqualTo("POSTED"));
        assertThatThrownBy(() -> payrollAccounting.preparePayment(f.maker(), run, "synthetic-bank-no-ref",
                new PayrollAccountingController.PaymentRequest(school, f.yearId(), LocalDate.of(2025, 9, 1),
                        "BANK_TRANSFER", null, List.of(f.resultId()), "Synthetic absent bank reference")))
                .hasMessageContaining("reference for non-cash");
        var payment = payrollAccounting.preparePayment(f.maker(), run, "synthetic-payroll-payment-1", paymentRequest);
        assertThat(payrollAccounting.preparePayment(f.maker(), run,
                "synthetic-payroll-payment-1", paymentRequest)).isEqualTo(payment);
        assertThatThrownBy(() -> payrollAccounting.preparePayment(f.maker(), run,
                "synthetic-payroll-payment-2", paymentRequest)).hasMessageContaining("active payment journal");
        assertThatThrownBy(() -> payrollAccounting.preparePayment(f.maker(), run,
                "synthetic-payroll-payment-1", new PayrollAccountingController.PaymentRequest(
                        school, f.yearId(), LocalDate.of(2025, 9, 2), "CASH", null,
                        List.of(f.resultId()), "Synthetic changed retry request")))
                .hasMessageContaining("retry key was already used");
        UUID paymentJournal = payrollJournal(payment.id());
        postPayrollJournal(f, paymentJournal);
        var paid = payrollAccounting.workspace(f.maker(), school);
        assertThat(paid.runs().getFirst().staff()).singleElement()
                .satisfies(item -> assertThat(item.paymentStatus()).isEqualTo("PAID"));
        var ledger = accounting.snapshot(f.maker(), school, f.yearId(),
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        assertThat(ledger.statements().periodSurplus()).isEqualByComparingTo("-20000.00");
        assertThat(ledger.statements().cash()).isEqualByComparingTo("-20000.00");
        assertThat(jdbc.queryForObject("""
                SELECT sum(line.debit_amount-line.credit_amount) FROM accounting_journal_lines line
                JOIN accounting_journals journal ON journal.id=line.accounting_journal_id
                WHERE line.account_id=? AND journal.status='POSTED'
                """, BigDecimal.class, f.payableId())).isEqualByComparingTo("0.00");
        // Force the deferred atomic-batch gate inside this rollback-only integration transaction.
        jdbc.execute("SET CONSTRAINTS payroll_accounting_posting_batches_complete IMMEDIATE");
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM payroll_accounting_posting_items WHERE posting_batch_id=?",
                payment.id()), "append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_accounting_component_mappings SET expense_account_id=? WHERE configuration_id=?
                """, f.expenseId(), f.configurationId()), "editable only while");
        assertDatabaseRejected(() -> payrollRuns.requestReversal(f.maker(), run,
                "synthetic-run-before-ledger", new PayrollRunController.RunReversalRequest(
                        school, 2L, "Synthetic invalid financial reversal order")), "protected payroll history");
        UUID august = jdbc.queryForObject("SELECT accounting_period_id FROM accounting_journals WHERE id=?",
                UUID.class, accrualJournal);
        var accrualReversal = accounting.requestReversal(f.maker(), accrualJournal,
                new ReversalRequest(school, august, LocalDate.of(2025, 8, 31), "Synthetic accrual correction"));
        assertDatabaseRejected(() -> accounting.decideReversal(f.approver(), accrualReversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "APPROVE", "Synthetic wrong reversal order", 0)), "active salary payment journals");
        UUID september = jdbc.queryForObject("SELECT accounting_period_id FROM accounting_journals WHERE id=?",
                UUID.class, paymentJournal);
        var paymentReversal = accounting.requestReversal(f.maker(), paymentJournal,
                new ReversalRequest(school, september, LocalDate.of(2025, 9, 2), "Synthetic payment correction"));
        accounting.decideReversal(f.approver(), paymentReversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "APPROVE", "Synthetic independent payment reversal", 0));
        accounting.decideReversal(f.approver(), accrualReversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "APPROVE", "Synthetic independent accrual reversal", 0));
        var reversal = payrollRuns.requestReversal(f.maker(), run, "synthetic-run-after-ledger",
                new PayrollRunController.RunReversalRequest(school, 2L, "Synthetic complete financial reversal"));
        payrollRuns.decideReversal(f.approver(), reversal.id(),
                new PayrollRunController.RunReversalDecisionRequest(school, "APPROVE", 0L,
                        "Synthetic independent payroll correction"));
        assertThat(jdbc.queryForObject("SELECT status FROM payroll_runs WHERE id=?", String.class, run))
                .isEqualTo("REVERSED");
        assertThat(accounting.snapshot(f.maker(), school, f.yearId(), LocalDate.of(2025, 4, 1),
                LocalDate.of(2026, 3, 31)).statements().trialDebit()).isEqualByComparingTo("0.00");
        var history = payrollReports.register(f.maker(), school, new com.educore.api.payroll.PayrollReports.Query(
                LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 1), null, "", 0, 25));
        assertThat(history.items()).singleElement().satisfies(row -> {
            assertThat(row.runStatus()).isEqualTo("REVERSED");
            assertThat(row.paymentStatus()).isEqualTo("REVERSED");
            assertThat(row.net()).isEqualByComparingTo("20000.00");
            assertThat(row.unsettled()).isEqualByComparingTo("0.00");
        });
        assertThat(payrollReports.summary(f.maker(), school,
                new com.educore.api.payroll.PayrollReports.Query(LocalDate.of(2025, 8, 1),
                        LocalDate.of(2025, 8, 1), null, "", 0, 25),
                com.educore.api.payroll.PayrollReports.GroupBy.DESIGNATION).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.runStatus()).isEqualTo("REVERSED");
                    assertThat(row.currencyCode()).isEqualTo("INR");
                    assertThat(row.net()).isEqualByComparingTo("20000.00");
                });
        var reconciled = payrollReports.reconciliation(f.maker(), school, new com.educore.api.payroll.PayrollReports.Query(
                LocalDate.of(2025, 8, 1), LocalDate.of(2025, 9, 30), null, "", 0, 25));
        assertThat(reconciled.items()).hasSize(3).allSatisfy(row -> assertThat(row.issues()).isEmpty());
        assertThat(reconciled.items()).extracting(com.educore.api.payroll.PayrollReports.ReconciliationRow::reconciliationStatus)
                .containsExactlyInAnyOrder("MATCHED_REJECTED", "MATCHED_REVERSED", "MATCHED_REVERSED");
    }

    @Test
    void appliesV036PayrollHistoryReportingIndexes() {
        assertThat(flyway.info().applied()).extracting(info -> info.getScript())
                .contains("V036__payroll_history_reporting_indexes.sql");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname='public'
                  AND indexname='payroll_accounting_batches_school_posting_date_idx'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void appliesV037FrozenPayrollAssignmentDimensions() {
        assertThat(flyway.info().applied()).extracting(info -> info.getScript())
                .contains("V037__frozen_payroll_assignment_dimensions.sql");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='public'
                  AND table_name='payroll_run_staff_results' AND column_name IN (
                    'assignment_snapshot_status','assignment_snapshot_date','assignment_id_snapshot',
                    'designation_name_snapshot','department_name_snapshot')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='payroll_results_assignment_identity_fk'
                """, String.class)).contains("staff_profile_id", "organisation_id", "school_id");
    }

    @Test
    @Transactional
    void payrollAssignmentSnapshotsAreEffectiveDatedScopedAndNeverGuessAmbiguity() {
        var f = insertAcademicRolloverFixture();
        var actor = payrollAccountingActor(f.actorUserId(), f.actorMembershipId(), f.organisationId());
        UUID staff = uuid("50000000", 13), original = uuid("50000000", 14), designation = uuid("50000000", 12);
        LocalDate august = LocalDate.of(2025, 8, 31);
        var initial = payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), august, "payroll.run.prepare");
        assertThat(initial.get(staff).status()).isEqualTo("CAPTURED");
        assertThat(initial.get(staff).assignmentId()).isEqualTo(original);
        assertThat(payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), august, "payroll.run.prepare")).isEqualTo(initial);
        assertThatThrownBy(() -> payrollPeople.payrollAssignmentSnapshots(actor, UUID.randomUUID(), august, "payroll.run.prepare"))
                .hasMessageContaining("unavailable for this school");
        var teacher = payrollAccountingActor(f.teacherUserId(), f.teacherMembershipId(), f.organisationId());
        assertThatThrownBy(() -> payrollPeople.payrollAssignmentSnapshots(teacher, f.schoolId(), august, "payroll.run.prepare"))
                .hasMessageContaining("unavailable for this school");
        var foreign = payrollAccountingActor(f.actorUserId(), f.actorMembershipId(), UUID.randomUUID());
        assertThatThrownBy(() -> payrollPeople.payrollAssignmentSnapshots(foreign, f.schoolId(), august, "payroll.run.prepare"))
                .hasMessageContaining("unavailable for this school");
        jdbc.update("""
                UPDATE staff_assignments SET status='ENDED',ends_on=DATE '2025-08-15',
                  ending_reason='Synthetic assignment transition',ended_by_membership_id=? WHERE id=?
                """, f.actorMembershipId(), original);
        assertThat(payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), august, "payroll.run.prepare").get(staff).status())
                .isEqualTo("NO_ASSIGNMENT");
        UUID department = UUID.randomUUID(), current = UUID.randomUUID(), planned = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO staff_departments(id,organisation_id,school_id,code,display_name,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN_REPORT','Synthetic report department','ACTIVE',?,now(),now(),0)
                """, department, f.organisationId(), f.schoolId(), f.actorMembershipId());
        jdbc.update("""
                INSERT INTO staff_assignments(id,organisation_id,school_id,staff_profile_id,staff_designation_id,
                  staff_department_id,campus_id,employment_type,employer_kind,primary_assignment,starts_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?,'FULL_TIME','SCHOOL',true,DATE '2025-08-16','ACTIVE',?,now(),now(),0),
                       (?, ?, ?, ?, ?, NULL, ?,'FULL_TIME','SCHOOL',true,DATE '2026-01-01','PLANNED',?,now(),now(),0)
                """, current, f.organisationId(), f.schoolId(), staff, designation, department, f.campusId(), f.actorMembershipId(),
                planned, f.organisationId(), f.schoolId(), staff, designation, f.campusId(), f.actorMembershipId());
        var after = payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), august, "payroll.run.prepare").get(staff);
        assertThat(after.assignmentId()).isEqualTo(current);
        assertThat(after.departmentName()).isEqualTo("Synthetic report department");
        assertThat(payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), LocalDate.of(2025, 8, 10), "payroll.run.prepare")
                .get(staff).assignmentId()).isEqualTo(original);
        // Contradictory historical records are visible, never resolved by an arbitrary LIMIT 1.
        jdbc.update("""
                INSERT INTO staff_assignments(id,organisation_id,school_id,staff_profile_id,staff_designation_id,
                  campus_id,employment_type,employer_kind,primary_assignment,starts_on,ends_on,status,
                  ending_reason,ended_by_membership_id,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?,'FULL_TIME','SCHOOL',true,DATE '2025-08-20',DATE '2025-08-31','ENDED',
                  'Synthetic overlapping history',?,?,now(),now(),0)
                """, UUID.randomUUID(), f.organisationId(), f.schoolId(), staff, designation, f.campusId(),
                f.actorMembershipId(), f.actorMembershipId());
        var ambiguous = payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), august, "payroll.run.prepare").get(staff);
        assertThat(ambiguous.status()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.assignmentId()).isNull();
        assertThat(ambiguous.departmentName()).isNull();
        jdbc.update("""
                UPDATE staff_assignments SET status='CANCELLED',ends_on=starts_on,ending_reason='Synthetic cancellation',
                  ended_by_membership_id=? WHERE id=?
                """, f.actorMembershipId(), planned);
        jdbc.update("""
                UPDATE staff_assignments SET status='ENDED',ends_on=DATE '2025-08-18',ending_reason='Synthetic departure',
                  ended_by_membership_id=? WHERE id=?
                """, f.actorMembershipId(), current);
        jdbc.update("""
                UPDATE staff_profiles SET status='ENDED',ended_on=DATE '2025-08-18',ending_reason='Synthetic departure',
                  ended_by_membership_id=? WHERE id=?
                """, f.actorMembershipId(), staff);
        var ended = payrollPeople.payrollAssignmentSnapshots(actor, f.schoolId(), august, "payroll.run.prepare").get(staff);
        assertThat(ended.status()).isEqualTo("CAPTURED");
        assertThat(ended.asOfDate()).isEqualTo(LocalDate.of(2025, 8, 18));
        assertThat(ended.assignmentId()).isEqualTo(current);
    }

    @Test
    @Transactional
    void payrollReportsAreScopedPaginatedAuditedAndLedgerReconciled() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId(), run = f.payroll().runId();
        var query = new com.educore.api.payroll.PayrollReports.Query(LocalDate.of(2025, 8, 1),
                LocalDate.of(2025, 8, 1), null, "", 0, 1);
        var batchQuery = new com.educore.api.payroll.PayrollReports.Query(LocalDate.of(2025, 8, 1),
                LocalDate.of(2025, 9, 30), null, "", 0, 25);
        var first = payrollReports.register(f.maker(), school, query);
        assertThat(first.total()).isEqualTo(1);
        assertThat(first.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(f.resultId());
            assertThat(row.accrualStatus()).isEqualTo("NOT_PREPARED");
            assertThat(row.paymentStatus()).isEqualTo("UNPAID");
            assertThat(row.paid()).isEqualByComparingTo("0.00");
            assertThat(row.unsettled()).isEqualByComparingTo(row.net());
            assertThat(row.assignmentSnapshotStatus()).isEqualTo("NOT_CAPTURED");
            assertThat(row.assignmentSnapshotDate()).isNull();
            assertThat(row.designationName()).isNull();
        });
        assertThat(payrollReports.register(f.maker(), school, query).items()).isEqualTo(first.items());
        var legacySummary = payrollReports.summary(f.maker(), school, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DEPARTMENT);
        assertThat(legacySummary.total()).isEqualTo(1);
        assertThat(legacySummary.items()).singleElement().satisfies(row -> {
            assertThat(row.groupBy()).isEqualTo("DEPARTMENT");
            assertThat(row.dimensionStatus()).isEqualTo("NOT_CAPTURED");
            assertThat(row.dimensionLabel()).isEqualTo("Historical classification not captured");
            assertThat(row.runStatus()).isEqualTo("FINALIZED");
            assertThat(row.currencyCode()).isEqualTo("INR");
            assertThat(row.payrollRunCount()).isEqualTo(1);
            assertThat(row.staffResultCount()).isEqualTo(1);
            assertThat(row.gross()).isEqualByComparingTo(first.items().getFirst().gross());
            assertThat(row.net()).isEqualByComparingTo(first.items().getFirst().net());
        });
        assertThat(payrollReports.summary(f.maker(), school, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DEPARTMENT).items()).isEqualTo(legacySummary.items());
        assertThat(payrollReports.summary(f.maker(), school, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DEDUCTION_COMPONENT).items()).isEmpty();
        var beyond = payrollReports.register(f.maker(), school, new com.educore.api.payroll.PayrollReports.Query(
                query.from(), query.to(), null, "", 1, 1));
        assertThat(beyond.total()).isEqualTo(1);
        assertThat(beyond.items()).isEmpty();
        assertThat(payrollReports.register(f.maker(), school, new com.educore.api.payroll.PayrollReports.Query(
                query.from(), query.to(), first.items().getFirst().staffProfileId(),
                first.items().getFirst().employeeCode().toLowerCase(java.util.Locale.ROOT), 0, 1)).items()).hasSize(1);
        assertThat(payrollReports.register(f.maker(), school, new com.educore.api.payroll.PayrollReports.Query(
                query.from(), query.to(), UUID.randomUUID(), "", 0, 1)).total()).isZero();
        assertThatThrownBy(() -> payrollReports.register(f.maker(), UUID.randomUUID(), query))
                .hasMessageContaining("unavailable for this school");
        var teacher = payrollAccountingActor(f.payroll().rollover().teacherUserId(),
                f.payroll().rollover().teacherMembershipId(), f.payroll().rollover().organisationId());
        assertThatThrownBy(() -> payrollReports.register(teacher, school, query)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollReports.exportRegister(teacher, school, query)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollReports.reconciliation(teacher, school, batchQuery)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollReports.exportReconciliation(teacher, school, batchQuery)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollReports.summary(teacher, school, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DESIGNATION)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollReports.exportSummary(teacher, school, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DESIGNATION)).hasMessageContaining("unavailable for this school");
        var accrual = payrollAccounting.prepareAccrual(f.maker(), run, "synthetic-report-accrual",
                new PayrollAccountingController.AccrualRequest(school, f.yearId(), LocalDate.of(2025, 8, 31),
                        "Synthetic report accrual"));
        var pending = payrollReports.reconciliation(f.maker(), school, batchQuery);
        assertThat(pending.items()).singleElement().satisfies(row -> {
            assertThat(row.reconciliationStatus()).isEqualTo("AWAITING_APPROVAL");
            assertThat(row.issues()).isEmpty();
            assertThat(row.frozenPayrollAmount()).isEqualByComparingTo(row.journalDebit());
        });
        postPayrollJournal(f, payrollJournal(accrual.id()));
        var payment = payrollAccounting.preparePayment(f.maker(), run, "synthetic-report-payment",
                new PayrollAccountingController.PaymentRequest(school, f.yearId(), LocalDate.of(2025, 9, 1),
                        "CASH", null, List.of(f.resultId()), "Synthetic report salary evidence"));
        assertThat(payrollReports.register(f.maker(), school, query).items()).singleElement().satisfies(row -> {
            assertThat(row.paymentStatus()).isEqualTo("AWAITING_APPROVAL");
            assertThat(row.pending()).isEqualByComparingTo(row.net());
            assertThat(row.paid()).isEqualByComparingTo("0.00");
        });
        postPayrollJournal(f, payrollJournal(payment.id()));
        assertThat(payrollReports.register(f.maker(), school, query).items()).singleElement().satisfies(row -> {
            assertThat(row.paymentStatus()).isEqualTo("PAID");
            assertThat(row.paid()).isEqualByComparingTo(row.net());
            assertThat(row.pending()).isEqualByComparingTo("0.00");
            assertThat(row.unsettled()).isEqualByComparingTo("0.00");
        });
        var pagedBatches = payrollReports.reconciliation(f.maker(), school,
                new com.educore.api.payroll.PayrollReports.Query(batchQuery.from(), batchQuery.to(), null, "", 0, 1));
        assertThat(pagedBatches.total()).isEqualTo(2);
        assertThat(pagedBatches.items()).singleElement().satisfies(row -> assertThat(row.postingType()).isEqualTo("PAYMENT"));
        assertThat(payrollReports.reconciliation(f.maker(), school, batchQuery).items()).allSatisfy(row -> {
            assertThat(row.reconciliationStatus()).isEqualTo("MATCHED_POSTED");
            assertThat(row.issues()).isEmpty();
        });
        String csv = payrollReports.exportRegister(f.maker(), school, query);
        assertThat(csv).startsWith("\uFEFF\"Payroll month\"").contains(f.resultId().toString(), "\"PAID\"")
                .doesNotContain("password", "bank_account", "source_snapshot", "idempotency_key");
        assertThat(payrollReports.exportReconciliation(f.maker(), school, batchQuery))
                .contains(accrual.id().toString(), payment.id().toString(), "MATCHED_POSTED");
        assertThat(payrollReports.exportSummary(f.maker(), school, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DEPARTMENT))
                .startsWith("\uFEFF\"Grouping\"").contains("\"NOT_CAPTURED\"", "\"INR\"")
                .doesNotContain("password", "bank_account", "source_snapshot", "idempotency_key");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND action='payroll.report.exported' AND outcome='SUCCEEDED'
                """, Integer.class, f.payroll().rollover().organisationId(), school)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND action LIKE 'payroll.report.%'
                  AND (jsonb_exists(safe_metadata,'staffName') OR jsonb_exists(safe_metadata,'search')
                    OR jsonb_exists(safe_metadata,'amount'))
                """, Integer.class, f.payroll().rollover().organisationId(), school)).isZero();
        // A genuinely separate school/organisation, not the fixed-ID primary payroll fixture twice.
        UUID foreignOrg = UUID.randomUUID(), foreignSchool = UUID.randomUUID();
        UUID foreignUser = UUID.randomUUID(), foreignMembership = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,default_locale,
                  created_at,updated_at,row_version)
                VALUES (?, 'synthetic-payroll-report-org', 'Synthetic Report Organisation',
                  'Synthetic Report Organisation', 'ACTIVE', 'en-IN', now(), now(), 0)
                """, foreignOrg);
        jdbc.update("""
                INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,time_zone,
                  currency_code,status,created_at,updated_at,row_version)
                VALUES (?, ?, 'synthetic-payroll-report-school', 'Synthetic Report School',
                  'Synthetic Report School', 'Asia/Kolkata', 'INR', 'ACTIVE', now(), now(), 0)
                """, foreignSchool, foreignOrg);
        insertSyntheticUser(foreignUser, "synthetic-payroll-report-foreign@example.test");
        insertSyntheticMembership(foreignMembership, foreignUser, foreignOrg, foreignSchool);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                foreignMembership, UUID.fromString("00000000-0000-4000-8000-000000000001"));
        var foreign = payrollAccountingActor(foreignUser, foreignMembership, foreignOrg);
        assertThat(payrollReports.register(foreign, foreignSchool, query).total()).isZero();
        assertThat(payrollReports.reconciliation(foreign, foreignSchool, batchQuery).items()).isEmpty();
        assertThat(payrollReports.summary(foreign, foreignSchool, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DESIGNATION).items()).isEmpty();
        assertThat(payrollReports.exportRegister(foreign, foreignSchool, query)).doesNotContain(f.resultId().toString());
        assertThat(payrollReports.exportSummary(foreign, foreignSchool, query,
                com.educore.api.payroll.PayrollReports.GroupBy.DESIGNATION)).doesNotContain(f.resultId().toString());
        assertThatThrownBy(() -> payrollReports.register(foreign, school, query)).hasMessageContaining("unavailable for this school");
        assertThat(accounting.payrollJournalEvidence(foreign, foreignSchool,
                List.of(payrollJournal(accrual.id())))).isEmpty();
    }

    @Test
    @Transactional
    void payrollAccountingEvidenceRemainsOrganisationIsolatedUnderRowSecurity() {
        var f = insertPayrollAccountingFixture();
        payrollAccounting.prepareAccrual(f.maker(), f.payroll().runId(), "synthetic-payroll-rls",
                new PayrollAccountingController.AccrualRequest(f.payroll().rollover().schoolId(),
                        f.yearId(), LocalDate.of(2025, 8, 31), "Synthetic RLS accounting evidence"));
        jdbc.execute("SET CONSTRAINTS payroll_accounting_posting_batches_complete IMMEDIATE");
        jdbc.execute("CREATE ROLE educore_payroll_accounting_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_payroll_accounting_rls_test");
        List<String> tables = List.of("payroll_accounting_configurations", "payroll_accounting_component_mappings",
                "payroll_accounting_tender_mappings", "payroll_accounting_posting_batches", "payroll_accounting_posting_items");
        for (String table : tables) jdbc.execute("GRANT SELECT,UPDATE ON " + table + " TO educore_payroll_accounting_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_payroll_accounting_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class,
                f.payroll().rollover().organisationId().toString());
        for (String table : tables) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isPositive();
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class,
                uuid("71000000", 99).toString());
        for (String table : tables) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
            assertThat(jdbc.update("UPDATE " + table + " SET school_id=school_id")).isZero();
        }
        jdbc.execute("RESET ROLE");
    }

    @Test
    @Transactional
    void payrollAccountingBalancesDeductionsAndEmployerContributions() {
        var f = insertPayrollAccountingFixture(true);
        UUID school = f.payroll().rollover().schoolId();
        var batch = payrollAccounting.prepareAccrual(f.maker(), f.payroll().runId(), "synthetic-mixed-accrual",
                new PayrollAccountingController.AccrualRequest(school, f.yearId(), LocalDate.of(2025, 8, 31),
                        "Synthetic mixed salary accrual"));
        UUID journal = payrollJournal(batch.id());
        postPayrollJournal(f, journal);
        assertThat(jdbc.queryForObject("""
                SELECT sum(debit_amount) FROM accounting_journal_lines WHERE accounting_journal_id=?
                """, BigDecimal.class, journal)).isEqualByComparingTo("20500.00");
        assertThat(jdbc.queryForObject("""
                SELECT sum(credit_amount) FROM accounting_journal_lines WHERE accounting_journal_id=?
                """, BigDecimal.class, journal)).isEqualByComparingTo("20500.00");
        assertThat(jdbc.queryForObject("""
                SELECT credit_amount FROM accounting_journal_lines
                WHERE accounting_journal_id=? AND account_id=?
                """, BigDecimal.class, journal, f.payableId())).isEqualByComparingTo("19000.00");
        assertThat(jdbc.queryForObject("""
                SELECT credit_amount FROM accounting_journal_lines
                WHERE accounting_journal_id=? AND account_code_snapshot='L2200.DEDUCTIONS'
                """, BigDecimal.class, journal)).isEqualByComparingTo("1500.00");
        var payment = payrollAccounting.preparePayment(f.maker(), f.payroll().runId(), "synthetic-mixed-payment",
                new PayrollAccountingController.PaymentRequest(school, f.yearId(), LocalDate.of(2025, 9, 1),
                        "CASH", null, List.of(f.resultId()), "Synthetic net-only salary settlement"));
        postPayrollJournal(f, payrollJournal(payment.id()));
        var ledger = accounting.snapshot(f.maker(), school, f.yearId(),
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        assertThat(ledger.statements().cash()).isEqualByComparingTo("-19000.00");
        assertThat(ledger.statements().periodSurplus()).isEqualByComparingTo("-20500.00");
        // Deductions and employer liabilities remain payable; salary settlement never erases them.
        assertThat(jdbc.queryForObject("""
                SELECT sum(line.credit_amount-line.debit_amount) FROM accounting_journal_lines line
                JOIN accounting_journals j ON j.id=line.accounting_journal_id
                WHERE j.school_id=? AND j.status='POSTED' AND line.account_code_snapshot='L2200.DEDUCTIONS'
                """, BigDecimal.class, school)).isEqualByComparingTo("1500.00");
        jdbc.execute("SET CONSTRAINTS payroll_accounting_posting_batches_complete IMMEDIATE");
    }


    @Test
    void appliesV038EmployeeAdvancesWithRequiredGuards() {
        assertThat(Arrays.stream(flyway.info().applied()).map(item -> item.getScript()))
                .contains("V038__governed_employee_advances_and_disbursement.sql");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies WHERE schemaname='public'
                  AND tablename IN ('payroll_employee_advances','payroll_employee_advance_installments',
                    'payroll_employee_advance_history','payroll_employee_advance_postings')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN (
                  'payroll_employee_advances_context_guard','payroll_employee_advances_lifecycle_guard',
                  'payroll_employee_advance_installments_guard','payroll_employee_advances_complete',
                  'payroll_employee_advance_history_guard','payroll_employee_advance_postings_guard',
                  'payroll_employee_advance_postings_complete','payroll_employee_advance_journal_lifecycle_guard',
                  'payroll_employee_advances_no_delete','accounting_journal_lines_identity_guard')
                """, Integer.class)).isEqualTo(10);
    }

    @Test
    @Transactional
    void employeeAdvancesAreScopedRetrySafeIndependentlyApprovedAndLedgerDerived() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId();
        var request = syntheticAdvance(f);
        var created = payrollAdvances.create(f.maker(), "synthetic-advance-create", request);
        assertThat(payrollAdvances.create(f.maker(), "synthetic-advance-create", request)).isEqualTo(created);
        var updated = payrollAdvances.update(f.maker(), created.id(),
                new PayrollAdvanceController.UpdateAdvanceRequest(request, 0L));
        assertThat(updated.rowVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> payrollAdvances.update(f.maker(), created.id(),
                new PayrollAdvanceController.UpdateAdvanceRequest(request, 0L))).hasMessageContaining("record changed");
        payrollAdvances.submit(f.maker(), created.id(),
                new PayrollAdvanceController.VersionedReasonRequest(school, 1L, "Synthetic submission"));
        assertThatThrownBy(() -> payrollAdvances.decide(f.maker(), created.id(),
                new PayrollAdvanceController.DecisionRequest(school, "APPROVE", 2L, "Synthetic denied approval")))
                .hasMessageContaining("different authorized person");
        payrollAdvances.decide(f.approver(), created.id(),
                new PayrollAdvanceController.DecisionRequest(school, "APPROVE", 2L, "Synthetic independent approval"));
        assertThat(payrollAdvances.workspace(f.maker(), school).advances()).singleElement().satisfies(a -> {
            assertThat(a.installments()).extracting(x -> x.plannedAmount())
                    .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.35"));
            assertThat(a.history()).hasSize(4);
            assertThat(a.postedDisbursementAmount()).isEqualByComparingTo("0");
            assertThat(a.recoveryStatus()).isEqualTo("NOT_DISBURSED");
            assertThat(a.recoveredAmount()).isEqualByComparingTo("0");
            assertThat(a.outstandingAmount()).isNull();
            assertThat(a.canEdit()).isFalse();
        });
        var disbursement = new PayrollAdvanceController.DisbursementRequest(school, "Synthetic disbursement");
        var first = payrollAdvances.prepareDisbursement(f.maker(), created.id(), "synthetic-advance-post-1", disbursement);
        assertThat(payrollAdvances.prepareDisbursement(f.maker(), created.id(),
                "synthetic-advance-post-1", disbursement)).isEqualTo(first);
        assertThatThrownBy(() -> payrollAdvances.prepareDisbursement(f.maker(), created.id(),
                "synthetic-advance-post-2", disbursement)).hasMessageContaining("disbursement");
        UUID firstJournal = advanceJournal(first.id());
        accounting.decideJournal(f.approver(), firstJournal,
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "REJECT", "Synthetic independent rejection", 1));
        var second = payrollAdvances.prepareDisbursement(f.maker(), created.id(),
                "synthetic-advance-post-2", disbursement);
        UUID journal = advanceJournal(second.id());
        postPayrollJournal(f, journal);
        assertThat(payrollAdvances.workspace(f.maker(), school).advances()).singleElement().satisfies(a -> {
            assertThat(a.postedDisbursementAmount()).isEqualByComparingTo("100.01");
            assertThat(a.recoveredAmount()).isEqualByComparingTo("0");
            assertThat(a.outstandingAmount()).isEqualByComparingTo("100.01");
            assertThat(a.recoveryStatus()).isEqualTo("NOT_STARTED");
            assertThat(a.operationalStatus()).isEqualTo("ACTIVE");
            assertThat(a.postings()).hasSize(2);
            assertThat(a.canPrepareDisbursement()).isFalse();
        });
        assertThat(jdbc.queryForObject("""
                SELECT sum(debit_amount-credit_amount) FROM accounting_journal_lines WHERE accounting_journal_id=?
                """, BigDecimal.class, journal)).isEqualByComparingTo("0");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_employee_advance_installments SET employee_advance_id=? WHERE employee_advance_id=?
                """, UUID.randomUUID(), created.id()), "identity is immutable");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE accounting_journal_lines SET accounting_journal_id=? WHERE accounting_journal_id=?
                """, UUID.randomUUID(), journal), "identity is immutable");
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM payroll_employee_advance_history WHERE employee_advance_id=?",
                created.id()), "history is immutable");
        UUID period = jdbc.queryForObject("SELECT accounting_period_id FROM accounting_journals WHERE id=?", UUID.class, journal);
        var reversal = accounting.requestReversal(f.maker(), journal,
                new ReversalRequest(school, period, LocalDate.of(2025, 9, 2), "Synthetic disbursement correction"));
        accounting.decideReversal(f.approver(), reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school, "APPROVE", "Synthetic independent reversal", 0));
        assertThat(payrollAdvances.workspace(f.maker(), school).advances()).singleElement().satisfies(a -> {
            assertThat(a.operationalStatus()).isEqualTo("REVERSED");
            assertThat(a.postedDisbursementAmount()).isEqualByComparingTo("0");
            assertThat(a.outstandingAmount()).isNull();
            assertThat(a.recoveryStatus()).isEqualTo("NOT_DISBURSED");
            assertThat(a.canPrepareDisbursement()).isFalse();
        });
        assertThatThrownBy(() -> payrollAdvances.prepareDisbursement(f.maker(), created.id(),
                "synthetic-advance-post-3", disbursement)).hasMessageContaining("disbursement");
        jdbc.execute("SET CONSTRAINTS payroll_employee_advances_complete, payroll_employee_advance_postings_complete IMMEDIATE");
    }

    @Test
    @Transactional
    void employeeAdvancesDenyForeignScopeInvalidReferencesAndHistoricalMutation() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId();
        var request = syntheticAdvance(f);
        var teacher = payrollAccountingActor(f.payroll().rollover().teacherUserId(),
                f.payroll().rollover().teacherMembershipId(), f.payroll().rollover().organisationId());
        assertThatThrownBy(() -> payrollAdvances.workspace(teacher, school)).hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollAdvances.workspace(f.maker(), UUID.randomUUID()))
                .hasMessageContaining("unavailable for this school");
        var foreign = payrollAccountingActor(f.maker().userId(), f.payroll().rollover().actorMembershipId(), UUID.randomUUID());
        assertThatThrownBy(() -> payrollAdvances.create(foreign, "synthetic-foreign", request))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollAdvances.create(f.maker(), "synthetic-invalid-staff",
                new PayrollAdvanceController.AdvanceRequest(school, UUID.randomUUID(), request.advanceType(),
                        request.purpose(), request.financialYearId(), request.issueDate(), request.principalAmount(),
                        request.recoveryStartMonth(), request.installmentCount(), request.receivableAccountId(),
                        request.disbursementAccountId(), request.paymentMethod(), null, request.reason())))
                .hasMessageContaining("employee from this school");
        assertThatThrownBy(() -> payrollAdvances.create(f.maker(), "synthetic-invalid-reference",
                new PayrollAdvanceController.AdvanceRequest(school, request.staffProfileId(), request.advanceType(),
                        request.purpose(), request.financialYearId(), request.issueDate(), request.principalAmount(),
                        request.recoveryStartMonth(), request.installmentCount(), request.receivableAccountId(),
                        request.disbursementAccountId(), "BANK_TRANSFER", null, request.reason())))
                .hasMessageContaining("reference");
        var created = payrollAdvances.create(f.maker(), "synthetic-retry-evidence", request);
        assertDatabaseRejected(() -> {
            jdbc.update("""
                    UPDATE payroll_employee_advance_installments
                    SET installment_number=4,due_month=DATE '2026-01-01'
                    WHERE employee_advance_id=? AND installment_number=1
                    """, created.id());
            jdbc.queryForObject("SELECT validate_complete_payroll_employee_advance(?,?,?)", Object.class,
                    created.id(), f.payroll().rollover().organisationId(), school);
        }, "incomplete or unbalanced");
        assertThatThrownBy(() -> payrollAdvances.create(f.maker(), "synthetic-retry-evidence",
                new PayrollAdvanceController.AdvanceRequest(school, request.staffProfileId(), request.advanceType(),
                        "Changed synthetic purpose", request.financialYearId(), request.issueDate(), request.principalAmount(),
                        request.recoveryStartMonth(), request.installmentCount(), request.receivableAccountId(),
                        request.disbursementAccountId(), request.paymentMethod(), null, request.reason())))
                .hasMessageContaining("retry key");
        assertThat(payrollAdvances.workspace(f.maker(), school,
                new PayrollAdvanceController.Query(1, 1, "", null, null)).advances()).isEmpty();
        assertThat(payrollAdvances.workspace(f.maker(), school,
                new PayrollAdvanceController.Query(0, 1, "%", null, null)).total()).isZero();
        assertThatThrownBy(() -> payrollAdvances.workspace(f.maker(), school,
                new PayrollAdvanceController.Query(0, 101, "", null, null))).hasMessageContaining("page size");
        payrollAdvances.submit(f.maker(), created.id(),
                new PayrollAdvanceController.VersionedReasonRequest(school, 0L, "Synthetic submission"));
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_employee_advances SET status='APPROVED',row_version=row_version+1,
                  submitted_at=submitted_at+INTERVAL '1 minute',decided_by_membership_id=?,
                  decided_at=now(),decision_reason='Synthetic altered submission' WHERE id=?
                """, f.payroll().approverMembershipId(), created.id()), "submission evidence is immutable");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE payroll_employee_advances SET status='APPROVED',row_version=row_version+1,
                  decided_by_membership_id=?,decided_at=now(),decision_reason='Synthetic self decision' WHERE id=?
                """, f.payroll().rollover().actorMembershipId(), created.id()), "independent person");
        payrollAdvances.decide(f.approver(), created.id(),
                new PayrollAdvanceController.DecisionRequest(school, "REJECT", 1L, "Synthetic independent rejection"));
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM payroll_employee_advances WHERE id=?", created.id()), "append-only");
        jdbc.execute("SET CONSTRAINTS payroll_employee_advances_complete IMMEDIATE");
    }


    @Test
    @Transactional
    void employeeAdvanceEvidenceRemainsOrganisationIsolatedUnderRowSecurity() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId();
        var advance = payrollAdvances.create(f.maker(), "synthetic-advance-rls", syntheticAdvance(f));
        payrollAdvances.submit(f.maker(), advance.id(),
                new PayrollAdvanceController.VersionedReasonRequest(school, 0L, "Synthetic RLS submission"));
        payrollAdvances.decide(f.approver(), advance.id(),
                new PayrollAdvanceController.DecisionRequest(school, "APPROVE", 1L, "Synthetic RLS approval"));
        payrollAdvances.prepareDisbursement(f.maker(), advance.id(), "synthetic-advance-rls-post",
                new PayrollAdvanceController.DisbursementRequest(school, "Synthetic RLS posting"));
        jdbc.execute("SET CONSTRAINTS payroll_employee_advances_complete, payroll_employee_advance_postings_complete IMMEDIATE");
        jdbc.execute("CREATE ROLE educore_employee_advance_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_employee_advance_rls_test");
        List<String> tables = List.of("payroll_employee_advances","payroll_employee_advance_installments",
                "payroll_employee_advance_history","payroll_employee_advance_postings");
        for (String table : tables) jdbc.execute("GRANT SELECT,UPDATE ON " + table + " TO educore_employee_advance_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_employee_advance_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class,
                f.payroll().rollover().organisationId().toString());
        for (String table : tables) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isPositive();
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, UUID.randomUUID().toString());
        for (String table : tables) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
            assertThat(jdbc.update("UPDATE " + table + " SET school_id=school_id")).isZero();
        }
        jdbc.execute("RESET ROLE");
    }

    @Test
    @Transactional
    void appliesV040FrozenAdvanceRecoveryGuards() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='040' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename IN ('payroll_run_advance_recoveries','payroll_advance_recovery_fences')", Integer.class)).isEqualTo(2);
        for (String name : List.of("payroll_recoveries_guard_evidence", "payroll_runs_release_recoveries",
                "accounting_advance_recovery_reversal_guard", "payroll_recovery_accrual_guard")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname=? AND NOT tgisinternal", Integer.class, name)).isEqualTo(1);
        }
    }

    @Test
    @Transactional
    void payrollAdvanceRecoveriesAreFrozenRetrySafeIndependentlyApprovedAndLedgerBalanced() {
        var f = insertPayrollAccountingFixture(true);
        when(payrollPayslipObjectStore.bucket()).thenReturn("synthetic-private-bucket");
        UUID school = f.payroll().rollover().schoolId();
        var request = syntheticAdvance(f);
        UUID issue = prepareSyntheticRecoveryIssue(f, request);
        var unposted = payrollRuns.preview(f.maker(), school, LocalDate.of(2025,10,1), "payroll.run.prepare");
        assertThat(unposted.staff().getFirst().recoveries()).isEmpty();
        postPayrollJournal(f, issue);
        BigDecimal recovered = BigDecimal.ZERO;
        for (int month = 10; month <= 12; month++) {
            LocalDate due = LocalDate.of(2025,month,1);
            var preview = payrollRuns.preview(f.maker(), school, due, "payroll.run.prepare");
            assertThat(preview.ready()).as("recovery preview blockers %s", preview.blockers()).isTrue();
            BigDecimal amount = new BigDecimal(month==12 ? "33.35" : "33.33");
            assertThat(preview.staff().getFirst().recoveries()).singleElement().satisfies(line -> {
                assertThat(line.amount()).isEqualByComparingTo(amount);
                assertThat(line.dueMonth()).isEqualTo(due);
            });
            assertThat(preview.deductions()).isEqualByComparingTo(new BigDecimal("1000.00").add(amount));
            assertThat(preview.net()).isEqualByComparingTo(new BigDecimal("19000.00").subtract(amount));
            var prepare = new PayrollRunController.PrepareRunRequest(school,due,preview.inputFingerprint(),
                    "synthetic-recovery-"+month,"Synthetic approved monthly recovery");
            var run = payrollRuns.prepare(f.maker(),prepare);
            assertThat(payrollRuns.prepare(f.maker(),prepare).id()).isEqualTo(run.id());
            payrollRuns.submit(f.maker(),run.id(),new PayrollRunController.RunTransitionRequest(school,0L,"Synthetic payroll submission"));
            assertThatThrownBy(() -> payrollRuns.decide(f.maker(),run.id(),
                    new PayrollRunController.RunDecisionRequest(school,"FINALIZE",1L,"Synthetic forbidden self approval")))
                    .hasMessageContaining("cannot make its final decision");
            payrollRuns.decide(f.approver(),run.id(),new PayrollRunController.RunDecisionRequest(school,"FINALIZE",1L,"Synthetic independent payroll approval"));
            var accrual = payrollAccounting.prepareAccrual(f.maker(),run.id(),"synthetic-recovery-accrual-"+month,
                    new PayrollAccountingController.AccrualRequest(school,f.yearId(),due.withDayOfMonth(due.lengthOfMonth()),"Synthetic recovery ledger accrual"));
            UUID journal = payrollJournal(accrual.id());
            postPayrollJournal(f,journal);
            assertThat(jdbc.queryForObject("SELECT sum(credit_amount) FROM accounting_journal_lines WHERE accounting_journal_id=? AND account_id=?",
                    BigDecimal.class,journal,request.receivableAccountId())).isEqualByComparingTo(amount);
            assertThat(jdbc.queryForObject("SELECT sum(debit_amount-credit_amount) FROM accounting_journal_lines WHERE accounting_journal_id=?",
                    BigDecimal.class,journal)).isEqualByComparingTo("0.00");
            var summary = payrollReports.summary(f.maker(),school,new com.educore.api.payroll.PayrollReports.Query(due,due,null,"",0,25),
                    com.educore.api.payroll.PayrollReports.GroupBy.DEDUCTION_COMPONENT);
            assertThat(summary.items().stream().map(row -> row.deductions()).reduce(BigDecimal.ZERO,BigDecimal::add))
                    .isEqualByComparingTo(preview.deductions());
            payrollPayslips.generate(f.maker(),run.id(),"synthetic-recovery-payslip-"+month,
                    new PayrollPayslipController.GeneratePayslipsRequest(school,"en-IN","Synthetic recovery payslip"));
            recovered = recovered.add(amount);
        }
        assertThat(recovered).isEqualByComparingTo("100.01");
        assertThat(jdbc.queryForObject("""
                SELECT sum(line.debit_amount-line.credit_amount) FROM accounting_journal_lines line
                JOIN accounting_journals j ON j.id=line.accounting_journal_id
                WHERE line.account_id=? AND j.status='POSTED'
                """,BigDecimal.class,request.receivableAccountId())).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT net_total FROM payroll_runs WHERE id=?",BigDecimal.class,f.payroll().runId()))
                .isEqualByComparingTo("19000.00");
        assertDatabaseRejected(() -> jdbc.update("UPDATE payroll_run_advance_recoveries SET recovery_amount=1"),"immutable");
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM payroll_run_advance_recoveries"),"immutable");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    @Transactional
    void payrollAdvanceStatementIsScopedAuditedCompleteAndLedgerDerived() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId();
        UUID issue = prepareSyntheticRecoveryIssue(f, syntheticAdvance(f));
        UUID advanceId = jdbc.queryForObject("""
                SELECT employee_advance_id FROM payroll_employee_advance_postings
                WHERE accounting_journal_id=?
                """, UUID.class, issue);

        var beforePosting = payrollAdvanceStatements.read(f.maker(), school, advanceId);
        assertThat(beforePosting.evidenceStatus()).isEqualTo("NOT_DISBURSED");
        assertThat(beforePosting.postedDisbursementAmount()).isEqualByComparingTo("0.00");
        assertThat(beforePosting.recoveredAmount()).isEqualByComparingTo("0.00");
        assertThat(beforePosting.outstandingAmount()).isNull();
        assertThat(beforePosting.installments()).hasSize(3).allSatisfy(installment -> {
            assertThat(installment.status()).isEqualTo("PLANNED");
            assertThat(installment.recoveryAttempts()).isEmpty();
        });

        postPayrollJournal(f, issue);
        var disbursed = payrollAdvanceStatements.read(f.maker(), school, advanceId);
        assertThat(disbursed.evidenceStatus()).isEqualTo("DISBURSED");
        assertThat(disbursed.postedDisbursementAmount()).isEqualByComparingTo("100.01");
        assertThat(disbursed.outstandingAmount()).isEqualByComparingTo("100.01");

        LocalDate due = LocalDate.of(2025, 10, 1);
        var preview = payrollRuns.preview(f.maker(), school, due, "payroll.run.prepare");
        var run = payrollRuns.prepare(f.maker(), new PayrollRunController.PrepareRunRequest(
                school, due, preview.inputFingerprint(), "synthetic-statement-recovery",
                "Synthetic statement recovery"));
        payrollRuns.submit(f.maker(), run.id(), new PayrollRunController.RunTransitionRequest(
                school, 0L, "Synthetic statement payroll submission"));
        payrollRuns.decide(f.approver(), run.id(), new PayrollRunController.RunDecisionRequest(
                school, "FINALIZE", 1L, "Synthetic independent statement approval"));
        var accrual = payrollAccounting.prepareAccrual(f.maker(), run.id(),
                "synthetic-statement-accrual", new PayrollAccountingController.AccrualRequest(
                        school, f.yearId(), LocalDate.of(2025, 10, 31),
                        "Synthetic statement accrual"));
        UUID accrualJournal = payrollJournal(accrual.id());
        var reserved = payrollAdvanceStatements.read(f.maker(), school, advanceId);
        assertThat(reserved.installments().getFirst().status()).isEqualTo("AWAITING_ACCOUNTING_APPROVAL");
        assertThat(reserved.recoveredAmount()).isEqualByComparingTo("0.00");
        postPayrollJournal(f, accrualJournal);

        var statement = payrollAdvanceStatements.read(f.maker(), school, advanceId);
        String runCode = jdbc.queryForObject("SELECT run_code FROM payroll_runs WHERE id=?",
                String.class, run.id());
        assertThat(statement.schoolId()).isEqualTo(school);
        assertThat(statement.evidenceStatus()).isEqualTo("PARTIALLY_RECOVERED");
        assertThat(statement.recoveredAmount()).isEqualByComparingTo("33.33");
        assertThat(statement.outstandingAmount()).isEqualByComparingTo("66.68");
        assertThat(statement.installments()).extracting(item -> item.status())
                .containsExactly("RECOVERED", "PLANNED", "PLANNED");
        assertThat(statement.installments().getFirst().recoveryAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.runCode()).isEqualTo(runCode);
            assertThat(attempt.status()).isEqualTo("RECOVERED");
            assertThat(attempt.ledgerPostings()).singleElement().satisfies(posting -> {
                assertThat(posting.accountingJournalId()).isEqualTo(accrualJournal);
                assertThat(posting.evidenceStatus()).isEqualTo("MATCHED_POSTED");
                assertThat(posting.countsAsRecovered()).isTrue();
            });
        });
        String csv = payrollAdvanceStatements.export(f.maker(), school, advanceId).csv();
        assertThat(csv.lines()).hasSize(4);
        assertThat(csv).contains(statement.advanceNumber(), "PAY-202510-", "MATCHED_POSTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND subject_id=? AND action IN ('payroll.advance.statement.read',
                    'payroll.advance.statement.exported') AND outcome='SUCCEEDED'
                """, Integer.class, f.payroll().rollover().organisationId(), school,
                advanceId.toString())).isGreaterThanOrEqualTo(2);

        var teacher = payrollAccountingActor(f.payroll().rollover().teacherUserId(),
                f.payroll().rollover().teacherMembershipId(), f.payroll().rollover().organisationId());
        assertThatThrownBy(() -> payrollAdvanceStatements.read(teacher, school, advanceId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollAdvanceStatements.read(f.maker(), UUID.randomUUID(), advanceId))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollAdvanceStatements.read(f.maker(), school, UUID.randomUUID()))
                .hasMessageContaining("unavailable in this school");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    @Transactional
    void payrollAdvanceReconciliationIsScopedAuditedPaginatedAndLedgerDerived() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId();
        UUID issueJournal = prepareSyntheticRecoveryIssue(f, syntheticAdvance(f));
        UUID advanceId = jdbc.queryForObject("""
                SELECT employee_advance_id FROM payroll_employee_advance_postings
                WHERE accounting_journal_id=?
                """, UUID.class, issueJournal);
        var query = new com.educore.api.payroll.PayrollReports.AdvanceQuery(
                LocalDate.of(2025, 10, 31), null, "EPA-", 0, 25);

        var awaitingIssue = payrollReports.advanceReconciliation(f.maker(), school, query);
        assertThat(awaitingIssue.schoolId()).isEqualTo(school);
        assertThat(awaitingIssue.dueThrough()).isEqualTo(LocalDate.of(2025, 10, 31));
        assertThat(awaitingIssue.total()).isEqualTo(1);
        assertThat(awaitingIssue.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(advanceId);
            assertThat(row.disbursementStatus()).isEqualTo("AWAITING_APPROVAL");
            assertThat(row.reconciliationStatus()).isEqualTo("NOT_DISBURSED");
            assertThat(row.postedDisbursementAmount()).isEqualByComparingTo("0.00");
            assertThat(row.recoveredAmount()).isEqualByComparingTo("0.00");
            assertThat(row.outstandingAmount()).isNull();
            assertThat(row.overdueAmount()).isNull();
            assertThat(row.issues()).isEmpty();
        });

        postPayrollJournal(f, issueJournal);
        assertThat(payrollReports.advanceReconciliation(f.maker(), school, query).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.disbursementStatus()).isEqualTo("POSTED");
                    assertThat(row.reconciliationStatus()).isEqualTo("OVERDUE");
                    assertThat(row.postedDisbursementAmount()).isEqualByComparingTo("100.01");
                    assertThat(row.recoveredAmount()).isEqualByComparingTo("0.00");
                    assertThat(row.outstandingAmount()).isEqualByComparingTo("100.01");
                    assertThat(row.overdueAmount()).isEqualByComparingTo("33.33");
                    assertThat(row.nextUnrecoveredMonth()).isEqualTo(LocalDate.of(2025, 10, 1));
                });

        LocalDate due = LocalDate.of(2025, 10, 1);
        var preview = payrollRuns.preview(f.maker(), school, due, "payroll.run.prepare");
        var run = payrollRuns.prepare(f.maker(), new PayrollRunController.PrepareRunRequest(
                school, due, preview.inputFingerprint(), "synthetic-report-advance-recovery",
                "Synthetic reconciliation recovery"));
        payrollRuns.submit(f.maker(), run.id(), new PayrollRunController.RunTransitionRequest(
                school, 0L, "Synthetic reconciliation payroll submission"));
        payrollRuns.decide(f.approver(), run.id(), new PayrollRunController.RunDecisionRequest(
                school, "FINALIZE", 1L, "Synthetic independent reconciliation approval"));
        var accrual = payrollAccounting.prepareAccrual(f.maker(), run.id(),
                "synthetic-report-advance-accrual", new PayrollAccountingController.AccrualRequest(
                        school, f.yearId(), LocalDate.of(2025, 10, 31),
                        "Synthetic reconciliation accrual"));
        var pending = payrollReports.advanceReconciliation(f.maker(), school, query).items().getFirst();
        assertThat(pending.pendingRecoveryCount()).isEqualTo(1);
        assertThat(pending.pendingRecoveryAmount()).isEqualByComparingTo("33.33");
        assertThat(pending.recoveredAmount()).isEqualByComparingTo("0.00");
        postPayrollJournal(f, payrollJournal(accrual.id()));

        var recovered = payrollReports.advanceReconciliation(f.maker(), school, query);
        assertThat(recovered.items()).singleElement().satisfies(row -> {
            assertThat(row.reconciliationStatus()).isEqualTo("PARTIALLY_RECOVERED");
            assertThat(row.recoveredAmount()).isEqualByComparingTo("33.33");
            assertThat(row.outstandingAmount()).isEqualByComparingTo("66.68");
            assertThat(row.overdueAmount()).isEqualByComparingTo("0.00");
            assertThat(row.pendingRecoveryCount()).isZero();
            assertThat(row.nextUnrecoveredMonth()).isEqualTo(LocalDate.of(2025, 11, 1));
            assertThat(row.issues()).isEmpty();
        });
        var secondPage = payrollReports.advanceReconciliation(f.maker(), school,
                new com.educore.api.payroll.PayrollReports.AdvanceQuery(
                        query.dueThrough(), null, query.search(), 1, 1));
        assertThat(secondPage.total()).isEqualTo(1);
        assertThat(secondPage.items()).isEmpty();
        String csv = payrollReports.exportAdvanceReconciliation(f.maker(), school, query);
        assertThat(csv.lines()).hasSize(2);
        assertThat(csv).startsWith("\uFEFF\"Advance number\"")
                .contains("\"PARTIALLY_RECOVERED\"", advanceId.toString())
                .doesNotContain("password", "idempotency_key", "evidence_snapshot");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organisation_id=? AND school_id=?
                  AND action IN ('payroll.report.read','payroll.report.exported')
                  AND safe_metadata->>'report'='advance_reconciliation'
                  AND outcome='SUCCEEDED'
                """, Integer.class, f.payroll().rollover().organisationId(), school))
                .isGreaterThanOrEqualTo(2);

        var teacher = payrollAccountingActor(f.payroll().rollover().teacherUserId(),
                f.payroll().rollover().teacherMembershipId(), f.payroll().rollover().organisationId());
        assertThatThrownBy(() -> payrollReports.advanceReconciliation(teacher, school, query))
                .hasMessageContaining("unavailable for this school");
        assertThatThrownBy(() -> payrollReports.advanceReconciliation(
                f.maker(), UUID.randomUUID(), query)).hasMessageContaining("unavailable for this school");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    @Transactional
    void payrollAdvanceRecoveryBlocksInsufficientSalaryAndUnauthorizedOrForeignScope() {
        var f = insertPayrollAccountingFixture();
        UUID school = f.payroll().rollover().schoolId();
        var original = syntheticAdvance(f);
        var request = new PayrollAdvanceController.AdvanceRequest(school,original.staffProfileId(),original.advanceType(),
                original.purpose(),original.financialYearId(),original.issueDate(),new BigDecimal("60000.03"),
                original.recoveryStartMonth(),3,original.receivableAccountId(),original.disbursementAccountId(),
                "CASH",null,"Synthetic insufficient salary fixture");
        postPayrollJournal(f,prepareSyntheticRecoveryIssue(f,request));
        LocalDate due=LocalDate.of(2025,10,1);
        var preview=payrollRuns.preview(f.maker(),school,due,"payroll.run.prepare");
        assertThat(preview.ready()).isFalse();
        assertThat(preview.blockers()).extracting(item -> item.code()).contains("PAYABLE_NET_NEGATIVE");
        assertThat(preview.net()).isEqualByComparingTo("-0.01");
        assertThatThrownBy(() -> payrollRuns.prepare(f.maker(),new PayrollRunController.PrepareRunRequest(school,due,
                preview.inputFingerprint(),"synthetic-insufficient-recovery","Synthetic blocked negative salary"))).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payroll_run_advance_recoveries",Integer.class)).isZero();
        assertThatThrownBy(() -> payrollRuns.preview(f.maker(),UUID.randomUUID(),due,"payroll.run.prepare"))
                .hasMessageContaining("unavailable for this school");
        var teacher=payrollAccountingActor(f.payroll().rollover().teacherUserId(),f.payroll().rollover().teacherMembershipId(),f.payroll().rollover().organisationId());
        assertThatThrownBy(() -> payrollRuns.preview(teacher,school,due,"payroll.run.prepare"))
                .isInstanceOf(com.educore.api.common.api.ApiException.class);
    }

    @Test
    @Transactional
    void payrollAdvanceRecoveryReleasesOnlyOnControlledRunDecisionAndProtectsIssueReversal() {
        var f=insertPayrollAccountingFixture();
        UUID school=f.payroll().rollover().schoolId();
        UUID issue=prepareSyntheticRecoveryIssue(f,syntheticAdvance(f));
        postPayrollJournal(f,issue);
        LocalDate due=LocalDate.of(2025,10,1);
        var preview=payrollRuns.preview(f.maker(),school,due,"payroll.run.prepare");
        var run=payrollRuns.prepare(f.maker(),new PayrollRunController.PrepareRunRequest(school,due,preview.inputFingerprint(),
                "synthetic-recovery-release","Synthetic recovery release fixture"));
        UUID period=jdbc.queryForObject("SELECT accounting_period_id FROM accounting_journals WHERE id=?",UUID.class,issue);
        var reversal=accounting.requestReversal(f.maker(),issue,new ReversalRequest(school,period,LocalDate.of(2025,9,1),"Synthetic issue correction"));
        assertDatabaseRejected(() -> accounting.decideReversal(f.approver(),reversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(school,"APPROVE","Synthetic blocked source reversal",0)),
                "dependent payroll recoveries");
        assertDatabaseRejected(() -> jdbc.update("UPDATE payroll_run_advance_recoveries SET reservation_status='RELEASED'"),"immutable");
        payrollRuns.cancel(f.maker(),run.id(),new PayrollRunController.RunTransitionRequest(school,0L,"Synthetic cancelled recovery draft"));
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM payroll_run_advance_recoveries",String.class)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT release_reason FROM payroll_run_advance_recoveries",String.class)).isEqualTo("Synthetic cancelled recovery draft");
        var replacement=payrollRuns.preview(f.maker(),school,due,"payroll.run.prepare");
        assertThat(replacement.staff().getFirst().recoveries()).hasSize(1);
        var replacementRun=payrollRuns.prepare(f.maker(),new PayrollRunController.PrepareRunRequest(school,due,
                replacement.inputFingerprint(),"synthetic-recovery-replacement","Synthetic replacement recovery"));
        payrollRuns.submit(f.maker(),replacementRun.id(),new PayrollRunController.RunTransitionRequest(
                school,0L,"Synthetic replacement submission"));
        payrollRuns.decide(f.approver(),replacementRun.id(),new PayrollRunController.RunDecisionRequest(
                school,"FINALIZE",1L,"Synthetic independent replacement approval"));
        var accrual=payrollAccounting.prepareAccrual(f.maker(),replacementRun.id(),"synthetic-recovery-reversal-accrual",
                new PayrollAccountingController.AccrualRequest(school,f.yearId(),LocalDate.of(2025,10,31),
                        "Synthetic recovery reversal accrual"));
        UUID accrualJournal=payrollJournal(accrual.id());
        postPayrollJournal(f,accrualJournal);
        assertThat(payrollAdvances.workspace(f.maker(),school).advances().getFirst().recoveryStatus())
                .isEqualTo("PARTIALLY_RECOVERED");
        UUID accrualPeriod=jdbc.queryForObject("SELECT accounting_period_id FROM accounting_journals WHERE id=?",
                UUID.class,accrualJournal);
        var accrualReversal=accounting.requestReversal(f.maker(),accrualJournal,new ReversalRequest(
                school,accrualPeriod,LocalDate.of(2025,10,31),"Synthetic recovery accrual correction"));
        accounting.decideReversal(f.approver(),accrualReversal.id(),
                new com.educore.api.accounting.AccountingController.DecisionRequest(
                        school,"APPROVE","Synthetic independent accrual reversal",0));
        var runReversal=payrollRuns.requestReversal(f.maker(),replacementRun.id(),"synthetic-recovery-run-reversal",
                new PayrollRunController.RunReversalRequest(school,2L,"Synthetic recovery run correction"));
        payrollRuns.decideReversal(f.approver(),runReversal.id(),new PayrollRunController.RunReversalDecisionRequest(
                school,"APPROVE",0L,"Synthetic independent recovery run reversal"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payroll_run_advance_recoveries WHERE reservation_status='ACTIVE'",
                Integer.class)).isZero();
        accounting.decideReversal(f.approver(),reversal.id(),new com.educore.api.accounting.AccountingController.DecisionRequest(
                school,"APPROVE","Synthetic safe source reversal",0));
        UUID advanceId=jdbc.queryForObject("SELECT employee_advance_id FROM payroll_employee_advance_postings WHERE accounting_journal_id=?",
                UUID.class,issue);
        var retainedStatement=payrollAdvanceStatements.read(f.maker(),school,advanceId);
        assertThat(retainedStatement.evidenceStatus()).isEqualTo("NOT_DISBURSED");
        assertThat(retainedStatement.recoveredAmount()).isEqualByComparingTo("0.00");
        assertThat(retainedStatement.outstandingAmount()).isNull();
        assertThat(retainedStatement.installments().getFirst().recoveryAttempts()).hasSize(2)
                .allSatisfy(attempt -> assertThat(attempt.status()).isEqualTo("RELEASED"));
        assertThat(retainedStatement.installments().getFirst().recoveryAttempts()).flatExtracting(
                attempt -> attempt.ledgerPostings()).extracting(posting -> posting.evidenceStatus())
                .contains("MATCHED_REVERSED");
        var after=payrollRuns.preview(f.maker(),school,due,"payroll.run.prepare");
        assertThat(after.staff().getFirst().recoveries()).isEmpty();
        assertThatThrownBy(() -> payrollRuns.prepare(f.maker(),new PayrollRunController.PrepareRunRequest(school,due,
                replacement.inputFingerprint(),"synthetic-stale-recovery","Synthetic stale source evidence"))).hasMessageContaining("changed after preview");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    @Transactional
    void payrollAdvanceRecoveryEvidenceRemainsOrganisationIsolatedUnderRowSecurity() {
        var f=insertPayrollAccountingFixture();
        UUID school=f.payroll().rollover().schoolId();
        postPayrollJournal(f,prepareSyntheticRecoveryIssue(f,syntheticAdvance(f)));
        LocalDate due=LocalDate.of(2025,10,1);
        var preview=payrollRuns.preview(f.maker(),school,due,"payroll.run.prepare");
        payrollRuns.prepare(f.maker(),new PayrollRunController.PrepareRunRequest(school,due,preview.inputFingerprint(),
                "synthetic-recovery-rls","Synthetic recovery RLS fixture"));
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("CREATE ROLE educore_payroll_recovery_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_payroll_recovery_rls_test");
        for (String table : List.of("payroll_run_advance_recoveries","payroll_advance_recovery_fences")) {
            jdbc.execute("GRANT SELECT,UPDATE ON " + table + " TO educore_payroll_recovery_rls_test");
        }
        jdbc.execute("SET LOCAL ROLE educore_payroll_recovery_rls_test");
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,
                f.payroll().rollover().organisationId().toString());
        for (String table : List.of("payroll_run_advance_recoveries","payroll_advance_recovery_fences")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isPositive();
        }
        jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
        for (String table : List.of("payroll_run_advance_recoveries","payroll_advance_recovery_fences")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
            assertThat(jdbc.update("UPDATE "+table+" SET school_id=school_id")).isZero();
        }
        jdbc.execute("RESET ROLE");
    }

    private UUID prepareSyntheticRecoveryIssue(PayrollAccountingFixture f, PayrollAdvanceController.AdvanceRequest request) {
        UUID school=f.payroll().rollover().schoolId();
        var advance=payrollAdvances.create(f.maker(),"synthetic-recovery-issue",request);
        payrollAdvances.submit(f.maker(),advance.id(),new PayrollAdvanceController.VersionedReasonRequest(school,0L,"Synthetic advance submission"));
        payrollAdvances.decide(f.approver(),advance.id(),new PayrollAdvanceController.DecisionRequest(school,"APPROVE",1L,"Synthetic independent advance approval"));
        return advanceJournal(payrollAdvances.prepareDisbursement(f.maker(),advance.id(),"synthetic-recovery-disbursement",
                new PayrollAdvanceController.DisbursementRequest(school,"Synthetic issued advance evidence")).id());
    }

    private PayrollAdvanceController.AdvanceRequest syntheticAdvance(PayrollAccountingFixture f) {
        UUID school = f.payroll().rollover().schoolId();
        var receivable = accounting.createAccount(f.maker(), new AccountRequest(school, null, "A1300.ADVANCE",
                "Synthetic employee advances", null, "ASSET", "RECEIVABLE", true, "ACTIVE", null, "Synthetic advance setup"));
        UUID cash = jdbc.queryForObject("SELECT id FROM accounting_accounts WHERE school_id=? AND code='A1000.PAYROLL'",
                UUID.class, school);
        return new PayrollAdvanceController.AdvanceRequest(school, f.payroll().staffId(), "ADVANCE",
                "Synthetic employee advance", f.yearId(), LocalDate.of(2025, 9, 1), new BigDecimal("100.01"),
                LocalDate.of(2025, 10, 1), 3, receivable.id(), cash, "CASH", null, "Synthetic advance preparation");
    }

    private UUID advanceJournal(UUID posting) {
        return jdbc.queryForObject("SELECT accounting_journal_id FROM payroll_employee_advance_postings WHERE id=?",
                UUID.class, posting);
    }

    private PayrollAccountingFixture insertPayrollAccountingFixture() {
        return insertPayrollAccountingFixture(false);
    }

    private PayrollAccountingFixture insertPayrollAccountingFixture(boolean mixedComponents) {
        PayslipFixture p = insertFinalizedPayslipFixture(mixedComponents);
        UUID school = p.rollover().schoolId(), org = p.rollover().organisationId();
        var maker = payrollAccountingActor(p.rollover().actorUserId(), p.rollover().actorMembershipId(), org);
        var approver = payrollAccountingActor(p.approverUserId(), p.approverMembershipId(), org);
        var year = accounting.createFinancialYear(maker, new FinancialYearRequest(school,
                "FY_PAYROLL_2025_26", "Synthetic payroll accrual year", LocalDate.of(2025, 4, 1),
                LocalDate.of(2026, 3, 31), "ACCRUAL", "INR", "Synthetic payroll integration setup"));
        var expense = accounting.createAccount(maker, new AccountRequest(school, null, "E6000.SALARY",
                "Synthetic salary expense", null, "EXPENSE", "EXPENSE", true, "ACTIVE", null, "Synthetic payroll account"));
        var payable = accounting.createAccount(maker, new AccountRequest(school, null, "L2100.SALARY",
                "Synthetic salary payable", null, "LIABILITY", "PAYABLE", true, "ACTIVE", null, "Synthetic payroll account"));
        var cash = accounting.createAccount(maker, new AccountRequest(school, null, "A1000.PAYROLL",
                "Synthetic payroll cash", null, "ASSET", "CASH", true, "ACTIVE", null, "Synthetic payroll account"));
        accounting.openFinancialYear(approver, year.id(),
                new com.educore.api.accounting.AccountingController.VersionedReasonRequest(school, 0, "Synthetic year opening"));
        var deductions = accounting.createAccount(maker, new AccountRequest(school, null, "L2200.DEDUCTIONS",
                "Synthetic component payable", null, "LIABILITY", "PAYABLE", true, "ACTIVE", null, "Synthetic payroll account"));
        var mappings = jdbc.query("SELECT id,component_kind FROM payroll_components WHERE school_id=?",
                (row, number) -> new PayrollAccountingController.ComponentMappingRequest(row.getObject("id", UUID.class),
                        "DEDUCTION".equals(row.getString("component_kind")) ? null : expense.id(),
                        "EARNING".equals(row.getString("component_kind")) ? null : deductions.id()), school);
        var mappingRequest = new PayrollAccountingController.ConfigurationRequest(
                school, year.id(), null, 1, LocalDate.of(2025, 4, 1), payable.id(),
                mappings,
                List.of(new PayrollAccountingController.TenderMappingRequest("CASH", cash.id())), "Synthetic payroll mappings");
        var config = payrollAccounting.createConfiguration(maker, mappingRequest);
        assertThat(payrollAccounting.workspace(maker, school).configurations()).singleElement().satisfies(c -> {
            assertThat(c.canEdit()).isTrue();
            assertThat(c.canSubmit()).isTrue();
            assertThat(c.canDecide()).isFalse();
        });
        assertThat(payrollAccounting.workspace(approver, school).configurations()).singleElement().satisfies(c -> {
            assertThat(c.canEdit()).isFalse();
            assertThat(c.canSubmit()).isFalse();
            assertThat(c.canDecide()).isFalse();
        });
        payrollAccounting.updateConfiguration(maker, config.id(),
                new PayrollAccountingController.UpdateConfigurationRequest(mappingRequest, 0L));
        assertThatThrownBy(() -> payrollAccounting.updateConfiguration(maker, config.id(),
                new PayrollAccountingController.UpdateConfigurationRequest(mappingRequest, 0L)))
                .hasMessageContaining("record changed");
        payrollAccounting.submitConfiguration(maker, config.id(),
                new PayrollAccountingController.VersionedReasonRequest(school, 1L, "Synthetic mapping submission"));
        assertThat(payrollAccounting.workspace(maker, school).configurations()).singleElement().satisfies(c -> {
            assertThat(c.canEdit()).isFalse();
            assertThat(c.canSubmit()).isFalse();
            assertThat(c.canDecide()).isFalse();
        });
        assertThat(payrollAccounting.workspace(approver, school).configurations()).singleElement().satisfies(c -> {
            assertThat(c.canEdit()).isFalse();
            assertThat(c.canSubmit()).isFalse();
            assertThat(c.canDecide()).isTrue();
        });
        assertThatThrownBy(() -> payrollAccounting.decideConfiguration(maker, config.id(),
                new PayrollAccountingController.ConfigurationDecisionRequest(school, "APPROVE", 2L,
                        "Synthetic denied self approval"))).hasMessageContaining("different authorized person");
        payrollAccounting.decideConfiguration(approver, config.id(),
                new PayrollAccountingController.ConfigurationDecisionRequest(school, "APPROVE", 2L,
                        "Synthetic independent mapping approval"));
        assertThat(payrollAccounting.workspace(approver, school).configurations()).singleElement().satisfies(c -> {
            assertThat(c.canEdit()).isFalse();
            assertThat(c.canSubmit()).isFalse();
            assertThat(c.canDecide()).isFalse();
        });
        UUID result = jdbc.queryForObject("SELECT id FROM payroll_run_staff_results WHERE payroll_run_id=?", UUID.class, p.runId());
        return new PayrollAccountingFixture(p, maker, approver, year.id(), expense.id(), payable.id(), config.id(), result);
    }

    private AuthenticatedUser payrollAccountingActor(UUID user, UUID membership, UUID organisation) {
        return new AuthenticatedUser(user, UUID.randomUUID(), "payroll-accounting@example.test",
                "payroll-accounting@example.test", "Synthetic payroll accounting actor", true, organisation,
                Set.of(membership), Set.of("payroll.read", "payroll.configure", "payroll.approve", "payroll.run.prepare",
                "payroll.advance.prepare", "payroll.advance.approve",
                "payroll.run.reverse.request", "payroll.run.reverse.approve", "accounting.read", "accounting.configure",
                "accounting.prepare", "accounting.post", "accounting.close", "accounting.reverse.request",
                "accounting.reverse.approve"), List.of());
    }

    @Test
    @Transactional
    void appliesV039PayrollPersonLevelDecisionGuards() {
        var fixture = insertAcademicRolloverFixture();
        assertThat(java.util.Arrays.stream(flyway.info().applied())
                .anyMatch(info -> "039".equals(info.getVersion().getVersion()))).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                WHERE event_object_schema='public' AND trigger_name IN (
                  'payroll_runs_guard_person_decision','payroll_run_reversals_guard_person_decision')
                """, Integer.class)).isEqualTo(2);
        UUID alias = alternatePayrollMembership(fixture.actorMembershipId(), fixture.campusId());
        assertThat(jdbc.queryForObject("SELECT payroll_run_actor_is_independent(?,?,?,?,?)", Boolean.class,
                alias, fixture.actorMembershipId(), null, fixture.organisationId(), fixture.schoolId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT payroll_run_actor_is_independent(?,?,?,?,?)", Boolean.class,
                fixture.teacherMembershipId(), fixture.actorMembershipId(), null,
                fixture.organisationId(), fixture.schoolId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT payroll_run_actor_is_independent(?,?,?,?,?)", Boolean.class,
                fixture.teacherMembershipId(), fixture.actorMembershipId(), fixture.teacherMembershipId(),
                fixture.organisationId(), fixture.schoolId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT payroll_run_actor_is_independent(?,?,?,?,?)", Boolean.class,
                fixture.teacherMembershipId(), fixture.actorMembershipId(), UUID.randomUUID(),
                fixture.organisationId(), fixture.schoolId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT payroll_run_actor_is_independent(?,?,?,?,?)", Boolean.class,
                fixture.teacherMembershipId(), fixture.actorMembershipId(), null,
                UUID.randomUUID(), fixture.schoolId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT payroll_run_actor_is_independent(?,?,?,?,?)", Boolean.class,
                fixture.teacherMembershipId(), fixture.actorMembershipId(), null,
                fixture.organisationId(), UUID.randomUUID())).isFalse();
    }

    private UUID alternatePayrollMembership(UUID originalMembership, UUID campusId) {
        UUID alternate = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,
                  valid_from,created_at,updated_at,row_version)
                SELECT ?,user_id,organisation_id,school_id,?,'ACTIVE',valid_from,now(),now(),0
                FROM memberships WHERE id=?
                """, alternate, campusId, originalMembership);
        jdbc.update("""
                INSERT INTO membership_roles(membership_id,role_id)
                SELECT ?,role_id FROM membership_roles WHERE membership_id=?
                """, alternate, originalMembership);
        return alternate;
    }

    private UUID payrollJournal(UUID batch) {
        return jdbc.queryForObject("SELECT accounting_journal_id FROM payroll_accounting_posting_batches WHERE id=?", UUID.class, batch);
    }

    private void postPayrollJournal(PayrollAccountingFixture f, UUID journal) {
        accounting.decideJournal(f.approver(), journal, new com.educore.api.accounting.AccountingController.DecisionRequest(
                f.payroll().rollover().schoolId(), "POST", "Synthetic independent ledger posting", 1));
    }

    private record PayrollAccountingFixture(PayslipFixture payroll, AuthenticatedUser maker,
            AuthenticatedUser approver, UUID yearId, UUID expenseId, UUID payableId,
            UUID configurationId, UUID resultId) {}

    private PayslipFixture insertFinalizedPayslipFixture() {
        return insertFinalizedPayslipFixture(false);
    }

    private PayslipFixture insertFinalizedPayslipFixture(boolean mixedComponents) {
        RolloverFixture rollover = insertAcademicRolloverFixture();
        UUID staffId = jdbc.queryForObject(
                "SELECT id FROM staff_profiles WHERE user_account_id=?", UUID.class,
                rollover.teacherUserId());
        UUID approverUser = uuid("69000000", 1);
        UUID approverMembership = uuid("69000000", 2);
        UUID component = uuid("69000000", 3);
        UUID salary = uuid("69000000", 4);
        UUID salaryLine = uuid("69000000", 5);
        UUID policy = uuid("69000000", 6);
        UUID run = uuid("69000000", 7);
        UUID result = uuid("69000000", 8);
        UUID runLine = uuid("69000000", 9);
        BigDecimal deduction = new BigDecimal(mixedComponents ? "1000.00" : "0.00");
        BigDecimal employer = new BigDecimal(mixedComponents ? "500.00" : "0.00");
        BigDecimal net = new BigDecimal("20000.00").subtract(deduction);
        insertSyntheticUser(approverUser, "payslip-approver@example.test");
        insertSyntheticMembership(approverMembership, approverUser,
                rollover.organisationId(), rollover.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?)",
                approverMembership, UUID.fromString("00000000-0000-4000-8000-000000000007"));
        jdbc.update("""
                INSERT INTO payroll_components(
                  id,organisation_id,school_id,code,display_name,component_kind,
                  calculation_method,is_basic,attendance_prorated,statutory,description,
                  display_order,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?,'PAYSLIP_BASIC','Payslip basic salary','EARNING','FIXED',
                  true,true,false,'Synthetic finalized payslip evidence',10,'ACTIVE',?,now(),now(),0)
                """, component, rollover.organisationId(), rollover.schoolId(),
                rollover.actorMembershipId());
        jdbc.update("""
                INSERT INTO staff_salary_profiles(
                  id,organisation_id,school_id,staff_profile_id,revision_number,effective_from,
                  currency_code,pay_frequency,status,creation_reason,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?,1,DATE '2025-04-01','INR','MONTHLY','DRAFT',
                  'Synthetic finalized payslip salary',?,now(),now(),0)
                """, salary, rollover.organisationId(), rollover.schoolId(), staffId,
                rollover.actorMembershipId());
        jdbc.update("""
                INSERT INTO staff_salary_profile_lines(
                  id,organisation_id,school_id,salary_profile_id,payroll_component_id,
                  component_code_snapshot,component_name_snapshot,component_kind_snapshot,
                  calculation_method_snapshot,is_basic_snapshot,attendance_prorated_snapshot,
                  statutory_snapshot,fixed_amount,created_at)
                VALUES (?, ?, ?, ?, ?,'PAYSLIP_BASIC','Payslip basic salary','EARNING',
                  'FIXED',true,true,false,20000.00,now())
                """, salaryLine, rollover.organisationId(), rollover.schoolId(), salary, component);
        if (mixedComponents) {
            for (String kind : List.of("DEDUCTION", "EMPLOYER_CONTRIBUTION")) {
                UUID extra = uuid("69000000", kind.equals("DEDUCTION") ? 10 : 11);
                BigDecimal amount = kind.equals("DEDUCTION") ? deduction : employer;
                jdbc.update("""
                        INSERT INTO payroll_components(id,organisation_id,school_id,code,display_name,component_kind,
                          calculation_method,is_basic,attendance_prorated,statutory,display_order,status,
                          created_by_membership_id,created_at,updated_at,row_version)
                        VALUES(?,?,?,?,?,?,'FIXED',false,false,false,20,'ACTIVE',?,now(),now(),0)
                        """, extra, rollover.organisationId(), rollover.schoolId(), kind, "Synthetic " + kind, kind,
                        rollover.actorMembershipId());
                jdbc.update("""
                        INSERT INTO staff_salary_profile_lines(id,organisation_id,school_id,salary_profile_id,
                          payroll_component_id,component_code_snapshot,component_name_snapshot,component_kind_snapshot,
                          calculation_method_snapshot,is_basic_snapshot,attendance_prorated_snapshot,statutory_snapshot,
                          fixed_amount,created_at)
                        VALUES(?,?,?,?,?,?,?,?,'FIXED',false,false,false,?,now())
                        """, UUID.randomUUID(), rollover.organisationId(), rollover.schoolId(), salary, extra,
                        kind, "Synthetic " + kind, kind, amount);
            }
        }
        jdbc.update("""
                UPDATE staff_salary_profiles
                SET status='SUBMITTED', submitted_by_membership_id=?, submitted_at=now(),
                    submission_reason='Synthetic salary submission', updated_at=now(), row_version=1
                WHERE id=? AND organisation_id=? AND school_id=?
                """, rollover.actorMembershipId(), salary, rollover.organisationId(),
                rollover.schoolId());
        jdbc.update("""
                UPDATE staff_salary_profiles
                SET status='APPROVED', decided_by_membership_id=?, decided_at=now(),
                    decision_reason='Synthetic independent salary approval', updated_at=now(),
                    row_version=2
                WHERE id=? AND organisation_id=? AND school_id=?
                """, approverMembership, salary, rollover.organisationId(), rollover.schoolId());
        jdbc.update("""
                INSERT INTO payroll_run_policies(
                  id,organisation_id,school_id,display_name,currency_code,
                  zero_scheduled_days_action,proration_scale,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?,'Synthetic payslip run policy','INR','FULL_PAY',6,'ACTIVE',?,
                  now(),now(),0)
                """, policy, rollover.organisationId(), rollover.schoolId(),
                rollover.actorMembershipId());
        jdbc.update("""
                INSERT INTO payroll_runs(
                  id,organisation_id,school_id,payroll_run_policy_id,policy_snapshot,payroll_month,
                  period_start,period_end,attempt_number,run_code,idempotency_key,input_fingerprint,
                  currency_code,status,staff_count,scheduled_working_days_total,recorded_days_total,
                  payable_days_total,full_gross_total,payable_gross_total,deduction_total,
                  employer_contribution_total,net_total,creation_reason,created_by_membership_id,
                  created_at,submitted_by_membership_id,submitted_at,submission_reason,
                  decided_by_membership_id,decided_at,decision_reason,updated_at,row_version)
                VALUES (?, ?, ?, ?,'{}',DATE '2025-08-01',DATE '2025-08-01',DATE '2025-08-31',
                  1,'PAY-202508-001','synthetic-payslip-finalized-run',?,'INR','FINALIZED',1,
                  0,0,0,20000.00,20000.00,?,?,?,'Synthetic finalized payroll run',?,now(),
                  ?,now(),'Synthetic payroll submission',?,now(),'Synthetic independent finalization',
                  now(),2)
                """, run, rollover.organisationId(), rollover.schoolId(), policy,
                "a".repeat(64), deduction, employer, net, rollover.actorMembershipId(), rollover.actorMembershipId(),
                approverMembership);
        jdbc.queryForObject("SELECT set_config('app.payroll_run_prepare','approved',true)",
                String.class);
        jdbc.update("""
                INSERT INTO payroll_run_staff_results(
                  id,organisation_id,school_id,payroll_run_id,staff_profile_id,salary_profile_id,
                  employee_code_snapshot,staff_name_snapshot,salary_revision_snapshot,
                  scheduled_working_days,recorded_days,payable_days,paid_leave_days,
                  unpaid_leave_days,missing_attendance_days,proration_factor,full_gross_amount,
                  payable_gross_amount,deduction_amount,employer_contribution_amount,net_amount,
                  warning_codes,created_at)
                VALUES (?, ?, ?, ?, ?, ?,'SYN-ROLL-T01','Synthetic Rollover Teacher',1,
                  0,0,0,0,0,0,1,20000.00,20000.00,?,?,?,ARRAY[]::text[],now())
                """, result, rollover.organisationId(), rollover.schoolId(), run, staffId, salary, deduction, employer, net);
        jdbc.update("""
                INSERT INTO payroll_run_staff_lines(
                  id,organisation_id,school_id,payroll_run_staff_result_id,payroll_component_id,
                  component_code_snapshot,component_name_snapshot,component_kind_snapshot,
                  calculation_method_snapshot,attendance_prorated_snapshot,statutory_snapshot,
                  full_amount,payable_amount,created_at)
                VALUES (?, ?, ?, ?, ?,'PAYSLIP_BASIC','Payslip basic salary','EARNING','FIXED',
                  true,false,20000.00,20000.00,now())
                """, runLine, rollover.organisationId(), rollover.schoolId(), result, component);
        if (mixedComponents) {
            for (String kind : List.of("DEDUCTION", "EMPLOYER_CONTRIBUTION")) {
                UUID extra = uuid("69000000", kind.equals("DEDUCTION") ? 10 : 11);
                BigDecimal amount = kind.equals("DEDUCTION") ? deduction : employer;
                jdbc.update("""
                        INSERT INTO payroll_run_staff_lines(id,organisation_id,school_id,payroll_run_staff_result_id,
                          payroll_component_id,component_code_snapshot,component_name_snapshot,component_kind_snapshot,
                          calculation_method_snapshot,attendance_prorated_snapshot,statutory_snapshot,
                          full_amount,payable_amount,created_at)
                        VALUES(?,?,?,?,?,?,?,?,'FIXED',false,false,?,?,now())
                        """, UUID.randomUUID(), rollover.organisationId(), rollover.schoolId(), result, extra,
                        kind, "Synthetic " + kind, kind, amount, amount);
            }
        }
        jdbc.query("SELECT validate_complete_payroll_run(?,?,?)",
                ignored -> null, run, rollover.organisationId(), rollover.schoolId());
        return new PayslipFixture(rollover, staffId, run, approverUser, approverMembership);
    }

    private void insertSyntheticFinalizedRunHeader(
            UUID policyId, UUID runId, UUID organisationId, UUID schoolId,
            UUID makerMembershipId, UUID approverMembershipId, String runCode,
            String idempotencyKey) {
        jdbc.update("""
                INSERT INTO payroll_run_policies(
                  id,organisation_id,school_id,display_name,currency_code,
                  zero_scheduled_days_action,proration_scale,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?,'Synthetic payslip RLS policy','INR','FULL_PAY',6,'INACTIVE',?,
                  now(),now(),0)
                """, policyId, organisationId, schoolId, makerMembershipId);
        jdbc.update("""
                INSERT INTO payroll_runs(
                  id,organisation_id,school_id,payroll_run_policy_id,policy_snapshot,payroll_month,
                  period_start,period_end,attempt_number,run_code,idempotency_key,input_fingerprint,
                  currency_code,status,staff_count,scheduled_working_days_total,recorded_days_total,
                  payable_days_total,full_gross_total,payable_gross_total,deduction_total,
                  employer_contribution_total,net_total,creation_reason,created_by_membership_id,
                  created_at,submitted_by_membership_id,submitted_at,submission_reason,
                  decided_by_membership_id,decided_at,decision_reason,updated_at,row_version)
                VALUES (?, ?, ?, ?,'{}',DATE '2026-01-01',DATE '2026-01-01',DATE '2026-01-31',
                  1,?,?,?,'INR','FINALIZED',1,0,0,0,0,0,0,0,0,'Synthetic RLS payroll run',?,now(),
                  ?,now(),'Synthetic RLS submission',?,now(),'Synthetic RLS finalization',now(),2)
                """, runId, organisationId, schoolId, policyId, runCode, idempotencyKey,
                "b".repeat(64), makerMembershipId, makerMembershipId, approverMembershipId);
    }

    private void insertSyntheticPayslipBatch(
            UUID batchId, UUID runId, UUID organisationId, UUID schoolId,
            UUID makerMembershipId, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO payroll_payslip_batches(
                  id,organisation_id,school_id,payroll_run_id,idempotency_key,
                  request_fingerprint,locale,generation_reason,total_count,
                  generated_by_membership_id,generated_at)
                VALUES (?, ?, ?, ?, ?, ?,'en-IN','Synthetic row security evidence',1,?,now())
                """, batchId, organisationId, schoolId, runId, idempotencyKey,
                "c".repeat(64), makerMembershipId);
    }

    private void insertSyntheticPayrollReversalRequest(
            UUID requestId, UUID runId, UUID organisationId, UUID schoolId,
            UUID requesterMembershipId, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO payroll_run_reversal_requests(
                  id,organisation_id,school_id,payroll_run_id,requested_run_version,
                  idempotency_key,request_fingerprint,request_reason,status,
                  requested_by_membership_id,requested_at,row_version)
                VALUES (?, ?, ?, ?,2,?,?,'Synthetic reversal row security evidence','PENDING',
                  ?,now(),0)
                """, requestId, organisationId, schoolId, runId, idempotencyKey,
                "d".repeat(64), requesterMembershipId);
    }

    private static String syntheticEncryptionKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private static String syntheticP256dh() {
        byte[] key = new byte[65];
        key[0] = 0x04;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private static String syntheticAuth() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    }

    private record PortalFixture(
            UUID organisationId,
            UUID schoolId,
            UUID staffUserId,
            UUID staffMembershipId,
            UUID guardianUserId,
            UUID guardianMembershipId,
            UUID applicationId,
            UUID learnerId,
            UUID guardianId) {}

    private record CurriculumFixture(
            UUID organisationId,
            UUID schoolId,
            UUID userId,
            UUID membershipId,
            UUID sessionId,
            UUID subjectId,
            UUID resourceId,
            UUID publicationId,
            UUID itemId) {}

    private record RolloverFixture(
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID actorUserId,
            UUID actorMembershipId,
            UUID teacherUserId,
            UUID teacherMembershipId,
            UUID sourceSessionId,
            UUID gradeSixSectionId,
            UUID gradeSevenSectionId,
            UUID promotedLearnerId,
            UUID promotedEnrolmentId,
            UUID retainedLearnerId,
            UUID retainedEnrolmentId,
            UUID leavingLearnerId,
            UUID leavingEnrolmentId) {}

    private record PayslipFixture(RolloverFixture rollover, UUID staffId, UUID runId,
            UUID approverUserId, UUID approverMembershipId) {}

    private record StoredPushEvidence(
            boolean endpointProtected,
            boolean hashDoesNotExposeEndpoint,
            String status,
            long version) {}

    private void assertDatabaseRejected(Runnable action, String expectedMessage) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try {
                assertThatThrownBy(action::run).hasMessageContaining(expectedMessage);
            } finally {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
            return null;
        });
    }

    private void insertSyntheticMembership(
            UUID id,
            UUID userId,
            UUID organisationId,
            UUID schoolId) {
        jdbc.update("""
                INSERT INTO memberships (
                    id, user_id, organisation_id, school_id, status,
                    valid_from, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', now(), now(), now())
                """, id, userId, organisationId, schoolId);
    }

    private void insertSyntheticCashYear(UUID id, UUID organisationId, UUID schoolId,
            UUID creatorMembershipId, String code) {
        jdbc.update("""
                INSERT INTO accounting_financial_years(
                  id,organisation_id,school_id,code,display_name,starts_on,ends_on,
                  accounting_basis,currency_code,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 'Synthetic RLS cash year', DATE '2026-04-01',
                  DATE '2027-03-31','CASH','INR','DRAFT',?,now(),now(),0)
                """, id, organisationId, schoolId, code, creatorMembershipId);
    }

    private void insertSyntheticFeeAccountingConfiguration(UUID id,
            UUID organisationId, UUID schoolId, UUID financialYearId,
            UUID creatorMembershipId) {
        jdbc.update("""
                INSERT INTO fee_accounting_configurations(
                  id,organisation_id,school_id,financial_year_id,revision_number,
                  effective_from,accounting_basis_snapshot,currency_code,status,
                  creation_reason,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, 1, DATE '2026-04-01','CASH','INR','DRAFT',
                  'Synthetic row security evidence',?,now(),now(),0)
                """, id, organisationId, schoolId, financialYearId,
                creatorMembershipId);
    }

    private void insertTransportStop(UUID id, UUID organisationId, UUID schoolId,
            UUID campusId, UUID creatorMembershipId, String code) {
        jdbc.update("""
                INSERT INTO transport_stops (id,organisation_id,school_id,campus_id,code,
                    display_name,family_label,status,created_by_membership_id,
                    created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, 'Synthetic stop', 'Synthetic family label', 'ACTIVE', ?,
                    now(), now(), 0)
                """, id, organisationId, schoolId, campusId, code, creatorMembershipId);
    }

    private void insertApplication(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID creatorMembershipId,
            String idempotencyKey) {
        jdbc.update("""
                INSERT INTO admission_applications (
                    id, organisation_id, school_id, idempotency_key, request_fingerprint,
                    intake_session_label, target_grade_code, applicant_legal_name,
                    birth_date, gender_code, status, created_by_membership_id,
                    created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, 'Synthetic session', 'VI', 'Synthetic applicant',
                    DATE '2015-01-01', 'NOT_STATED', 'DRAFT', ?, now(), now(), 0)
                """, id, organisationId, schoolId, idempotencyKey, "1".repeat(64), creatorMembershipId);
    }

    private void insertApplicationGuardian(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID applicationId,
            String displayName,
            String email,
            boolean primaryContact) {
        jdbc.update("""
                INSERT INTO admission_application_guardians (
                    id, organisation_id, school_id, application_id, display_name,
                    relationship_code, email_normalized, primary_contact,
                    pickup_authorized, created_at, updated_at, row_version
                ) VALUES (?, ?, ?, ?, ?, 'PARENT_GUARDIAN', ?, ?, false, now(), now(), 0)
                """, id, organisationId, schoolId, applicationId, displayName, email, primaryContact);
    }

    @Autowired private com.educore.api.ptm.PtmService ptm;
    @Autowired private com.educore.api.ptm.PtmReminderProcessor ptmReminders;
    @Autowired private com.educore.api.ptm.PtmNotificationProcessor ptmNotifications;
    @Autowired private com.educore.api.communication.PtmReminderPublicationService ptmPublications;
    @Autowired private com.educore.api.communication.CommunicationInboxService ptmInbox;
    @Autowired private com.educore.api.identity.SecurityAuditService inboxAudit;

    @Test
    @Transactional
    void communicationStaffInboxRequiresLiveAccountSchoolAndOrganisation() {
        var f=insertPtmFixture(); var p=f.portal();
        assertThat(ptmInbox.staffInbox(f.admin(),p.schoolId()).schoolId()).isEqualTo(p.schoolId());
        for(var target:java.util.Map.of("user_accounts",p.staffUserId(),"schools",p.schoolId(),"organisations",p.organisationId()).entrySet()) {
            jdbc.update("UPDATE "+target.getKey()+" SET status='SUSPENDED' WHERE id=?",target.getValue());
            assertThatThrownBy(() -> ptmInbox.staffInbox(f.admin(),p.schoolId()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMUNICATION_SCOPE_DENIED"));
            assertThatThrownBy(() -> ptmInbox.readStaff(f.admin(),UUID.randomUUID(),p.schoolId()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMUNICATION_SCOPE_DENIED"));
            assertThatThrownBy(() -> ptmInbox.acknowledgeStaff(f.admin(),UUID.randomUUID(),p.schoolId()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMUNICATION_SCOPE_DENIED"));
            jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
        }
        assertThat(ptmInbox.staffInbox(f.admin(),p.schoolId()).schoolId()).isEqualTo(p.schoolId());
    }

    @Test
    @Transactional
    void communicationFamilyInboxRequiresLiveAccountSchoolAndOrganisation() {
        var f=insertPtmFixture(); var p=f.portal();
        assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).learnerId()).isEqualTo(p.learnerId());
        for(var target:java.util.Map.of("user_accounts",p.guardianUserId(),"schools",p.schoolId(),"organisations",p.organisationId()).entrySet()) {
            jdbc.update("UPDATE "+target.getKey()+" SET status='SUSPENDED' WHERE id=?",target.getValue());
            assertThatThrownBy(() -> ptmInbox.familyInbox(f.guardian(),p.learnerId()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
            assertThatThrownBy(() -> ptmInbox.readFamily(f.guardian(),p.learnerId(),UUID.randomUUID()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
            assertThatThrownBy(() -> ptmInbox.acknowledgeFamily(f.guardian(),p.learnerId(),UUID.randomUUID()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
            jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
        }
        assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).learnerId()).isEqualTo(p.learnerId());
    }

    @Test
    @Transactional
    void communicationInboxesRejectForeignOrganisationRoleAuthority() {
        var f=insertPtmFixture(); var p=f.portal();
        var student = syntheticInboxStudent(p);
        UUID studentMembership = student.membershipIds().iterator().next();
        UUID foreignOrganisation=UUID.randomUUID(), foreignRole=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at)
                VALUES(?,?,'Synthetic foreign inbox organisation','Synthetic foreign inbox organisation','ACTIVE',now(),now())
                """,foreignOrganisation,"synthetic-inbox-"+foreignOrganisation);
        jdbc.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,created_at,updated_at)
                VALUES(?,?,'SYNTHETIC_INBOX','Synthetic foreign inbox role',now(),now())
                """,foreignRole,foreignOrganisation);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,'communication.read'),(?,'portal.guardian'),(?,'portal.student')",foreignRole,foreignRole,foreignRole);
        jdbc.update("DELETE FROM membership_roles WHERE membership_id IN (?,?,?)",p.staffMembershipId(),p.guardianMembershipId(),studentMembership);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?),(?,?),(?,?)",
                p.staffMembershipId(),foreignRole,p.guardianMembershipId(),foreignRole,studentMembership,foreignRole);
        assertThatThrownBy(() -> ptmInbox.staffInbox(f.admin(),p.schoolId()))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMUNICATION_SCOPE_DENIED"));
        assertThatThrownBy(() -> ptmInbox.familyInbox(f.guardian(),p.learnerId()))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
        assertThatThrownBy(() -> ptmInbox.studentInbox(student))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("STUDENT_PORTAL_UNAVAILABLE"));
    }

    @Test
    @Transactional
    void communicationStudentInboxRequiresLiveAccountSchoolAndOrganisation() {
        var f = insertPtmFixture();
        var p = f.portal();
        var student = syntheticInboxStudent(p);
        assertThat(ptmInbox.studentInbox(student).learnerId()).isEqualTo(p.learnerId());
        for (var target : java.util.Map.of("user_accounts", student.userId(),
                "schools", p.schoolId(), "organisations", p.organisationId()).entrySet()) {
            jdbc.update("UPDATE " + target.getKey() + " SET status='SUSPENDED' WHERE id=?", target.getValue());
            assertThatThrownBy(() -> ptmInbox.studentInbox(student))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("STUDENT_PORTAL_UNAVAILABLE"));
            assertThatThrownBy(() -> ptmInbox.readStudent(student, UUID.randomUUID()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("STUDENT_PORTAL_UNAVAILABLE"));
            assertThatThrownBy(() -> ptmInbox.acknowledgeStudent(student, UUID.randomUUID()))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("STUDENT_PORTAL_UNAVAILABLE"));
            jdbc.update("UPDATE " + target.getKey() + " SET status='ACTIVE' WHERE id=?", target.getValue());
        }
        assertThat(ptmInbox.studentInbox(student).learnerId()).isEqualTo(p.learnerId());
    }

    @Test
    @Transactional
    void communicationInboxesDenyEmptyForeignExpiredAndFutureMembershipScopes() {
        var f = insertPtmFixture();
        var p = f.portal();
        var student = syntheticInboxStudent(p);
        for (var memberships : List.of(Set.<UUID>of(), Set.of(UUID.randomUUID()))) {
            assertInboxScopesDenied(inboxScopeUser(f.admin(), p.organisationId(), memberships),
                    inboxScopeUser(f.guardian(), p.organisationId(), memberships),
                    inboxScopeUser(student, p.organisationId(), memberships), p);
        }
        UUID otherOrganisation = UUID.randomUUID();
        assertInboxScopesDenied(inboxScopeUser(f.admin(), otherOrganisation, f.admin().membershipIds()),
                inboxScopeUser(f.guardian(), otherOrganisation, f.guardian().membershipIds()),
                inboxScopeUser(student, otherOrganisation, student.membershipIds()), p);
        assertInboxScopesDenied(inboxScopeUser(f.admin(), p.organisationId(), f.guardian().membershipIds()),
                inboxScopeUser(f.guardian(), p.organisationId(), f.admin().membershipIds()),
                inboxScopeUser(student, p.organisationId(), f.admin().membershipIds()), p);
        assertThatThrownBy(() -> ptmInbox.staffInbox(f.admin(), UUID.randomUUID()))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMUNICATION_SCOPE_DENIED"));
        assertThatThrownBy(() -> ptmInbox.familyInbox(f.guardian(), UUID.randomUUID()))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
        UUID studentMembership = student.membershipIds().iterator().next();
        jdbc.update("UPDATE memberships SET valid_from=CURRENT_TIMESTAMP-interval '1 day',valid_until=CURRENT_TIMESTAMP WHERE id IN (?,?,?)",
                p.staffMembershipId(), p.guardianMembershipId(), studentMembership);
        assertInboxScopesDenied(f.admin(), f.guardian(), student, p);
        jdbc.update("UPDATE memberships SET valid_until=NULL,valid_from=CURRENT_TIMESTAMP+interval '1 day' WHERE id IN (?,?,?)",
                p.staffMembershipId(), p.guardianMembershipId(), studentMembership);
        assertInboxScopesDenied(f.admin(), f.guardian(), student, p);
        jdbc.update("UPDATE memberships SET valid_from=CURRENT_TIMESTAMP-interval '1 day' WHERE id IN (?,?,?)",
                p.staffMembershipId(), p.guardianMembershipId(), studentMembership);
        assertThat(ptmInbox.staffInbox(f.admin(), p.schoolId()).schoolId()).isEqualTo(p.schoolId());
        assertThat(ptmInbox.familyInbox(f.guardian(), p.learnerId()).learnerId()).isEqualTo(p.learnerId());
        assertThat(ptmInbox.studentInbox(student).learnerId()).isEqualTo(p.learnerId());
    }

    @Test
    @Transactional
    void communicationFamilyInboxPreservesOwnedNoticeReadRetriesAndHistoricalLearners() {
        var fixture = readyPtmNotification(false);
        var f = fixture.fixture();
        var p = f.portal();
        try (var ignored = TenantRequestContext.open(null, p.organisationId())) {
            assertThat(ptmNotifications.processNext(p.organisationId())).isEqualTo(fixture.outboxId());
        }
        try (var ignored = TenantRequestContext.open(f.guardian().userId(), p.organisationId())) {
            var item = ptmInbox.familyInbox(f.guardian(), p.learnerId()).items().getFirst();
            UUID recipient = item.recipientId();
            assertThat(item.readAt()).isNull();
            var beforeAvailability = new com.educore.api.communication.CommunicationInboxService(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), inboxAudit,
                    java.time.Clock.fixed(item.scheduledFor().minusMillis(1), java.time.ZoneOffset.UTC));
            assertThat(beforeAvailability.familyInbox(f.guardian(), p.learnerId()).items()).isEmpty();
            assertThatThrownBy(() -> beforeAvailability.readFamily(f.guardian(), p.learnerId(), recipient))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMUNICATION_RECIPIENT_NOT_FOUND"));
            assertThatThrownBy(() -> beforeAvailability.acknowledgeFamily(f.guardian(), p.learnerId(), recipient))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMUNICATION_RECIPIENT_NOT_FOUND"));
            assertThat(ptmInbox.staffInbox(f.admin(), p.schoolId()).items()).isEmpty();
            var read = ptmInbox.readFamily(f.guardian(), p.learnerId(), recipient);
            assertThat(ptmInbox.readFamily(f.guardian(), p.learnerId(), recipient)).isEqualTo(read);
            assertThat(ptmInbox.familyInbox(f.guardian(), p.learnerId()).items()).singleElement()
                    .satisfies(current -> assertThat(current.readAt()).isNotNull());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_recipient_events WHERE communication_recipient_id=? AND event_type='READ'",
                    Integer.class, recipient)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM communication_deliveries WHERE communication_recipient_id=? AND channel='IN_APP'",
                    String.class, recipient)).isEqualTo("DELIVERED");
            assertThatThrownBy(() -> ptmInbox.acknowledgeFamily(f.guardian(), p.learnerId(), recipient))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMUNICATION_ACK_NOT_REQUIRED"));
            jdbc.update("UPDATE learners SET status='INACTIVE' WHERE id=?", p.learnerId());
            assertThat(ptmInbox.familyInbox(f.guardian(), p.learnerId()).items()).singleElement()
                    .satisfies(current -> assertThat(current.recipientId()).isEqualTo(recipient));
            jdbc.update("UPDATE schools SET status='SUSPENDED' WHERE id=?", p.schoolId());
            assertThatThrownBy(() -> ptmInbox.readFamily(f.guardian(), p.learnerId(), recipient))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
            assertThatThrownBy(() -> ptmInbox.acknowledgeFamily(f.guardian(), p.learnerId(), recipient))
                    .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_recipient_events WHERE communication_recipient_id=?",
                    Integer.class, recipient)).isEqualTo(1);
            jdbc.update("UPDATE schools SET status='ACTIVE' WHERE id=?", p.schoolId());
            assertThat(ptmInbox.readFamily(f.guardian(), p.learnerId(), recipient)).isEqualTo(read);
        }
    }

    private void assertInboxScopesDenied(AuthenticatedUser staff, AuthenticatedUser guardian,
            AuthenticatedUser student, PortalFixture p) {
        assertThatThrownBy(() -> ptmInbox.staffInbox(staff, p.schoolId()))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMUNICATION_SCOPE_DENIED"));
        assertThatThrownBy(() -> ptmInbox.familyInbox(guardian, p.learnerId()))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FAMILY_CHILD_NOT_FOUND"));
        assertThatThrownBy(() -> ptmInbox.studentInbox(student))
                .isInstanceOfSatisfying(com.educore.api.common.api.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(student.membershipIds().isEmpty()
                                ? "ORGANISATION_SELECTION_REQUIRED" : "STUDENT_PORTAL_UNAVAILABLE"));
    }

    private AuthenticatedUser inboxScopeUser(AuthenticatedUser user, UUID organisationId, Set<UUID> memberships) {
        return new AuthenticatedUser(user.userId(), user.sessionId(), user.loginIdentifier(), user.email(),
                user.displayName(), user.mfaVerified(), organisationId, memberships, user.capabilities(), user.organisations());
    }

    private AuthenticatedUser syntheticInboxStudent(PortalFixture p) {
        UUID studentUser = UUID.randomUUID(), studentMembership = UUID.randomUUID();
        UUID consent = UUID.randomUUID(), portalAccount = UUID.randomUUID();
        String login = "student.synthetic-inbox";
        insertSyntheticLoginUser(studentUser, login, null);
        insertSyntheticMembership(studentMembership, studentUser, p.organisationId(), p.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",
                studentMembership, UUID.fromString("00000000-0000-4000-8000-000000000010"));
        insertPendingPortalConsent(p, consent, login);
        approvePortalConsent(p, consent, studentUser, p.guardianUserId(), p.guardianMembershipId());
        insertPortalAccount(p, portalAccount, consent, studentUser);
        return new AuthenticatedUser(studentUser, UUID.randomUUID(), login, null,
                "Synthetic inbox student", true, p.organisationId(),
                Set.of(studentMembership), Set.of("portal.student"), List.of());
    }

    @Test
    @Transactional
    void ptmRemindersReachTheExistingInboxAndQueueWithoutInventedApprovers() {
        var fixture=readyPtmNotification(true);
        var f=fixture.fixture(); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            assertThat(ptmNotifications.processNext(p.organisationId())).isEqualTo(fixture.outboxId());
            assertThat(ptmNotifications.processNext(p.organisationId())).isNull();
            var inbox=ptmInbox.familyInbox(f.guardian(),p.learnerId());
            assertThat(inbox.items()).hasSize(1);
            UUID notice=inbox.items().getFirst().announcementId();
            assertThat(inbox.items().getFirst().bodyEn()).contains("family portal").doesNotContain("PRIVATE SYNTHETIC TOPIC");
            assertThat(ptmPublications.publish(p.organisationId(),p.schoolId(),fixture.reminderId(),fixture.outboxId())).isEqualTo(notice);
            assertThat(jdbc.queryForMap("SELECT authority_kind,created_by_membership_id,decided_by_membership_id FROM communication_announcements WHERE id=?",notice))
                    .containsEntry("authority_kind","DOMAIN_EVENT").containsEntry("created_by_membership_id",null).containsEntry("decided_by_membership_id",null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcement_history WHERE communication_announcement_id=? AND actor_kind='SYSTEM' AND actor_membership_id IS NULL",Integer.class,notice)).isEqualTo(1);
            UUID delivery=jdbc.queryForObject("""
                    SELECT delivery.id FROM communication_deliveries delivery JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id WHERE recipient.communication_announcement_id=? AND delivery.channel='PUSH'
                    """,UUID.class,notice);
            assertThat(jdbc.queryForObject("SELECT status FROM communication_deliveries WHERE id=?",String.class,delivery)).isEqualTo("AWAITING_PROVIDER");
            jdbc.update("""
                    INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,
                      credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at)
                    VALUES(?,?,?,'PUSH','LIVE','WEB_PUSH','env:APP_PUSH_DELIVERY_PRIVATE_KEY','ACTIVE',?,now(),now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),p.staffMembershipId());
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var clock=java.time.Clock.systemUTC();
            var dispatch=new com.educore.api.communication.PushDeliveryDispatch(named,clock);
            var queue=new PushDeliveryQueue(named,clock);
            assertThat(dispatch.materializeNext(p.organisationId(),p.schoolId())).isEqualTo(delivery);
            var claim=queue.claimNext(p.organisationId(),p.schoolId());
            var preparation=new com.educore.api.communication.PushDeliveryPreparation(named,pushSubscriptionProtector,clock,
                    publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
            assertThat(preparation.prepare(claim).action()).isEqualTo(com.educore.api.communication.PushDeliveryPreparation.Action.SEND);
            ptm.cancelBooking(f.guardian(),p.learnerId(),fixture.bookingId(),
                    new com.educore.api.ptm.FamilyPtmController.CancelBookingRequest(0,"Synthetic cancellation before delivery"));
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            assertThat(ptmNotificationSource.current(p.organisationId(),p.schoolId(),fixture.reminderId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_attempts WHERE organisation_id=? AND school_id=?",Integer.class,p.organisationId(),p.schoolId())).isZero();
        }
    }

    @Test
    @Transactional
    void appliesV046AndGuardsDomainAuthorityRecipientsHistoryAndConsent() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='046'",Boolean.class)).isTrue();
        var fixture=readyPtmNotification(false); var f=fixture.fixture(); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            assertThat(ptmNotificationSource.current(p.organisationId(),UUID.randomUUID(),fixture.reminderId())).isNull();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(() -> ptmNotifications.processNext(p.organisationId())).hasMessageContaining("organisation context");
                assertThatThrownBy(() -> ptmNotificationSource.current(p.organisationId(),p.schoolId(),fixture.reminderId())).hasMessageContaining("organisation context");
            }
            assertDatabaseRejected(() -> {
                jdbc.update("""
                        INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                          audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,scheduled_for,expires_at,status,
                          created_at,updated_at,authority_kind,source_type,source_id,ptm_reminder_id,domain_event_id)
                        SELECT ?,reminder.organisation_id,reminder.school_id,'ACADEMIC','NORMAL','LEARNER_FAMILIES',booking.learner_id,
                          'BILINGUAL','Synthetic reminder','काल्पनिक याद','Synthetic body','काल्पनिक सूचना',evidence.occurred_at,slot.starts_at,
                          'APPROVED',now(),now(),'DOMAIN_EVENT','PTM_REMINDER',reminder.id,reminder.id,evidence.id
                        FROM ptm_booking_reminders reminder JOIN ptm_bookings booking ON booking.id=reminder.ptm_booking_id
                        JOIN ptm_slots slot ON slot.id=reminder.ptm_slot_id JOIN outbox_events evidence ON evidence.id=? WHERE reminder.id=?
                        """,UUID.randomUUID(),fixture.outboxId(),fixture.reminderId());
                jdbc.execute("SET CONSTRAINTS communication_domain_history_required IMMEDIATE");
            },"same-transaction system history");
            UUID notice=ptmPublications.publish(p.organisationId(),p.schoolId(),fixture.reminderId(),fixture.outboxId());
            assertThat(jdbc.queryForObject("""
                    SELECT delivery.status FROM communication_deliveries delivery JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id WHERE recipient.communication_announcement_id=? AND delivery.channel='PUSH'
                    """,String.class,notice)).isEqualTo("SUPPRESSED");
            assertDatabaseRejected(() -> jdbc.update("UPDATE communication_announcements SET authority_kind='HUMAN_REVIEW' WHERE id=?",notice),"immutable");
            assertDatabaseRejected(() -> jdbc.update("UPDATE communication_announcements SET body_en='rewritten' WHERE id=?",notice),"immutable");
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                      guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                    VALUES(?,?,?,?,'GUARDIAN',?,?,?,'EN',true,now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,UUID.randomUUID(),p.learnerId(),p.guardianUserId()),"booking guardian");
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_announcement_history(id,organisation_id,school_id,communication_announcement_id,
                      event_type,new_status,reason,actor_membership_id,occurred_at)
                    VALUES(?,?,?,?,'APPROVED','APPROVED','Synthetic fabricated reviewer',?,now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,p.staffMembershipId()),"actor must match");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            assertThatThrownBy(() -> ptmInbox.familyInbox(f.guardian(),UUID.randomUUID())).hasMessageContaining("verified");
        }
    }

    @Test
    @Transactional
    void ptmNotificationRetriesAreBoundedAndDoNotFabricatePublication() {
        var fixture=readyPtmNotification(false); var p=fixture.fixture().portal();
        var publisher=org.mockito.Mockito.mock(com.educore.api.communication.PtmReminderPublicationService.class);
        org.mockito.Mockito.when(publisher.publish(p.organisationId(),p.schoolId(),fixture.reminderId(),fixture.outboxId()))
                .thenThrow(new IllegalStateException("Synthetic private failure detail"));
        var clock=new PushQueueClock();
        var processor=new com.educore.api.ptm.PtmNotificationProcessor(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),publisher,clock);
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            for(int attempt=1;attempt<=8;attempt++) {
                assertThatThrownBy(() -> processor.processNext(p.organisationId()))
                        .isInstanceOf(com.educore.api.ptm.PtmNotificationProcessor.PublicationException.class)
                        .hasMessage("PTM notification publication failed").hasNoCause();
                processor.recordFailure(p.organisationId(),fixture.outboxId());
                var evidence=jdbc.queryForMap("SELECT attempt_count,processed_at,last_error_code,available_at FROM outbox_events WHERE id=?",fixture.outboxId());
                assertThat(evidence).containsEntry("attempt_count",attempt).containsEntry("processed_at",null)
                        .containsEntry("last_error_code","PTM_NOTIFICATION_FAILED");
                assertThat(((java.sql.Timestamp)evidence.get("available_at")).toInstant()).isAfter(clock.instant());
                assertThat(processor.processNext(p.organisationId())).isNull();
                clock.advance(7200);
            }
            assertThat(processor.processNext(p.organisationId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE authority_kind='DOMAIN_EVENT' AND organisation_id=? AND school_id=?",Integer.class,p.organisationId(),p.schoolId())).isZero();
            org.mockito.Mockito.verify(publisher,org.mockito.Mockito.times(8)).publish(p.organisationId(),p.schoolId(),fixture.reminderId(),fixture.outboxId());
        }
    }

    @Test
    @Transactional
    void familyRequestUpdatesUseTheExistingInboxQueueAndCurrentGuardianAuthority() {
        var fixture=readyRequestNotification(true); var f=fixture.fixture(); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            assertThat(requestNotifications.processNext(p.organisationId())).isEqualTo(fixture.eventId());
            assertThat(requestNotifications.processNext(p.organisationId())).isNull();
            var inbox=ptmInbox.familyInbox(f.guardian(),p.learnerId());
            assertThat(inbox.items()).hasSize(1);
            UUID notice=inbox.items().getFirst().announcementId();
            assertThat(inbox.items().getFirst().bodyEn()).contains("family portal").doesNotContain("PRIVATE SYNTHETIC");
            assertThat(requestPublications.publish(p.organisationId(),p.schoolId(),fixture.eventId())).isEqualTo(notice);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcement_history WHERE communication_announcement_id=? AND actor_kind='SYSTEM'",Integer.class,notice)).isEqualTo(1);
            jdbc.update("""
                    INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,
                      credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at)
                    VALUES(?,?,?,'PUSH','LIVE','WEB_PUSH','env:APP_PUSH_DELIVERY_PRIVATE_KEY','ACTIVE',?,now(),now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),p.staffMembershipId());
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var clock=java.time.Clock.systemUTC();
            var dispatch=new com.educore.api.communication.PushDeliveryDispatch(named,clock);
            assertThat(dispatch.materializeNext(p.organisationId(),p.schoolId())).isNotNull();
            var queue=new PushDeliveryQueue(named,clock);
            var claim=queue.claimNext(p.organisationId(),p.schoolId());
            var preparation=new com.educore.api.communication.PushDeliveryPreparation(named,pushSubscriptionProtector,clock,
                    publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
            assertThat(preparation.prepare(claim).action()).isEqualTo(com.educore.api.communication.PushDeliveryPreparation.Action.SEND);
            assertDatabaseRejected(() -> jdbc.update("UPDATE communication_announcements SET body_en='changed' WHERE id=?",notice),"immutable");
            assertDatabaseRejected(() -> jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                      guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                    SELECT ?,organisation_id,school_id,communication_announcement_id,recipient_kind,?,learner_id,
                      user_account_id,preferred_language,contact_eligible,available_at,created_at FROM communication_recipients
                    WHERE communication_announcement_id=?
                    """,UUID.randomUUID(),UUID.randomUUID(),notice),"current request guardian");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.update("DELETE FROM membership_roles WHERE membership_id=?",p.guardianMembershipId());
            assertThat(requestNotificationSource.current(p.organisationId(),p.schoolId(),fixture.eventId())).isNull();
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_attempts WHERE organisation_id=? AND school_id=?",Integer.class,p.organisationId(),p.schoolId())).isZero();
        }
    }

    @Test
    @Transactional
    void appliesV047AndResolvesOnlyUnambiguousPublicRequestEvidenceUnderRowSecurity() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='047'",Boolean.class)).isTrue();
        var fixture=readyRequestNotification(false); var f=fixture.fixture(); var p=f.portal();
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            assertThat(requestNotificationSource.current(p.organisationId(),UUID.randomUUID(),fixture.eventId())).isNull();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(() -> requestNotifications.processNext(p.organisationId())).hasMessageContaining("organisation context");
                assertThatThrownBy(() -> requestNotificationSource.current(p.organisationId(),p.schoolId(),fixture.eventId())).hasMessageContaining("organisation context");
            }
            jdbc.update("UPDATE outbox_events SET payload=payload-'historyId'-'messageId'-'organisationId' WHERE id=?",fixture.eventId());
            assertThat(requestNotificationSource.current(p.organisationId(),p.schoolId(),fixture.eventId())).isNotNull();
            UUID notice=requestPublications.publish(p.organisationId(),p.schoolId(),fixture.eventId());
            assertThat(jdbc.queryForObject("""
                    SELECT delivery.status FROM communication_deliveries delivery JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id WHERE recipient.communication_announcement_id=? AND delivery.channel='PUSH'
                    """,String.class,notice)).isEqualTo("SUPPRESSED");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("CREATE ROLE educore_request_source_rls_test NOLOGIN");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_request_source_rls_test");
            jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO educore_request_source_rls_test");
            jdbc.execute("SET LOCAL ROLE educore_request_source_rls_test");
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM parent_request_notification_sources WHERE event_id=?",Integer.class,fixture.eventId())).isEqualTo(1);
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM parent_request_notification_sources WHERE event_id=?",Integer.class,fixture.eventId())).isZero();
            jdbc.execute("RESET ROLE");
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
            jdbc.update("""
                    INSERT INTO parent_request_history(id,organisation_id,school_id,parent_request_id,event_type,previous_status,
                      new_status,previous_priority,new_priority,previous_assignee_id,new_assignee_id,reason,actor_membership_id,occurred_at)
                    SELECT ?,organisation_id,school_id,parent_request_id,event_type,previous_status,new_status,previous_priority,
                      new_priority,previous_assignee_id,new_assignee_id,reason,actor_membership_id,occurred_at
                    FROM parent_request_history WHERE id=(SELECT parent_request_history_id FROM communication_announcements WHERE id=?)
                    """,UUID.randomUUID(),notice);
            assertThat(requestNotificationSource.current(p.organisationId(),p.schoolId(),fixture.eventId())).isNull();
        }
    }

    @Test
    @Transactional
    void requestNotificationFailuresAreBoundedAndPreserveUnpublishedEvidence() {
        var fixture=readyRequestNotification(false); var p=fixture.fixture().portal();
        var publication=org.mockito.Mockito.mock(com.educore.api.communication.ParentRequestPublicationService.class);
        org.mockito.Mockito.when(publication.publish(p.organisationId(),p.schoolId(),fixture.eventId())).thenThrow(new IllegalStateException("PRIVATE SYNTHETIC failure"));
        var clock=new PushQueueClock();
        var processor=new com.educore.api.parentrequests.ParentRequestNotificationProcessor(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),publication,clock);
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            for(int attempt=1;attempt<=8;attempt++) {
                assertThatThrownBy(() -> processor.processNext(p.organisationId()))
                        .isInstanceOf(com.educore.api.parentrequests.ParentRequestNotificationProcessor.PublicationException.class)
                        .hasMessage("Request notification publication failed").hasNoCause();
                processor.recordFailure(p.organisationId(),fixture.eventId());
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE id=?",Integer.class,fixture.eventId())).isEqualTo(attempt);
                assertThat(processor.processNext(p.organisationId())).isNull();
                clock.advance(7200);
            }
            assertThat(processor.processNext(p.organisationId())).isNull();
            assertThat(jdbc.queryForMap("SELECT processed_at,last_error_code FROM outbox_events WHERE id=?",fixture.eventId()))
                    .containsEntry("processed_at",null).containsEntry("last_error_code","REQUEST_NOTIFICATION_FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE organisation_id=? AND school_id=?",Integer.class,p.organisationId(),p.schoolId())).isZero();
            org.mockito.Mockito.verify(publication,org.mockito.Mockito.times(8)).publish(p.organisationId(),p.schoolId(),fixture.eventId());
        }
    }

    @Test
    @Transactional
    void requestFamilyNotificationsFollowPublicLifecycleAndExpireWithoutPrivateNotes() {
        var fixture=readyRequestNotification(false); var f=fixture.fixture(); var p=f.portal();
        for(var transition:List.of(new Object[]{"RESOLVE",3L},new Object[]{"CLOSE",4L},new Object[]{"REOPEN",5L})) {
            parentRequests.transition(f.admin(),fixture.requestId(),new com.educore.api.parentrequests.ParentRequestController.TransitionRequest(
                    p.schoolId(),(String)transition[0],(Long)transition[1],"Synthetic lifecycle update"));
        }
        try(var ignored=TenantRequestContext.open(null,p.organisationId())) {
            var processed=new java.util.HashSet<UUID>();
            for(int index=0;index<4;index++) assertThat(processed.add(requestNotifications.processNext(p.organisationId()))).isTrue();
            assertThat(processed).doesNotContainNull().hasSize(4);
            assertThat(requestNotifications.processNext(p.organisationId())).isNull();
            assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).items()).hasSize(4)
                    .allSatisfy(item -> assertThat(item.bodyEn()).doesNotContain("PRIVATE SYNTHETIC"));
            var expired=new com.educore.api.parentrequests.ParentRequestNotificationSource(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                    java.time.Clock.fixed(java.time.Instant.now().plusSeconds(8*86400),java.time.ZoneOffset.UTC));
            assertThat(expired.current(p.organisationId(),p.schoolId(),fixture.eventId())).isNull();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM communication_announcements notice JOIN parent_request_history history
                      ON history.id=notice.parent_request_history_id
                    WHERE notice.organisation_id=? AND notice.school_id=? AND history.reason='Internal staff note added'
                    """,Integer.class,p.organisationId(),p.schoolId())).isZero();
        }
    }

    @Test
    @Transactional
    void attendanceRechecksMembershipValidityBeforeReadingOrWriting() {
        var fixture=insertPtmFixture(); var p=fixture.portal();
        assertThat(attendanceService.snapshot(fixture.admin(),p.schoolId(),null).schoolId()).isEqualTo(p.schoolId());
        jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",p.staffMembershipId());
        assertThatThrownBy(() -> attendanceService.snapshot(fixture.admin(),p.schoolId(),null)).hasMessageContaining("Attendance access is unavailable");
        assertThatThrownBy(() -> attendanceService.openRegister(fixture.admin(),new com.educore.api.attendance.AttendanceController.OpenRegisterRequest(
                p.schoolId(),UUID.randomUUID(),UUID.randomUUID(),null,"DAILY"))).hasMessageContaining("Attendance access is unavailable");
        jdbc.update("UPDATE memberships SET valid_from=now()+interval '1 day',valid_until=NULL WHERE id=?",p.staffMembershipId());
        assertThatThrownBy(() -> attendanceService.snapshot(fixture.admin(),p.schoolId(),null)).hasMessageContaining("Attendance access is unavailable");
    }

    @Test
    @Transactional
    void attendanceRequiresActiveAccountSchoolOrganisationAndRequestedScope() {
        var fixture=insertPtmFixture(); var p=fixture.portal();
        assertThat(attendanceService.snapshot(fixture.admin(),p.schoolId(),null).schoolId()).isEqualTo(p.schoolId());
        assertThatThrownBy(() -> attendanceService.snapshot(fixture.admin(),UUID.randomUUID(),null)).hasMessageContaining("Attendance access is unavailable");
        for(var target:java.util.Map.of("user_accounts",p.staffUserId(),"schools",p.schoolId(),"organisations",p.organisationId()).entrySet()) {
            jdbc.update("UPDATE "+target.getKey()+" SET status='SUSPENDED' WHERE id=?",target.getValue());
            assertThatThrownBy(() -> attendanceService.snapshot(fixture.admin(),p.schoolId(),null)).hasMessageContaining("Attendance access is unavailable");
            assertThatThrownBy(() -> attendanceService.openRegister(fixture.admin(),new com.educore.api.attendance.AttendanceController.OpenRegisterRequest(
                    p.schoolId(),UUID.randomUUID(),UUID.randomUUID(),null,"DAILY"))).hasMessageContaining("Attendance access is unavailable");
            jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
        }
    }

    private RequestNoticeFixture readyRequestNotification(boolean registerDevice) {
        var f=insertPtmFixture(); var p=f.portal();
        if(registerDevice) pushNotifications.register(f.guardian(),new RegisterPushSubscriptionRequest(p.schoolId(),
                "https://push.example.org/request/synthetic",syntheticP256dh(),syntheticAuth(),"Synthetic request device"));
        var opened=parentRequests.open(f.guardian(),p.learnerId(),new com.educore.api.parentrequests.FamilyParentRequestController.OpenRequest(
                "ATTENDANCE","PRIVATE SYNTHETIC subject","PRIVATE SYNTHETIC question","synthetic-request-notice"));
        UUID staff=jdbc.queryForObject("SELECT id FROM staff_profiles WHERE user_account_id=? AND school_id=?",UUID.class,p.staffUserId(),p.schoolId());
        parentRequests.assign(f.admin(),opened.id(),new com.educore.api.parentrequests.ParentRequestController.AssignmentRequest(
                p.schoolId(),staff,"NORMAL",0,"Synthetic assignment"));
        parentRequests.staffReply(f.admin(),opened.id(),new com.educore.api.parentrequests.ParentRequestController.StaffReplyRequest(
                p.schoolId(),"PRIVATE SYNTHETIC visible response",false,"synthetic-visible-reply",1));
        parentRequests.staffReply(f.admin(),opened.id(),new com.educore.api.parentrequests.ParentRequestController.StaffReplyRequest(
                p.schoolId(),"PRIVATE SYNTHETIC internal note",true,"synthetic-private-note",2));
        UUID event=jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='PARENT_REQUEST' AND aggregate_id=? AND event_type='parent_request.staff_replied'",UUID.class,opened.id().toString());
        return new RequestNoticeFixture(f,opened.id(),event);
    }
    private record RequestNoticeFixture(PtmFixture fixture,UUID requestId,UUID eventId) {}

    private PtmNoticeFixture readyPtmNotification(boolean registerDevice) {
        var f=insertPtmFixture(); var p=f.portal();
        if(registerDevice) pushNotifications.register(f.guardian(),new RegisterPushSubscriptionRequest(p.schoolId(),
                "https://push.example.org/ptm/synthetic",syntheticP256dh(),syntheticAuth(),"Synthetic PTM device"));
        var event=ptm.create(f.admin(),f.request());
        ptm.publish(f.admin(),event.id(),new com.educore.api.ptm.PtmController.EventDecisionRequest(p.schoolId(),0,"Synthetic PTM publication"));
        var slot=ptm.familySchedule(f.guardian(),p.learnerId()).events().getFirst().slots().getFirst();
        var booking=ptm.book(f.guardian(),p.learnerId(),slot.id(),new com.educore.api.ptm.FamilyPtmController.BookRequest("PRIVATE SYNTHETIC TOPIC","synthetic-notice-booking"));
        UUID reminder=ptmReminders.processNext(p.organisationId());
        assertThat(reminder).isNotNull();
        UUID outbox=jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='PTM_REMINDER' AND aggregate_id=?",UUID.class,reminder.toString());
        return new PtmNoticeFixture(f,booking.id(),reminder,outbox);
    }
    private record PtmNoticeFixture(PtmFixture fixture,UUID bookingId,UUID reminderId,UUID outboxId) {}

    @Test
    @Transactional
    void ptmBookingsAreScopedRetrySafeReschedulableAndAudited() {
        var fixture = insertPtmFixture();
        var portal = fixture.portal();
        var draft = ptm.create(fixture.admin(), fixture.request());
        assertThat(ptm.create(fixture.admin(), fixture.request())).isEqualTo(draft);
        assertThat(ptm.familySchedule(fixture.guardian(), portal.learnerId()).events()).isEmpty();
        ptm.publish(fixture.admin(), draft.id(), new com.educore.api.ptm.PtmController.EventDecisionRequest(
                portal.schoolId(), 0, "Synthetic publication review"));
        var event = ptm.familySchedule(fixture.guardian(), portal.learnerId()).events().getFirst();
        var booked = ptm.book(fixture.guardian(), portal.learnerId(), event.slots().get(0).id(),
                new com.educore.api.ptm.FamilyPtmController.BookRequest("Synthetic topic", "ptm-book-key"));
        assertThat(ptm.book(fixture.guardian(), portal.learnerId(), event.slots().get(0).id(),
                new com.educore.api.ptm.FamilyPtmController.BookRequest("Synthetic topic", "ptm-book-key"))).isEqualTo(booked);
        assertThatThrownBy(() -> ptm.book(fixture.guardian(), portal.learnerId(), event.slots().get(1).id(),
                new com.educore.api.ptm.FamilyPtmController.BookRequest("Synthetic topic", "ptm-book-key")))
                .hasMessageContaining("retry key");
        assertDatabaseRejected(() -> ptm.book(fixture.guardian(), portal.learnerId(), event.slots().get(1).id(),
                new com.educore.api.ptm.FamilyPtmController.BookRequest(null, "ptm-duplicate-key")), "cannot be booked");
        assertThatThrownBy(() -> ptm.familySchedule(fixture.guardian(), UUID.randomUUID()))
                .hasMessageContaining("verified relationship");
        assertThatThrownBy(() -> ptm.workspace(fixture.guardian(), portal.schoolId()))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> ptm.workspace(fixture.admin(), UUID.randomUUID()))
                .hasMessageContaining("unavailable");
        var otherTenant = new AuthenticatedUser(fixture.admin().userId(), UUID.randomUUID(),
                "synthetic@example.test", "synthetic@example.test", "Synthetic", true, UUID.randomUUID(),
                fixture.admin().membershipIds(), fixture.admin().capabilities(), List.of());
        assertThatThrownBy(() -> ptm.workspace(otherTenant, portal.schoolId())).hasMessageContaining("unavailable");
        var moved = ptm.reschedule(fixture.guardian(), portal.learnerId(), booked.id(),
                new com.educore.api.ptm.FamilyPtmController.RescheduleRequest(event.slots().get(1).id(), 0,
                        "Synthetic appointment rescheduled"));
        assertThat(moved.rowVersion()).isEqualTo(1);
        assertThatThrownBy(() -> ptm.cancelBooking(fixture.guardian(), portal.learnerId(), booked.id(),
                new com.educore.api.ptm.FamilyPtmController.CancelBookingRequest(0, "Stale cancellation")))
                .hasMessageContaining("changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptm_booking_reminders WHERE ptm_booking_id=? AND status='CANCELLED'",
                Integer.class, booked.id())).isEqualTo(1);
        assertThat(ptmReminders.processNext(portal.organisationId())).isNotNull();
        assertThat(ptmReminders.processNext(portal.organisationId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='ptm.booking_reminder'",
                Integer.class)).isEqualTo(1);
        var cancelled = ptm.cancelBooking(fixture.guardian(), portal.learnerId(), booked.id(),
                new com.educore.api.ptm.FamilyPtmController.CancelBookingRequest(1, "Synthetic cancellation"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptm_booking_history WHERE ptm_booking_id=?",
                Integer.class, booked.id())).isEqualTo(3);
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM ptm_bookings WHERE id=?", booked.id()), "cannot be deleted");
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM ptm_booking_history WHERE ptm_booking_id=?", booked.id()), "append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE subject_type='PTM_BOOKING' AND subject_id=?",
                Integer.class, booked.id().toString())).isEqualTo(3);
    }

    @Test
    @Transactional
    void ptmPublicationRevalidatesAssignmentsAndManagementCanCancelAfterFamilyDeadline() {
        var fixture = insertPtmFixture();
        var portal = fixture.portal();
        var draft = ptm.create(fixture.admin(), fixture.request());
        assertDatabaseRejected(() -> {
            jdbc.update("""
                    UPDATE staff_assignments SET status='ENDED',ends_on=CURRENT_DATE,
                      ending_reason='Synthetic assignment ended',ended_by_membership_id=?,
                      updated_at=now(),row_version=row_version+1 WHERE id=?
                    """, portal.staffMembershipId(), fixture.assignmentId());
            ptm.publish(fixture.admin(), draft.id(),
                new com.educore.api.ptm.PtmController.EventDecisionRequest(portal.schoolId(), 0,
                        "Synthetic invalid publication"));
        }, "no longer valid");
        ptm.publish(fixture.admin(), draft.id(), new com.educore.api.ptm.PtmController.EventDecisionRequest(
                portal.schoolId(), 0, "Synthetic valid publication"));
        var slot = ptm.familySchedule(fixture.guardian(), portal.learnerId()).events().getFirst().slots().getFirst();
        var booking = ptm.book(fixture.guardian(), portal.learnerId(), slot.id(),
                new com.educore.api.ptm.FamilyPtmController.BookRequest(null, "ptm-deadline-key"));
        var afterDeadline = new com.educore.api.ptm.PtmService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                applicationContext.getBean(com.educore.api.identity.SecurityAuditService.class),
                applicationContext.getBean(tools.jackson.databind.ObjectMapper.class),
                java.time.Clock.fixed(fixture.request().changesCloseAt().plusSeconds(1), java.time.ZoneOffset.UTC));
        assertThatThrownBy(() -> afterDeadline.cancelBooking(fixture.guardian(), portal.learnerId(), booking.id(),
                new com.educore.api.ptm.FamilyPtmController.CancelBookingRequest(0, "Family too late")))
                .hasMessageContaining("no longer be changed");
        assertThat(afterDeadline.cancelEvent(fixture.admin(), draft.id(),
                new com.educore.api.ptm.PtmController.EventDecisionRequest(portal.schoolId(), 1,
                        "Management safety cancellation")) .status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM ptm_bookings WHERE id=?", String.class, booking.id()))
                .isEqualTo("CANCELLED");
        assertThat(ptmReminders.processNext(portal.organisationId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptm_booking_reminders WHERE ptm_booking_id=? AND status='PENDING'",
                Integer.class, booking.id())).isZero();
    }

    @Autowired private org.springframework.context.ApplicationContext applicationContext;

    @Autowired private com.educore.api.studentleave.StudentLeaveService studentLeave;
    @Autowired private com.educore.api.studentleave.StudentLeaveNotificationSource leaveNotificationSource;
    @Autowired private com.educore.api.studentleave.StudentLeaveSubmissionNotificationSource leaveSubmissionSource;
    @Autowired private com.educore.api.studentleave.StudentLeaveNotificationProcessor leaveNotifications;
    @Autowired private com.educore.api.communication.StudentLeavePublicationService leavePublications;
    @Autowired private com.educore.api.communication.StudentLeaveSubmissionPublicationService leaveSubmissionPublications;
    @Autowired private com.educore.api.communication.DomainFamilyDelivery domainFamilyDelivery;
    @Autowired private com.educore.api.transport.FamilyTransportService familyTransport;
    @Autowired private com.educore.api.transport.TransportService authorityTransport;
    @Autowired private com.educore.api.identity.SecurityAuditService nonTravelAudit;

    @Test @Transactional
    void parentNonTravelPlansAreAtomicAppendOnlyAndReplayWithoutDuplicateAudit() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var service=nonTravelAt(f,6,0);
        var initial=service.policies(f.family().admin(),p.schoolId()).policies().getFirst();
        assertThat(initial.enabled()).isFalse(); assertThat(initial.version()).isEqualTo(-1);
        assertThat(initial.campusId()).isEqualTo(f.campus()); assertThat(initial.id()).isNull();
        assertThat(service.familyDay(f.family().guardian(),p.learnerId()).journeys()).hasSize(2)
                .allMatch(j -> !j.changesAllowed());
        enableNonTravel(f,service,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var request=nonTravelRequest(f,"FULL_DAY",true,null,null);
        var first=service.change(f.family().guardian(),p.learnerId(),request);
        assertThat(service.change(f.family().guardian(),p.learnerId(),request)).isEqualTo(first);
        var day=service.familyDay(f.family().guardian(),p.learnerId());
        assertThat(day.journeys()).hasSize(2).allMatch(j -> j.notTravelling() && j.changesAllowed());
        assertThat(day.history()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='transport.non-travel.change' AND actor_user_id=?",
                Integer.class,p.guardianUserId())).isEqualTo(1);
        var cancelled=service.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"FULL_DAY",false,first.id(),first.id()));
        assertThat(cancelled.occurredAt()).isAfter(first.occurredAt());
        assertThat(service.familyDay(f.family().guardian(),p.learnerId()).journeys()).allMatch(j -> !j.notTravelling());
        assertThat(service.familyDay(f.family().guardian(),p.learnerId()).history()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_trip_events",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_transport_assignments WHERE learner_id=? AND status='ACTIVE'",
                Integer.class,p.learnerId())).isEqualTo(2);
    }

    @Test @Transactional
    void parentNonTravelRejectsStaleInvalidAndLateChangesWithoutPartialFullDayWrite() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var early=nonTravelAt(f,6,0);
        enableNonTravel(f,early,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var guardian=f.family().guardian();
        assertThatThrownBy(() -> early.change(guardian,p.learnerId(),nonTravelRequest(f,"PICKUP",false,null,null)))
                .hasMessageContaining("no current non-travel plan");
        var invalidDay=new com.educore.api.transport.TransportNonTravelModels.ChangeRequest(UUID.randomUUID(),f.day().plusDays(1),
                "PICKUP",true,f.pickup(),null,null,null);
        assertThatThrownBy(() -> early.change(guardian,p.learnerId(),invalidDay)).hasMessageContaining("today's school-local");
        var wrongAssignment=new com.educore.api.transport.TransportNonTravelModels.ChangeRequest(UUID.randomUUID(),f.day(),
                "PICKUP",true,f.drop(),null,null,null);
        assertThatThrownBy(() -> early.change(guardian,p.learnerId(),wrongAssignment)).hasMessageContaining("unavailable");
        var late=nonTravelAt(f,7,0);
        assertThatThrownBy(() -> late.change(guardian,p.learnerId(),nonTravelRequest(f,"FULL_DAY",true,null,null)))
                .hasMessageContaining("Changes are closed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes WHERE learner_id=?",Integer.class,p.learnerId())).isZero();
        var request=nonTravelRequest(f,"DROP",true,null,null); var first=late.change(guardian,p.learnerId(),request);
        assertThatThrownBy(() -> late.change(guardian,p.learnerId(),nonTravelRequest(f,"DROP",true,null,null)))
                .hasMessageContaining("changed");
        var reused=new com.educore.api.transport.TransportNonTravelModels.ChangeRequest(request.clientRequestId(),f.day(),
                "DROP",false,null,f.drop(),null,first.id());
        assertThatThrownBy(() -> late.change(guardian,p.learnerId(),reused)).hasMessageContaining("different transport plan");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes WHERE learner_id=?",Integer.class,p.learnerId())).isEqualTo(1);
    }

    @Test @Transactional
    void parentNonTravelKeepsOriginalDeadlineAfterPolicyExtensionAndRechecksRetryAuthority() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var early=nonTravelAt(f,6,0);
        enableNonTravel(f,early,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var request=nonTravelRequest(f,"PICKUP",true,null,null);
        var first=early.change(f.family().guardian(),p.learnerId(),request);
        var later=nonTravelAt(f,8,0);
        enableNonTravel(f,later,LocalTime.of(9,0),LocalTime.of(17,0),0L);
        assertThatThrownBy(() -> later.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"PICKUP",false,first.id(),null)))
                .hasMessageContaining("Changes are closed");
        assertThat(later.familyDay(f.family().guardian(),p.learnerId()).journeys().stream().filter(j -> "PICKUP".equals(j.journeyKind())))
                .allMatch(j -> !j.changesAllowed());
        assertThat(later.change(f.family().guardian(),p.learnerId(),request)).isEqualTo(first);
        jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=?",p.learnerId());
        assertThatThrownBy(() -> later.change(f.family().guardian(),p.learnerId(),request)).hasMessageContaining("verified relationship");
    }

    @Test @Transactional
    void parentNonTravelRequiresLiveOwnChildAndCurrentManagementAuthority() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var service=nonTravelAt(f,6,0);
        enableNonTravel(f,service,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var request=nonTravelRequest(f,"FULL_DAY",true,null,null);
        assertThatThrownBy(() -> service.change(f.family().guardian(),UUID.randomUUID(),request)).hasMessageContaining("verified relationship");
        assertThatThrownBy(() -> service.familyDay(inboxScopeUser(f.family().guardian(),UUID.randomUUID(),
                f.family().guardian().membershipIds()),p.learnerId())).hasMessageContaining("verified relationship");
        assertThatThrownBy(() -> service.change(inboxScopeUser(f.family().guardian(),p.organisationId(),Set.of()),p.learnerId(),request))
                .hasMessageContaining("verified relationship");
        assertThatThrownBy(() -> service.policies(f.family().guardian(),p.schoolId())).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> service.policies(f.family().admin(),UUID.randomUUID())).hasMessageContaining("unavailable");
        for(var target:java.util.Map.of("schools",p.schoolId(),"organisations",p.organisationId(),"user_accounts",p.guardianUserId()).entrySet()) {
            jdbc.update("UPDATE "+target.getKey()+" SET status='SUSPENDED' WHERE id=?",target.getValue());
            assertThatThrownBy(() -> service.change(f.family().guardian(),p.learnerId(),request)).hasMessageContaining("verified relationship");
            jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
        }
        jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",p.guardianMembershipId());
        assertThatThrownBy(() -> service.change(f.family().guardian(),p.learnerId(),request)).hasMessageContaining("verified relationship");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes WHERE learner_id=?",Integer.class,p.learnerId())).isZero();
    }

    @Test @Transactional
    void parentNonTravelDoesNotCarryAnOldPlanOntoAChangedJourneyAssignment() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var service=nonTravelAt(f,6,0);
        enableNonTravel(f,service,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var first=service.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"PICKUP",true,null,null));
        authorityTransport.endLearnerAssignment(f.family().admin(),f.pickup(),new com.educore.api.transport.TransportController.EndRequest(
                p.schoolId(),f.day(),0,"Synthetic change in assigned journey"));
        UUID replacement=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO learner_transport_assignments(id,organisation_id,school_id,learner_id,transport_route_revision_id,
                  transport_route_stop_id,journey_kind,starts_on,status,created_by_membership_id,created_at,updated_at,row_version)
                SELECT ?,organisation_id,school_id,learner_id,transport_route_revision_id,transport_route_stop_id,journey_kind,
                  starts_on,'ACTIVE',created_by_membership_id,now(),now(),0 FROM learner_transport_assignments WHERE id=?
                """,replacement,f.pickup());
        var journey=service.familyDay(f.family().guardian(),p.learnerId()).journeys().stream()
                .filter(j -> "PICKUP".equals(j.journeyKind())).findFirst().orElseThrow();
        assertThat(journey.assignmentChanged()).isTrue(); assertThat(journey.notTravelling()).isFalse();
        assertThat(journey.revisionId()).isEqualTo(first.id()); assertThat(journey.changesAllowed()).isTrue();
        var request=new com.educore.api.transport.TransportNonTravelModels.ChangeRequest(UUID.randomUUID(),f.day(),"PICKUP",true,
                replacement,null,first.id(),null);
        service.change(f.family().guardian(),p.learnerId(),request);
        assertThat(service.familyDay(f.family().guardian(),p.learnerId()).journeys().stream().filter(j -> "PICKUP".equals(j.journeyKind())))
                .allMatch(j -> j.notTravelling() && !j.assignmentChanged());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_trip_events",Integer.class)).isZero();
    }

    @Test @Transactional
    void appliesV060AndProtectsNonTravelHistoryWithRealRowSecurityAndDatabaseGuards() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var service=nonTravelAt(f,6,0);
        var policy=enableNonTravel(f,service,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var first=service.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"FULL_DAY",true,null,null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='060' AND success",Integer.class)).isEqualTo(1);
        assertDatabaseRejected(() -> jdbc.update("UPDATE transport_non_travel_changes SET not_travelling=false WHERE id=?",first.id()),"append-only");
        assertDatabaseRejected(() -> jdbc.update("DELETE FROM transport_non_travel_policy_revisions WHERE id=?",policy.id()),"append-only");
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO transport_non_travel_policy_revisions(id,organisation_id,school_id,campus_id,revision,enabled,
                  pickup_closes_at,drop_closes_at,reason,actor_membership_id,occurred_at)
                SELECT ?,organisation_id,school_id,campus_id,revision+1,true,pickup_closes_at,drop_closes_at,
                  'Synthetic forged guardian policy',?,occurred_at FROM transport_non_travel_policy_revisions WHERE id=?
                """,UUID.randomUUID(),p.guardianMembershipId(),policy.id()),"authority or revision is invalid");
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO transport_non_travel_changes(id,organisation_id,school_id,campus_id,learner_id,service_date,journeys,
                  not_travelling,client_request_id,actor_user_id,actor_membership_id,policy_revision_id,pickup_assignment_id,drop_assignment_id,
                  previous_pickup_revision_id,previous_drop_revision_id,pickup_closes_at,drop_closes_at,request_fingerprint,occurred_at)
                SELECT ?,organisation_id,school_id,campus_id,learner_id,service_date,journeys,false,?,actor_user_id,actor_membership_id,
                  policy_revision_id,pickup_assignment_id,drop_assignment_id,id,id,pickup_closes_at+interval '1 hour',drop_closes_at,
                  request_fingerprint,occurred_at+interval '1 second' FROM transport_non_travel_changes WHERE id=?
                """,UUID.randomUUID(),UUID.randomUUID(),first.id()),"changes are closed");
        jdbc.execute("CREATE ROLE educore_non_travel_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_non_travel_rls_test");
        jdbc.execute("GRANT SELECT ON transport_non_travel_changes,transport_non_travel_policy_revisions TO educore_non_travel_rls_test");
        try {
            jdbc.execute("SET LOCAL ROLE educore_non_travel_rls_test");
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_policy_revisions",Integer.class)).isEqualTo(1);
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_policy_revisions",Integer.class)).isZero();
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id','',true)",String.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes",Integer.class)).isZero();
        } finally { jdbc.execute("RESET ROLE"); }
    }

    @Test @Transactional
    void parentNonTravelClosesAtDepartureAndRejectsMissingOrStalePolicyAndDatedEnrolment() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var early=nonTravelAt(f,6,0);
        assertThatThrownBy(() -> early.savePolicy(f.family().admin(),new com.educore.api.transport.TransportNonTravelModels.PolicyRequest(
                p.schoolId(),f.campus(),true,null,LocalTime.of(12,0),null,"Synthetic invalid policy"))).hasMessageContaining("Choose both");
        enableNonTravel(f,early,LocalTime.of(10,0),LocalTime.of(18,0),null);
        assertThatThrownBy(() -> enableNonTravel(f,early,LocalTime.of(7,0),LocalTime.of(12,0),null)).hasMessageContaining("changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_policy_revisions WHERE campus_id=?",Integer.class,f.campus())).isEqualTo(1);
        var departure=nonTravelAt(f,9,0);
        assertThatThrownBy(() -> departure.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"PICKUP",true,null,null)))
                .hasMessageContaining("Changes are closed");
        jdbc.update("UPDATE student_enrolments SET ends_on=CURRENT_DATE-1 WHERE learner_id=?",p.learnerId());
        assertThatThrownBy(() -> early.familyDay(f.family().guardian(),p.learnerId())).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> early.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"DROP",true,null,null)))
                .hasMessageContaining("unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_non_travel_changes WHERE learner_id=?",Integer.class,p.learnerId())).isZero();
    }

    @Test @Transactional
    void parentNonTravelIsVisibleOnlyToAssignedOperatorsAndNeverAutomaticallyMarksAbsence() {
        var f=insertNonTravelFixture(); var p=f.family().portal(); var service=nonTravelAt(f,6,0);
        enableNonTravel(f,service,LocalTime.of(7,0),LocalTime.of(12,0),null);
        var trip=insertNonTravelTripFixture(f);
        var request=nonTravelRequest(f,"PICKUP",true,null,null);
        var first=service.change(f.family().guardian(),p.learnerId(),request);
        var snapshot=authorityTransport.snapshot(trip.operator(),p.schoolId());
        assertThat(snapshot.roster()).hasSize(1); var roster=snapshot.roster().getFirst();
        assertThat(roster.parentNotTravelling()).isTrue(); assertThat(roster.parentPlanUpdatedAt()).isEqualTo(first.occurredAt());
        assertThat(roster.parentPlanAssignmentChanged()).isFalse();
        assertThat(authorityTransport.snapshot(trip.unassigned(),p.schoolId()).roster()).isEmpty();
        assertThatThrownBy(() -> service.policies(trip.operator(),p.schoolId())).hasMessageContaining("unavailable");
        assertThat(snapshot.tripEvents()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_trip_roster_entries WHERE transport_trip_id=?",Integer.class,trip.tripId())).isEqualTo(1);
        authorityTransport.changeTripState(trip.operator(),trip.tripId(),new com.educore.api.transport.TransportController.TripStateRequest(
                p.schoolId(),"OPEN_BOARDING",0,"Synthetic explicit operator action"));
        assertThatThrownBy(() -> service.change(f.family().guardian(),p.learnerId(),nonTravelRequest(f,"PICKUP",false,first.id(),null)))
                .hasMessageContaining("Changes are closed");
        assertThat(service.change(f.family().guardian(),p.learnerId(),request)).isEqualTo(first);
        assertThat(service.familyDay(f.family().guardian(),p.learnerId()).journeys().stream().filter(j -> "PICKUP".equals(j.journeyKind())))
                .allMatch(j -> !j.changesAllowed());
        assertThat(authorityTransport.snapshot(trip.operator(),p.schoolId()).tripEvents()).isEmpty();
        authorityTransport.recordTripEvent(trip.operator(),new com.educore.api.transport.TransportController.TripEventRequest(p.schoolId(),
                UUID.randomUUID(),trip.tripId(),roster.id(),"ABSENT",null,"Synthetic explicit absence verification"));
        assertThat(authorityTransport.snapshot(trip.operator(),p.schoolId()).tripEvents()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_trip_roster_entries WHERE transport_trip_id=?",Integer.class,trip.tripId())).isEqualTo(1);
    }

    private NonTravelTripFixture insertNonTravelTripFixture(NonTravelFixture f) {
        var p=f.family().portal(); var operators=new java.util.ArrayList<AuthenticatedUser>(); var profiles=new java.util.ArrayList<UUID>();
        for(int index=0;index<2;index++) {
            UUID user=UUID.randomUUID(),member=UUID.randomUUID(),profile=UUID.randomUUID();
            insertSyntheticUser(user,"synthetic-non-travel-operator-"+user+"@example.test");
            insertSyntheticMembership(member,user,p.organisationId(),p.schoolId());
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",member,UUID.fromString("00000000-0000-4000-8000-000000000011"));
            jdbc.update("""
                    INSERT INTO staff_profiles(id,organisation_id,school_id,employee_code,legal_name,display_name,user_account_id,joined_on,
                      status,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(?,?,?,?,'Synthetic transport operator','Synthetic transport operator',?,CURRENT_DATE-30,'ACTIVE',?,now(),now(),0)
                    """,profile,p.organisationId(),p.schoolId(),"SYN-NT-"+index,user,p.staffMembershipId());
            profiles.add(profile); operators.add(new AuthenticatedUser(user,UUID.randomUUID(),"synthetic-operator@example.test",null,
                    "Synthetic operator",true,p.organisationId(),Set.of(member),Set.of("transport.read","transport.operate"),List.of()));
        }
        var vehicle=authorityTransport.createVehicle(f.family().admin(),new com.educore.api.transport.TransportController.VehicleRequest(
                p.schoolId(),f.campus(),"SYN_NON_TRAVEL","Synthetic test vehicle","SYN-TEST-ONLY","SCHOOL",null,20,false,null,false,
                "ACTIVE",null,"Synthetic fixture; not readiness evidence")).id();
        UUID revision=jdbc.queryForObject("SELECT transport_route_revision_id FROM learner_transport_assignments WHERE id=?",UUID.class,f.pickup());
        UUID duty=UUID.randomUUID(),trip=UUID.randomUUID();
        // Valid database fixture for roster/authorization/state tests, not vehicle/driver readiness acceptance.
        jdbc.update("""
                INSERT INTO transport_duty_assignments(id,organisation_id,school_id,transport_route_revision_id,transport_vehicle_id,
                  driver_staff_profile_id,attendant_staff_profile_id,starts_on,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,now(),now(),0)
                """,duty,p.organisationId(),p.schoolId(),revision,vehicle,profiles.getFirst(),uuid("ac000000",11),f.day(),p.staffMembershipId());
        jdbc.update("""
                INSERT INTO transport_trips(id,organisation_id,school_id,transport_route_revision_id,transport_duty_assignment_id,service_date,
                  journey_kind,route_code_snapshot,route_name_snapshot,service_label_snapshot,vehicle_label_snapshot,vehicle_registration_snapshot,
                  driver_name_snapshot,attendant_name_snapshot,status,created_by_membership_id,updated_by_membership_id,created_at,updated_at,row_version)
                VALUES(?,?,?,?,?,?,'PICKUP','SYN_NON_TRAVEL','Synthetic route','Synthetic pickup','Synthetic vehicle','SYN-TEST-ONLY',
                  'Synthetic operator','Synthetic attendant','PLANNED',?,?,now(),now(),0)
                """,trip,p.organisationId(),p.schoolId(),revision,duty,f.day(),p.staffMembershipId(),p.staffMembershipId());
        jdbc.update("""
                INSERT INTO transport_trip_roster_entries(id,organisation_id,school_id,transport_trip_id,learner_id,learner_transport_assignment_id,
                  transport_route_stop_id,learner_name_snapshot,learner_reference_snapshot,stop_sequence_snapshot,stop_family_label_snapshot,
                  scheduled_time_snapshot,created_at)
                SELECT ?,assignment.organisation_id,assignment.school_id,?,assignment.learner_id,assignment.id,assignment.transport_route_stop_id,
                  'Synthetic learner','SYN-TEST-ONLY',stop.sequence_number,stop.family_label_snapshot,stop.scheduled_time,now()
                FROM learner_transport_assignments assignment JOIN transport_route_revision_stops stop ON stop.id=assignment.transport_route_stop_id
                WHERE assignment.id=?
                """,UUID.randomUUID(),trip,f.pickup());
        return new NonTravelTripFixture(trip,operators.getFirst(),operators.getLast());
    }
    private record NonTravelTripFixture(UUID tripId,AuthenticatedUser operator,AuthenticatedUser unassigned) {}

    private NonTravelFixture insertNonTravelFixture() {
        var f=insertPtmFixture(); var p=f.portal(); UUID campus=UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID reviewerUser=UUID.randomUUID(),reviewerMember=UUID.randomUUID();
        insertSyntheticUser(reviewerUser,"synthetic-transport-reviewer-"+reviewerUser+"@example.test");
        insertSyntheticMembership(reviewerMember,reviewerUser,p.organisationId(),p.schoolId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",reviewerMember,UUID.fromString("00000000-0000-4000-8000-000000000001"));
        var reviewer=new AuthenticatedUser(reviewerUser,UUID.randomUUID(),"synthetic-reviewer@example.test",null,"Synthetic reviewer",
                true,p.organisationId(),Set.of(reviewerMember),Set.of("transport.publish"),List.of());
        var day=java.time.Instant.now().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        var route=authorityTransport.createRoute(f.admin(),new com.educore.api.transport.TransportController.RouteRequest(p.schoolId(),campus,
                "SYN_NON_TRAVEL","Synthetic parent transport",null,"ACTIVE",null,"Synthetic transport setup")).id();
        UUID firstStop=UUID.randomUUID(),lastStop=UUID.randomUUID();
        insertTransportStop(firstStop,p.organisationId(),p.schoolId(),campus,p.staffMembershipId(),"SYN_NON_TRAVEL_FIRST");
        insertTransportStop(lastStop,p.organisationId(),p.schoolId(),campus,p.staffMembershipId(),"SYN_NON_TRAVEL_LAST");
        UUID pickup=null,drop=null;
        int version=0;
        for(String kind:List.of("PICKUP","DROP")) {
            var revision=authorityTransport.createRouteRevision(f.admin(),new com.educore.api.transport.TransportController.RouteRevisionRequest(
                    p.schoolId(),route,++version,null,kind,"Synthetic "+kind,"MON,TUE,WED,THU,FRI,SAT,SUN",day,null,null,"Synthetic route")).id();
            LocalTime start=LocalTime.of("PICKUP".equals(kind)?9:17,0);
            authorityTransport.createRouteStop(f.admin(),new com.educore.api.transport.TransportController.RouteStopRequest(p.schoolId(),revision,
                    firstStop,1,start,5,null,"Synthetic first stop"));
            var assignedStop=authorityTransport.createRouteStop(f.admin(),new com.educore.api.transport.TransportController.RouteStopRequest(p.schoolId(),revision,
                    lastStop,2,start.plusMinutes(10),5,null,"Synthetic last stop")).id();
            authorityTransport.submitRoute(f.admin(),revision,new com.educore.api.transport.TransportController.VersionedReason(p.schoolId(),0,"Synthetic route review"));
            authorityTransport.decideRoute(reviewer,revision,new com.educore.api.transport.TransportController.DecisionRequest(p.schoolId(),"PUBLISHED",1,"Synthetic independent approval"));
            UUID assignment=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO learner_transport_assignments(id,organisation_id,school_id,learner_id,transport_route_revision_id,
                      transport_route_stop_id,journey_kind,starts_on,status,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,now(),now(),0)
                    """,assignment,p.organisationId(),p.schoolId(),p.learnerId(),revision,assignedStop,kind,day,p.staffMembershipId());
            if("PICKUP".equals(kind)) pickup=assignment; else drop=assignment;
        }
        return new NonTravelFixture(f,campus,day,pickup,drop);
    }
    private com.educore.api.transport.TransportNonTravelService nonTravelAt(NonTravelFixture f,int hour,int minute) {
        var clock=java.time.Clock.fixed(f.day().atTime(hour,minute).atZone(ZoneId.of("Asia/Kolkata")).toInstant(),java.time.ZoneOffset.UTC);
        return new com.educore.api.transport.TransportNonTravelService(authorityTransport,familyTransport,
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),nonTravelAudit,clock);
    }
    private com.educore.api.transport.TransportNonTravelModels.Policy enableNonTravel(NonTravelFixture f,
            com.educore.api.transport.TransportNonTravelService service,LocalTime pickup,LocalTime drop,Long version) {
        return service.savePolicy(f.family().admin(),new com.educore.api.transport.TransportNonTravelModels.PolicyRequest(
                f.family().portal().schoolId(),f.campus(),true,pickup,drop,version,"Synthetic school-local policy"));
    }
    private com.educore.api.transport.TransportNonTravelModels.ChangeRequest nonTravelRequest(NonTravelFixture f,String kind,
            boolean notTravelling,UUID previousPickup,UUID previousDrop) {
        return new com.educore.api.transport.TransportNonTravelModels.ChangeRequest(UUID.randomUUID(),f.day(),kind,notTravelling,
                "DROP".equals(kind)?null:f.pickup(),"PICKUP".equals(kind)?null:f.drop(),previousPickup,previousDrop);
    }
    private record NonTravelFixture(PtmFixture family,UUID campus,LocalDate day,UUID pickup,UUID drop) {}

    @Test @Transactional
    void familyTransportRequiresLiveAccountSchoolOrganisationAndCampus() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=insertPtmFixture(); var p=f.portal();
            assertThat(familyTransport.transport(f.guardian(),p.learnerId()).learnerId()).isEqualTo(p.learnerId());
            for(var target:java.util.Map.of("schools",p.schoolId(),"organisations",p.organisationId(),
                    "user_accounts",p.guardianUserId(),"campuses",UUID.fromString("10000000-0000-4000-8000-000000000003")).entrySet()) {
                String status="campuses".equals(target.getKey())?"INACTIVE":"SUSPENDED";
                jdbc.update("UPDATE "+target.getKey()+" SET status=? WHERE id=?",status,target.getValue());
                assertThatThrownBy(()->familyTransport.transport(f.guardian(),p.learnerId()))
                        .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("verified relationship");
                jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
            }
        }
    }

    @Test @Transactional
    void familyTransportRejectsEmptyWrongAndExpiredMembershipWithoutLeakingRecords() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=insertPtmFixture(); var p=f.portal(); var guardian=f.guardian();
            for(Set<UUID> ids:List.of(Set.<UUID>of(),Set.of(UUID.randomUUID()))) {
                var actor=new AuthenticatedUser(guardian.userId(),guardian.sessionId(),guardian.loginIdentifier(),guardian.email(),
                        guardian.displayName(),guardian.mfaVerified(),guardian.activeOrganisationId(),ids,guardian.capabilities(),guardian.organisations());
                assertThatThrownBy(()->familyTransport.transport(actor,p.learnerId()))
                        .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("verified relationship");
            }
            assertThatThrownBy(()->familyTransport.transport(guardian,UUID.randomUUID())).hasMessageContaining("verified relationship");
            assertThatThrownBy(()->familyTransport.transport(inboxScopeUser(guardian,UUID.randomUUID(),guardian.membershipIds()),p.learnerId()))
                    .hasMessageContaining("verified relationship");
            assertThatThrownBy(()->familyTransport.transport(inboxScopeUser(guardian,p.organisationId(),f.admin().membershipIds()),p.learnerId()))
                    .hasMessageContaining("verified relationship");
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",p.guardianMembershipId());
            assertThatThrownBy(()->familyTransport.transport(guardian,p.learnerId())).hasMessageContaining("verified relationship");
            jdbc.update("UPDATE memberships SET valid_from=now()+interval '1 day',valid_until=NULL WHERE id=?",p.guardianMembershipId());
            assertThatThrownBy(()->familyTransport.transport(guardian,p.learnerId())).hasMessageContaining("verified relationship");
        }
    }

    @Test @Transactional
    void transportAuthorityRequiresLiveAccountSchoolOrganisationAndOwnedMembership() {
        var f=insertPtmFixture(); var p=f.portal();
        assertThat(authorityTransport.snapshot(f.admin(),p.schoolId()).schoolId()).isEqualTo(p.schoolId());
        for(var target:java.util.Map.of("schools",p.schoolId(),"organisations",p.organisationId(),
                "user_accounts",p.staffUserId()).entrySet()) {
            jdbc.update("UPDATE "+target.getKey()+" SET status='SUSPENDED' WHERE id=?",target.getValue());
            assertThatThrownBy(()->authorityTransport.snapshot(f.admin(),p.schoolId()))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("unavailable");
            assertThatThrownBy(()->authorityTransport.createRoute(f.admin(),new com.educore.api.transport.TransportController.RouteRequest(
                    p.schoolId(),UUID.fromString("10000000-0000-4000-8000-000000000003"),"SYN_SCOPE_DENIED",
                    "Synthetic denied route",null,"ACTIVE",null,"Synthetic denied mutation")))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("unavailable");
            jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
        }
        for(Set<UUID> ids:List.of(Set.<UUID>of(),Set.of(UUID.randomUUID()),f.guardian().membershipIds())) {
            assertThatThrownBy(()->authorityTransport.snapshot(inboxScopeUser(f.admin(),p.organisationId(),ids),p.schoolId()))
                    .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("unavailable");
        }
        assertThatThrownBy(()->authorityTransport.snapshot(f.admin(),UUID.randomUUID())).hasMessageContaining("unavailable");
        assertThatThrownBy(()->authorityTransport.snapshot(inboxScopeUser(f.admin(),UUID.randomUUID(),f.admin().membershipIds()),p.schoolId()))
                .hasMessageContaining("unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_routes WHERE code='SYN_SCOPE_DENIED'",Integer.class)).isZero();
        jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",p.staffMembershipId());
        assertThatThrownBy(()->authorityTransport.snapshot(f.admin(),p.schoolId())).hasMessageContaining("unavailable");
        jdbc.update("UPDATE memberships SET valid_from=now()+interval '1 day',valid_until=NULL WHERE id=?",p.staffMembershipId());
        assertThatThrownBy(()->authorityTransport.snapshot(f.admin(),p.schoolId())).hasMessageContaining("unavailable");
    }

    @Test @Transactional
    void transportRejectsForeignRoleReadAndPrivilegeFlagsWithoutChangingValidLocalAccess() {
        var f=insertPtmFixture(); var p=f.portal();
        UUID otherOrg=UUID.randomUUID(),foreignRole=UUID.randomUUID(),readRole=UUID.randomUUID();
        jdbc.update("INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at) VALUES(?,?,'Synthetic transport organisation','Synthetic transport organisation',now(),now())",
                otherOrg,"synthetic-transport-"+otherOrg);
        jdbc.update("""
                INSERT INTO roles(id,organisation_id,code,display_name,created_at,updated_at)
                VALUES(?,?,'SYN_TRANSPORT_FOREIGN','Synthetic foreign transport authority',now(),now()),
                      (?,?,'SYN_TRANSPORT_LOCAL','Synthetic local transport reader',now(),now())
                """,foreignRole,otherOrg,readRole,p.organisationId());
        for(String capability:List.of("transport.read","transport.configure","transport.operate","transport.safety","transport.publish","portal.guardian"))
            jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,?)",foreignRole,capability);
        jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,'transport.read')",readRole);
        jdbc.update("DELETE FROM membership_roles WHERE membership_id IN(?,?)",p.staffMembershipId(),p.guardianMembershipId());
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?),(?,?),(?,?)",
                p.staffMembershipId(),foreignRole,p.staffMembershipId(),readRole,p.guardianMembershipId(),foreignRole);
        var reader=authorityTransport.snapshot(f.admin(),p.schoolId());
        assertThat(reader.canConfigure()).isFalse(); assertThat(reader.canOperate()).isFalse();
        assertThat(reader.canReviewSafety()).isFalse(); assertThat(reader.canPublish()).isFalse();
        assertThat(reader.learners()).isEmpty(); assertThat(reader.staff()).isEmpty();
        assertThatThrownBy(()->authorityTransport.createRoute(f.admin(),new com.educore.api.transport.TransportController.RouteRequest(
                p.schoolId(),UUID.fromString("10000000-0000-4000-8000-000000000003"),"SYN_FOREIGN_DENIED",
                "Synthetic denied route",null,"ACTIVE",null,"Synthetic denied mutation")))
                .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("unavailable");
        assertThatThrownBy(()->familyTransport.transport(f.guardian(),p.learnerId()))
                .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("verified relationship");
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=? AND role_id=?",p.staffMembershipId(),readRole);
        assertThatThrownBy(()->authorityTransport.snapshot(f.admin(),p.schoolId()))
                .isInstanceOf(com.educore.api.common.api.ApiException.class).hasMessageContaining("unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transport_routes WHERE code='SYN_FOREIGN_DENIED'",Integer.class)).isZero();
    }

    private LeaveNoticeFixture readyLeaveNotification(boolean device) {
        var f=studentLeaveFixture(); var p=f.portal();
        if(device) pushNotifications.register(f.guardian(),new RegisterPushSubscriptionRequest(p.schoolId(),
                "https://push.example.org/leave/synthetic",syntheticP256dh(),syntheticAuth(),"Synthetic leave device"));
        var request=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-leave-notice"));
        studentLeave.decide(f.admin(),request.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                p.schoolId(),"APPROVED",0,"PRIVATE SYNTHETIC decision"));
        return new LeaveNoticeFixture(f,request.id(),studentLeaveEvent(request.id(),"APPROVED"));
    }
    private record LeaveNoticeFixture(PtmFixture fixture,UUID requestId,UUID eventId) {}

    @Test @Transactional
    void leaveDecisionsReachExistingInboxAndPushQueueWithDeliveryTimeSourceChecks() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var fixture=readyLeaveNotification(true); var f=fixture.fixture(); var p=f.portal();
            assertThat(leaveNotifications.processNext(p.organisationId())).isEqualTo(fixture.eventId());
            assertThat(leaveNotifications.processNext(p.organisationId())).isNull();
            var inbox=ptmInbox.familyInbox(f.guardian(),p.learnerId());
            assertThat(inbox.items()).hasSize(1);
            UUID notice=inbox.items().getFirst().announcementId();
            assertThat(inbox.items().getFirst().bodyEn()).contains("Student leave").doesNotContain("PRIVATE SYNTHETIC","private family reason");
            assertThat(inbox.items().getFirst().bodyHi()).isNotBlank();
            assertThat(leavePublications.publish(p.organisationId(),p.schoolId(),fixture.eventId())).isEqualTo(notice);
            assertThat(jdbc.queryForMap("SELECT authority_kind,created_by_membership_id,decided_by_membership_id FROM communication_announcements WHERE id=?",notice))
                    .containsEntry("authority_kind","DOMAIN_EVENT").containsEntry("created_by_membership_id",null).containsEntry("decided_by_membership_id",null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcement_history WHERE communication_announcement_id=? AND actor_kind='SYSTEM'",Integer.class,notice)).isEqualTo(1);
            jdbc.update("""
                    INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,
                      credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at)
                    VALUES(?,?,?,'PUSH','LIVE','WEB_PUSH','env:APP_PUSH_DELIVERY_PRIVATE_KEY','ACTIVE',?,now(),now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),p.staffMembershipId());
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc); var clock=java.time.Clock.systemUTC();
            var dispatch=new com.educore.api.communication.PushDeliveryDispatch(named,clock);
            assertThat(dispatch.materializeNext(p.organisationId(),p.schoolId())).isNotNull();
            var queue=new PushDeliveryQueue(named,clock); var claim=queue.claimNext(p.organisationId(),p.schoolId());
            var preparation=new com.educore.api.communication.PushDeliveryPreparation(named,pushSubscriptionProtector,clock,
                    publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
            assertThat(preparation.prepare(claim).action()).isEqualTo(com.educore.api.communication.PushDeliveryPreparation.Action.SEND);
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=false WHERE school_id=? AND user_account_id=?",p.schoolId(),p.guardianUserId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_CONSENT_WITHDRAWN");
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=true WHERE school_id=? AND user_account_id=?",p.schoolId(),p.guardianUserId());
            studentLeave.withdraw(f.guardian(),p.learnerId(),fixture.requestId(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(1,"Synthetic parent withdrawal"));
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_attempts WHERE school_id=?",Integer.class,p.schoolId())).isZero();
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void appliesV054AndGuardsLeavePublicationRecipientsHistoryAndConsent() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='054'",Boolean.class)).isTrue();
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var fixture=readyLeaveNotification(false); var f=fixture.fixture(); var p=f.portal();
            assertThat(leavePublications.publish(p.organisationId(),UUID.randomUUID(),fixture.eventId())).isNull();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(()->leaveNotifications.processNext(p.organisationId())).hasMessageContaining("organisation context");
                assertThatThrownBy(()->leavePublications.publish(p.organisationId(),p.schoolId(),fixture.eventId())).hasMessageContaining("organisation context");
            }
            UUID notice=leavePublications.publish(p.organisationId(),p.schoolId(),fixture.eventId());
            assertThat(jdbc.queryForObject("SELECT d.status FROM communication_deliveries d JOIN communication_recipients r ON r.id=d.communication_recipient_id WHERE r.communication_announcement_id=? AND d.channel='PUSH'",String.class,notice)).isEqualTo("SUPPRESSED");
            assertDatabaseRejected(()->jdbc.update("UPDATE communication_announcements SET body_en='forged' WHERE id=?",notice),"immutable");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                      guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                    VALUES(?,?,?,?,'GUARDIAN',?,?,?,'EN',true,now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,UUID.randomUUID(),p.learnerId(),p.guardianUserId()),"current requesting guardian");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_announcement_history(id,organisation_id,school_id,communication_announcement_id,
                      event_type,new_status,reason,actor_membership_id,occurred_at)
                    VALUES(?,?,?,?,'APPROVED','APPROVED','Synthetic fabricated approver',?,now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,p.staffMembershipId()),"actor must match");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,audience_reference_id,
                      language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,status,created_at,updated_at,
                      authority_kind,source_type,source_id,domain_event_id,student_leave_request_id,student_leave_history_id)
                    SELECT gen_random_uuid(),organisation_id,school_id,category,priority,audience_kind,audience_reference_id,
                      language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,status,created_at,updated_at,
                      authority_kind,source_type,source_id,domain_event_id,student_leave_request_id,gen_random_uuid()
                    FROM communication_announcements WHERE id=?
                    """,notice),"current scoped decision evidence");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void obsoleteOrUnavailableLeaveDecisionsNeverCreateNewFamilyPublications() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var fixture=readyLeaveNotification(false); var f=fixture.fixture(); var p=f.portal();
            studentLeave.decide(f.admin(),fixture.requestId(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"REVOKED",1,"Synthetic replacement decision"));
            var processed=new java.util.HashSet<UUID>();
            processed.add(leaveNotifications.processNext(p.organisationId())); processed.add(leaveNotifications.processNext(p.organisationId()));
            assertThat(processed).containsExactlyInAnyOrder(fixture.eventId(),studentLeaveEvent(fixture.requestId(),"REVOKED"));
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE id=?",String.class,fixture.eventId())).isEqualTo("LEAVE_SOURCE_UNAVAILABLE");
            assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).items()).hasSize(1);
            var second=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-new-notice"));
            studentLeave.decide(f.admin(),second.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"REJECTED",0,"Synthetic new decision"));
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=?",p.learnerId());
            assertThat(leaveNotifications.processNext(p.organisationId())).isEqualTo(studentLeaveEvent(second.id(),"REJECTED"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE source_type='STUDENT_LEAVE_UPDATE' AND school_id=?",Integer.class,p.schoolId())).isEqualTo(1);
            assertThat(leaveNotifications.processNext(p.organisationId())).isNull();
        }
    }

    @Test @Transactional
    void leaveNotificationFailuresAreBoundedAndNeverPretendToBeDelivered() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var fixture=readyLeaveNotification(false); var p=fixture.fixture().portal();
            var publications=org.mockito.Mockito.mock(com.educore.api.communication.StudentLeavePublicationService.class);
            org.mockito.Mockito.when(publications.publish(p.organisationId(),p.schoolId(),fixture.eventId())).thenThrow(new IllegalStateException("PRIVATE SYNTHETIC detail"));
            var clock=new PushQueueClock(); var processor=new com.educore.api.studentleave.StudentLeaveNotificationProcessor(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),publications,leaveSubmissionPublications,clock);
            for(int attempt=1;attempt<=8;attempt++) {
                assertThatThrownBy(()->processor.processNext(p.organisationId()))
                        .isInstanceOf(com.educore.api.studentleave.StudentLeaveNotificationProcessor.PublicationException.class)
                        .hasMessage("Student leave notification publication failed").hasNoCause();
                processor.recordFailure(p.organisationId(),fixture.eventId());
                assertThat(jdbc.queryForMap("SELECT attempt_count,processed_at,last_error_code FROM outbox_events WHERE id=?",fixture.eventId()))
                        .containsEntry("attempt_count",attempt).containsEntry("processed_at",null).containsEntry("last_error_code","LEAVE_NOTIFICATION_FAILED");
                assertThat(processor.processNext(p.organisationId())).isNull(); clock.advance(7200);
            }
            assertThat(processor.processNext(p.organisationId())).isNull();
            org.mockito.Mockito.verify(publications,org.mockito.Mockito.times(8)).publish(p.organisationId(),p.schoolId(),fixture.eventId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE school_id=?",Integer.class,p.schoolId())).isZero();
            var expiredClock=java.time.Clock.fixed(java.time.Instant.now().plusSeconds(8*86400),java.time.ZoneOffset.UTC);
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var expired=new com.educore.api.communication.StudentLeavePublicationService(named,
                    new com.educore.api.studentleave.StudentLeaveNotificationSource(named,expiredClock),domainFamilyDelivery,expiredClock);
            assertThat(expired.publish(p.organisationId(),p.schoolId(),fixture.eventId())).isNull();
        }
    }

    private UUID studentLeaveEvent(UUID requestId,String status) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='STUDENT_LEAVE_REQUEST' AND aggregate_id=? AND payload->>'status'=?",
                UUID.class,requestId.toString(),status);
    }

    @Test @Transactional
    void leaveSubmissionsReachIndependentStaffInboxAndPreserveFamilyDecisionProcessing() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal();
            var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-reviewer-publication"));
            UUID event=studentLeaveEvent(created.id(),"SUBMITTED");
            assertThat(leaveNotifications.processNext(p.organisationId())).isNull();
            assertThat(leaveNotifications.processNextSubmission(p.organisationId())).isEqualTo(event);
            assertThat(leaveNotifications.processNextSubmission(p.organisationId())).isNull();
            var inbox=ptmInbox.staffInbox(f.admin(),p.schoolId());
            assertThat(inbox.items()).hasSize(1);
            var item=inbox.items().getFirst();
            assertThat(item.bodyEn()).contains("Student leave").doesNotContain("Synthetic private family reason","Synthetic portal learner");
            assertThat(item.bodyHi()).isNotBlank(); assertThat(item.learnerId()).isNull();
            assertThat(leaveSubmissionPublications.publish(p.organisationId(),p.schoolId(),event)).isEqualTo(item.announcementId());
            assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).items()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcement_history WHERE communication_announcement_id=? AND actor_kind='SYSTEM'",Integer.class,item.announcementId())).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT processed_at IS NOT NULL done,attempt_count,last_error_code FROM outbox_events WHERE id=?",event))
                    .containsEntry("done",true).containsEntry("attempt_count",1).containsEntry("last_error_code",null);
            assertThat(ptmInbox.readStaff(f.admin(),item.recipientId(),p.schoolId()).version()).isEqualTo(1);
            assertThat(ptmInbox.readStaff(f.admin(),item.recipientId(),p.schoolId()).version()).isEqualTo(1);
            studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic independent school decision"));
            assertThat(ptmInbox.staffInbox(f.admin(),p.schoolId()).items()).isEmpty();
            assertThatThrownBy(()->ptmInbox.readStaff(f.admin(),item.recipientId(),p.schoolId())).hasMessageContaining("unavailable");
            assertThat(leaveNotifications.processNext(p.organisationId())).isEqualTo(studentLeaveEvent(created.id(),"APPROVED"));
            assertThat(ptmInbox.familyInbox(f.guardian(),p.learnerId()).items()).hasSize(1);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void reviewerInboxUsesAllLiveOwnedMembershipsWithoutBorrowingAuthority() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal();
            UUID readMembership=UUID.randomUUID(),readRole=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                    VALUES(?,?,?,?,?,'ACTIVE',now()-interval '2 days',now()-interval '2 days',now())
                    """,readMembership,p.staffUserId(),p.organisationId(),p.schoolId(),UUID.fromString("10000000-0000-4000-8000-000000000003"));
            jdbc.update("INSERT INTO roles(id,organisation_id,code,display_name,created_at,updated_at) VALUES(?,?,'SYNTHETIC_INBOX_ONLY','Synthetic inbox-only authority',now(),now())",readRole,p.organisationId());
            jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,'communication.read')",readRole);
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",readMembership,readRole);
            var actor=new AuthenticatedUser(p.staffUserId(),UUID.randomUUID(),"synthetic-reviewer",null,"Synthetic reviewer",true,
                    p.organisationId(),Set.of(readMembership,p.staffMembershipId()),Set.of("communication.read","student_leave.manage"),List.of());
            var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-multiple-memberships"));
            UUID event=studentLeaveEvent(created.id(),"SUBMITTED");
            UUID notice=leaveSubmissionPublications.publish(p.organisationId(),p.schoolId(),event);
            assertThat(jdbc.queryForObject("SELECT membership_id FROM communication_recipients WHERE communication_announcement_id=?",UUID.class,notice)).isEqualTo(p.staffMembershipId());
            var inbox=ptmInbox.staffInbox(actor,p.schoolId());
            assertThat(inbox.items()).hasSize(1);
            assertThat(ptmInbox.readStaff(actor,inbox.items().getFirst().recipientId(),p.schoolId()).version()).isEqualTo(1);
            jdbc.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",p.staffMembershipId());
            assertThat(ptmInbox.staffInbox(actor,p.schoolId()).items()).isEmpty();
            assertThatThrownBy(()->ptmInbox.readStaff(actor,inbox.items().getFirst().recipientId(),p.schoolId())).hasMessageContaining("unavailable");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    private UUID addSecondLeaveReviewerMembership(PortalFixture p) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                VALUES(?,?,?,?,?,'ACTIVE',now(),now()+interval '1 second',now())
                """,id,p.staffUserId(),p.organisationId(),p.schoolId(),UUID.fromString("10000000-0000-4000-8000-000000000003"));
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",id,UUID.fromString("00000000-0000-4000-8000-000000000001"));
        return id;
    }

    @Test @Transactional
    void appliesV059AndProtectsReviewerPublicationPrivacyRecipientsAndHistory() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='059'",Boolean.class)).isTrue();
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var secondMembership=addSecondLeaveReviewerMembership(p);
            var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-reviewer-guards"));
            UUID event=studentLeaveEvent(created.id(),"SUBMITTED");
            assertThat(leaveSubmissionPublications.publish(p.organisationId(),UUID.randomUUID(),event)).isNull();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(()->leaveSubmissionPublications.publish(p.organisationId(),p.schoolId(),event)).hasMessageContaining("organisation context");
            }
            UUID notice=leaveSubmissionPublications.publish(p.organisationId(),p.schoolId(),event);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_recipients WHERE communication_announcement_id=?",Integer.class,notice)).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT authority_kind,created_by_membership_id,decided_by_membership_id FROM communication_announcements WHERE id=?",notice))
                    .containsEntry("authority_kind","DOMAIN_EVENT").containsEntry("created_by_membership_id",null).containsEntry("decided_by_membership_id",null);
            assertDatabaseRejected(()->jdbc.update("UPDATE communication_announcements SET body_en='changed' WHERE id=?",notice),"immutable");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                      membership_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                    VALUES(?,?,?,?,'STAFF',?,?,'EN',true,now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,secondMembership,p.staffUserId()),"one recipient per person");
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)",p.guardianMembershipId(),UUID.fromString("00000000-0000-4000-8000-000000000001"));
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                      membership_id,user_account_id,preferred_language,contact_eligible,available_at,created_at)
                    VALUES(?,?,?,?,'STAFF',?,?,'EN',true,now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,p.guardianMembershipId(),p.guardianUserId()),"independent school authority");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                      language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,status,created_at,updated_at,
                      authority_kind,source_type,source_id,domain_event_id,student_leave_request_id,student_leave_history_id)
                    SELECT gen_random_uuid(),organisation_id,school_id,category,priority,audience_kind,
                      language_mode,title_en,title_hi,'Synthetic private reason must never appear here',body_hi,request_push,scheduled_for,
                      expires_at,status,created_at,updated_at,authority_kind,source_type,source_id,domain_event_id,
                      student_leave_request_id,student_leave_history_id FROM communication_announcements WHERE id=?
                    """,notice),"privacy-minimal generic content");
            assertDatabaseRejected(()->jdbc.update("""
                    INSERT INTO communication_announcement_history(id,organisation_id,school_id,communication_announcement_id,
                      event_type,new_status,reason,actor_membership_id,occurred_at)
                    VALUES(?,?,?,?,'APPROVED','APPROVED','Synthetic invented human approval',?,now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),notice,p.staffMembershipId()),"actor must match");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void leaveReviewerPushRechecksConsentSourceAndOriginalMembershipBeforeDecryption() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var secondMembership=addSecondLeaveReviewerMembership(p);
            pushNotifications.register(f.admin(),new RegisterPushSubscriptionRequest(p.schoolId(),
                    "https://push.example.org/leave/reviewer/synthetic",syntheticP256dh(),syntheticAuth(),"Synthetic reviewer device"));
            jdbc.update("""
                    INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,
                      credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at)
                    VALUES(?,?,?,'PUSH','LIVE','WEB_PUSH','env:APP_PUSH_DELIVERY_PRIVATE_KEY','ACTIVE',?,now(),now(),now())
                    """,UUID.randomUUID(),p.organisationId(),p.schoolId(),p.staffMembershipId());
            var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-reviewer-push"));
            UUID event=studentLeaveEvent(created.id(),"SUBMITTED");
            assertThat(leaveNotifications.processNextSubmission(p.organisationId())).isEqualTo(event);
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc); var clock=java.time.Clock.systemUTC();
            var dispatch=new com.educore.api.communication.PushDeliveryDispatch(named,clock);
            assertThat(dispatch.materializeNext(p.organisationId(),p.schoolId())).isNotNull();
            var queue=new PushDeliveryQueue(named,clock); var claim=queue.claimNext(p.organisationId(),p.schoolId());
            var protector=org.mockito.Mockito.spy(pushSubscriptionProtector);
            var preparation=new com.educore.api.communication.PushDeliveryPreparation(named,protector,clock,
                    publishedSchoolCalendars,ptmNotificationSource,requestNotificationSource,leaveNotificationSource,leaveSubmissionSource,learningNotificationSource);
            assertThat(preparation.prepare(claim).action()).isEqualTo(com.educore.api.communication.PushDeliveryPreparation.Action.SEND);
            org.mockito.Mockito.clearInvocations(protector);
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=false WHERE school_id=? AND user_account_id=?",p.schoolId(),p.staffUserId());
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_CONSENT_WITHDRAWN");
            org.mockito.Mockito.verifyNoInteractions(protector);
            jdbc.update("UPDATE communication_contact_preferences SET allow_push=true WHERE school_id=? AND user_account_id=?",p.schoolId(),p.staffUserId());
            jdbc.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",p.staffMembershipId());
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(),p.schoolId(),event,secondMembership,p.staffUserId())).isTrue();
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            org.mockito.Mockito.verifyNoInteractions(protector);
            jdbc.update("UPDATE memberships SET status='ACTIVE' WHERE id=?",p.staffMembershipId());
            studentLeave.withdraw(f.guardian(),p.learnerId(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(0,"Synthetic parent withdrawal before dispatch"));
            assertThat(preparation.prepare(claim).safeCode()).isEqualTo("PUSH_SOURCE_UNAVAILABLE");
            org.mockito.Mockito.verifyNoInteractions(protector);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_push_attempts WHERE school_id=?",Integer.class,p.schoolId())).isZero();
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    @Test @Transactional
    void leaveSubmissionRetriesAreBoundedAndUnavailableSourcesNeverPublish() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal();
            var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-reviewer-retry"));
            UUID event=studentLeaveEvent(created.id(),"SUBMITTED");
            jdbc.update("UPDATE memberships SET status='SUSPENDED' WHERE id=?",p.staffMembershipId());
            assertThatThrownBy(()->leaveSubmissionPublications.publish(p.organisationId(),p.schoolId(),event)).hasMessageContaining("no eligible independent reviewers");
            jdbc.update("UPDATE memberships SET status='ACTIVE' WHERE id=?",p.staffMembershipId());
            var publications=org.mockito.Mockito.mock(com.educore.api.communication.StudentLeaveSubmissionPublicationService.class);
            org.mockito.Mockito.when(publications.publish(p.organisationId(),p.schoolId(),event)).thenThrow(new IllegalStateException("PRIVATE SYNTHETIC detail"));
            var clock=new PushQueueClock(); var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var processor=new com.educore.api.studentleave.StudentLeaveNotificationProcessor(named,leavePublications,publications,clock);
            for(int attempt=1;attempt<=8;attempt++) {
                assertThatThrownBy(()->processor.processNextSubmission(p.organisationId()))
                        .isInstanceOf(com.educore.api.studentleave.StudentLeaveNotificationProcessor.PublicationException.class)
                        .hasMessage("Student leave notification publication failed").hasNoCause();
                processor.recordFailure(p.organisationId(),event);
                assertThat(jdbc.queryForMap("SELECT attempt_count,processed_at,last_error_code FROM outbox_events WHERE id=?",event))
                        .containsEntry("attempt_count",attempt).containsEntry("processed_at",null).containsEntry("last_error_code","LEAVE_NOTIFICATION_FAILED");
                assertThat(processor.processNextSubmission(p.organisationId())).isNull(); clock.advance(7200);
            }
            org.mockito.Mockito.verify(publications,org.mockito.Mockito.times(8)).publish(p.organisationId(),p.schoolId(),event);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE school_id=?",Integer.class,p.schoolId())).isZero();
            studentLeave.withdraw(f.guardian(),p.learnerId(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(0,"Synthetic obsolete submission"));
            assertThat(leaveSubmissionPublications.publish(p.organisationId(),p.schoolId(),event)).isNull();
            var next=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"synthetic-already-reviewed"));
            studentLeave.decide(f.admin(),next.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"REJECTED",0,"Synthetic decision before alert"));
            UUID obsolete=studentLeaveEvent(next.id(),"SUBMITTED");
            assertThat(leaveNotifications.processNextSubmission(p.organisationId())).isEqualTo(obsolete);
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE id=?",String.class,obsolete)).isEqualTo("LEAVE_SOURCE_UNAVAILABLE");
        }
    }

    @Test
    @Transactional
    void leaveSubmissionSourceRequiresCurrentPrivateEvidenceAndPreservesFamilyDecisions() {
        try (var tenant = TenantRequestContext.open(null, UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f = studentLeaveFixture(); var p = f.portal();
            var request = studentLeaveRequest(f, "synthetic-staff-alert-source");
            var created = studentLeave.submit(f.guardian(), p.learnerId(), request);
            UUID event = studentLeaveEvent(created.id(), "SUBMITTED");
            var source = leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event);
            assertThat(source).isNotNull();
            assertThat(source.requestId()).isEqualTo(created.id());
            assertThat(source.requestVersion()).isZero();
            assertThat(source.toString()).doesNotContain(request.reason(), request.leaveType(), "Synthetic Learner");
            assertThat(studentLeave.submit(f.guardian(), p.learnerId(), request).id()).isEqualTo(created.id());
            assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isEqualTo(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_type='STUDENT_LEAVE_REQUEST' AND aggregate_id=?",
                    Integer.class, created.id().toString())).isEqualTo(1);
            var before = new com.educore.api.studentleave.StudentLeaveSubmissionNotificationSource(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                    java.time.Clock.fixed(source.occurredAt().minusMillis(1), java.time.ZoneOffset.UTC));
            assertThat(before.current(p.organisationId(), p.schoolId(), event)).isNull();
            assertThat(before.reviewers(p.organisationId(), p.schoolId(), event)).isEmpty();
            var expired = new com.educore.api.studentleave.StudentLeaveSubmissionNotificationSource(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                    java.time.Clock.fixed(source.expiresAt(), java.time.ZoneOffset.UTC));
            assertThat(expired.current(p.organisationId(), p.schoolId(), event)).isNull();
            assertThat(expired.isCurrentReviewer(p.organisationId(), p.schoolId(), event, p.staffMembershipId(), p.staffUserId())).isFalse();
            assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).singleElement()
                    .satisfies(reviewer -> assertThat(reviewer.userId()).isEqualTo(p.staffUserId()));
            assertThat(leaveNotifications.processNext(p.organisationId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE school_id=?", Integer.class, p.schoolId())).isZero();
            studentLeave.decide(f.admin(), created.id(), new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(), "APPROVED", 0, "Synthetic independent review"));
            assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isNull();
            assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).isEmpty();
            assertThat(leaveNotificationSource.current(p.organisationId(), p.schoolId(), studentLeaveEvent(created.id(), "APPROVED"))).isNotNull();
        }
    }

    @Test
    @Transactional
    void leaveSubmissionReviewersAreLiveIndependentPeopleNotDuplicateOrForeignMemberships() {
        try (var tenant = TenantRequestContext.open(null, UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f = studentLeaveFixture(); var p = f.portal();
            var created = studentLeave.submit(f.guardian(), p.learnerId(), studentLeaveRequest(f, "synthetic-staff-alert-reviewers"));
            UUID event = studentLeaveEvent(created.id(), "SUBMITTED");
            UUID otherUser = UUID.randomUUID(), otherMembership = UUID.randomUUID(), duplicateMembership = UUID.randomUUID();
            UUID schoolAdminRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
            insertSyntheticLoginUser(otherUser, "synthetic.leave-alert-reviewer", null);
            insertSyntheticMembership(otherMembership, otherUser, p.organisationId(), p.schoolId());
            jdbc.update("""
                    INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                    VALUES(?,?,?,?,?,'ACTIVE',now(),now(),now())
                    """, duplicateMembership, p.staffUserId(), p.organisationId(), p.schoolId(),
                    UUID.fromString("10000000-0000-4000-8000-000000000003"));
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?),(?,?),(?,?)",
                    otherMembership, schoolAdminRole, duplicateMembership, schoolAdminRole, p.guardianMembershipId(), schoolAdminRole);
            assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event))
                    .extracting(com.educore.api.studentleave.StudentLeaveSubmissionNotificationSource.Reviewer::userId)
                    .containsExactlyInAnyOrder(p.staffUserId(), otherUser);
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, p.staffMembershipId(), p.staffUserId())).isTrue();
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, duplicateMembership, p.staffUserId())).isTrue();
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, otherMembership, p.staffUserId())).isFalse();
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, p.guardianMembershipId(), p.guardianUserId())).isFalse();
            jdbc.update("UPDATE user_accounts SET status='SUSPENDED' WHERE id=?", otherUser);
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, otherMembership, otherUser)).isFalse();
            jdbc.update("UPDATE user_accounts SET status='ACTIVE' WHERE id=?", otherUser);
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?", otherMembership);
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, otherMembership, otherUser)).isFalse();
            jdbc.update("UPDATE memberships SET valid_until=NULL,valid_from=now()+interval '1 day' WHERE id=?", otherMembership);
            assertThat(leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, otherMembership, otherUser)).isFalse();
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '1 day' WHERE id=?", otherMembership);
            UUID linkedGuardian = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,user_account_id,created_at,updated_at,row_version)
                    VALUES(?,?,?,'Synthetic related reviewer','synthetic-linked-reviewer@example.test','ACTIVE',?,now(),now(),0)
                    """, linkedGuardian, p.organisationId(), p.schoolId(), p.staffUserId());
            jdbc.update("""
                    INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,
                      primary_contact,pickup_authorized,portal_access_status,created_at,updated_at,row_version)
                    VALUES(?,?,?,?,'PARENT_GUARDIAN',false,false,'SUSPENDED',now(),now(),0)
                    """, p.learnerId(), linkedGuardian, p.organisationId(), p.schoolId());
            assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).singleElement()
                    .satisfies(reviewer -> assertThat(reviewer.userId()).isEqualTo(otherUser));
            UUID foreignOrganisation = UUID.randomUUID(), foreignRole = UUID.randomUUID();
            jdbc.update("INSERT INTO organisations(id,slug,legal_name,display_name,status,created_at,updated_at) VALUES(?,?,'Synthetic foreign alert scope','Synthetic foreign alert scope','ACTIVE',now(),now())",
                    foreignOrganisation, "synthetic-alert-" + foreignOrganisation);
            jdbc.update("INSERT INTO roles(id,organisation_id,code,display_name,created_at,updated_at) VALUES(?,?,'SYNTHETIC_ALERT','Synthetic foreign alert authority',now(),now())", foreignRole, foreignOrganisation);
            jdbc.update("INSERT INTO role_capabilities(role_id,capability_code) VALUES(?,'communication.read'),(?,'student_leave.manage')", foreignRole, foreignRole);
            jdbc.update("DELETE FROM membership_roles WHERE membership_id=?", otherMembership);
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(?,?)", otherMembership, foreignRole);
            assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).isEmpty();
            assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isNotNull();
        }
    }

    @Test
    @Transactional
    void leaveSubmissionSourceRejectsForgedScopeAndChangedFamilyAuthority() {
        try (var tenant = TenantRequestContext.open(null, UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f = studentLeaveFixture(); var p = f.portal();
            var created = studentLeave.submit(f.guardian(), p.learnerId(), studentLeaveRequest(f, "synthetic-staff-alert-invalid"));
            UUID event = studentLeaveEvent(created.id(), "SUBMITTED");
            assertThat(leaveSubmissionSource.current(p.organisationId(), UUID.randomUUID(), event)).isNull();
            assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), UUID.randomUUID())).isNull();
            try (var other = TenantRequestContext.open(null, UUID.randomUUID())) {
                assertThatThrownBy(() -> leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).hasMessageContaining("organisation context");
                assertThatThrownBy(() -> leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).hasMessageContaining("organisation context");
                assertThatThrownBy(() -> leaveSubmissionSource.isCurrentReviewer(p.organisationId(), p.schoolId(), event, p.staffMembershipId(), p.staffUserId())).hasMessageContaining("organisation context");
            }
            String original = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE id=?", String.class, event);
            for (String field : List.of("organisationId", "schoolId", "requestId", "historyId", "requestVersion", "status")) {
                jdbc.update("UPDATE outbox_events SET payload=jsonb_set(payload,ARRAY[?],to_jsonb(CAST(? AS text))) WHERE id=?", field, "invalid-synthetic-value", event);
                assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isNull();
                assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).isEmpty();
                jdbc.update("UPDATE outbox_events SET payload=CAST(? AS jsonb) WHERE id=?", original, event);
            }
            for (var target : java.util.Map.of("user_accounts", p.guardianUserId(), "schools", p.schoolId(), "organisations", p.organisationId()).entrySet()) {
                jdbc.update("UPDATE " + target.getKey() + " SET status='SUSPENDED' WHERE id=?", target.getValue());
                assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isNull();
                jdbc.update("UPDATE " + target.getKey() + " SET status='ACTIVE' WHERE id=?", target.getValue());
            }
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=? AND guardian_id=?", p.learnerId(), p.guardianId());
            assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isNull();
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE' WHERE learner_id=? AND guardian_id=?", p.learnerId(), p.guardianId());
            studentLeave.withdraw(f.guardian(), p.learnerId(), created.id(), new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(0, "Synthetic request withdrawn before alert"));
            assertThat(leaveSubmissionSource.current(p.organisationId(), p.schoolId(), event)).isNull();
            assertThat(leaveSubmissionSource.reviewers(p.organisationId(), p.schoolId(), event)).isEmpty();
        }
    }

    @Test
    @Transactional
    void appliesV058AndProtectsLeaveSubmissionViewsWithRealRowSecurity() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='058'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='student_leave_submission_notification_sources'", String.class))
                .containsExactlyInAnyOrder("event_id", "request_id", "history_id", "organisation_id", "school_id", "request_version", "occurred_at", "expires_at");
        try (var tenant = TenantRequestContext.open(null, UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f = studentLeaveFixture(); var p = f.portal();
            var created = studentLeave.submit(f.guardian(), p.learnerId(), studentLeaveRequest(f, "synthetic-staff-alert-rls"));
            UUID event = studentLeaveEvent(created.id(), "SUBMITTED");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("CREATE ROLE educore_leave_submission_rls_test NOLOGIN");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_leave_submission_rls_test");
            jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO educore_leave_submission_rls_test");
            jdbc.execute("SET LOCAL ROLE educore_leave_submission_rls_test");
            try {
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, p.organisationId().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_submission_notification_sources WHERE event_id=?", Integer.class, event)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_submission_notification_reviewers WHERE event_id=?", Integer.class, event)).isEqualTo(1);
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, UUID.randomUUID().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_submission_notification_sources WHERE event_id=?", Integer.class, event)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_submission_notification_reviewers WHERE event_id=?", Integer.class, event)).isZero();
            } finally { jdbc.execute("RESET ROLE"); }
        }
    }

    @Test @Transactional
    void studentLeaveNotificationSourceRequiresExactCurrentDecisionAndKeepsPrivateDetailsOut() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var submitted=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"leave-notification-source"));
            UUID submission=studentLeaveEvent(submitted.id(),"SUBMITTED");
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),submission)).isNull();
            studentLeave.decide(f.admin(),submitted.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"PRIVATE SYNTHETIC decision reason"));
            UUID approved=studentLeaveEvent(submitted.id(),"APPROVED");
            var source=leaveNotificationSource.current(p.organisationId(),p.schoolId(),approved);
            assertThat(source).isNotNull();
            assertThat(source.requestId()).isEqualTo(submitted.id());
            assertThat(source.guardianId()).isEqualTo(p.guardianId());
            assertThat(source.userId()).isEqualTo(p.guardianUserId());
            assertThat(source.learnerId()).isEqualTo(p.learnerId());
            assertThat(source.requestVersion()).isEqualTo(1);
            assertThat(source.status()).isEqualTo("APPROVED");
            assertThat(source.toString()).doesNotContain("PRIVATE SYNTHETIC","private family reason","PERSONAL");
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),approved)).isEqualTo(source);
            assertThat(source.expiresAt()).isEqualTo(source.occurredAt().plusSeconds(7*86400));
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var expired=new com.educore.api.studentleave.StudentLeaveNotificationSource(named,java.time.Clock.fixed(source.expiresAt(),java.time.ZoneOffset.UTC));
            assertThat(expired.current(p.organisationId(),p.schoolId(),approved)).isNull();
            var early=new com.educore.api.studentleave.StudentLeaveNotificationSource(named,java.time.Clock.fixed(source.occurredAt().minusSeconds(1),java.time.ZoneOffset.UTC));
            assertThat(early.current(p.organisationId(),p.schoolId(),approved)).isNull();
            studentLeave.decide(f.admin(),submitted.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"REVOKED",1,"Synthetic revoked decision"));
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),approved)).isNull();
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),studentLeaveEvent(submitted.id(),"REVOKED")).status()).isEqualTo("REVOKED");
            var rejected=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"leave-notification-rejected"));
            studentLeave.decide(f.admin(),rejected.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"REJECTED",0,"Synthetic rejected decision"));
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),studentLeaveEvent(rejected.id(),"REJECTED")).status()).isEqualTo("REJECTED");
            var withdrawn=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"leave-notification-withdrawn"));
            studentLeave.decide(f.admin(),withdrawn.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"APPROVED",0,"Synthetic independent decision"));
            UUID obsolete=studentLeaveEvent(withdrawn.id(),"APPROVED");
            studentLeave.withdraw(f.guardian(),p.learnerId(),withdrawn.id(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(1,"Synthetic parent withdrawal"));
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),obsolete)).isNull();
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),studentLeaveEvent(withdrawn.id(),"WITHDRAWN"))).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM communication_announcements WHERE organisation_id=? AND school_id=?",Integer.class,p.organisationId(),p.schoolId())).isZero();
        }
    }

    @Test @Transactional
    void studentLeaveNotificationSourceRejectsForgedEventsAndLostFamilyAuthority() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"leave-source-access"));
            studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"APPROVED",0,"Synthetic independent decision"));
            UUID event=studentLeaveEvent(created.id(),"APPROVED");
            assertThat(leaveNotificationSource.current(p.organisationId(),UUID.randomUUID(),event)).isNull();
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(()->leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).hasMessageContaining("organisation context");
            }
            String original=jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE id=?",String.class,event);
            for(String field:List.of("organisationId","schoolId","requestId","historyId","requestVersion","status")) {
                jdbc.update("UPDATE outbox_events SET payload=payload-? WHERE id=?",field,event);
                assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
                jdbc.update("UPDATE outbox_events SET payload=CAST(? AS jsonb) WHERE id=?",original,event);
                jdbc.update("UPDATE outbox_events SET payload=jsonb_set(payload,ARRAY[?],to_jsonb(CAST(? AS text))) WHERE id=?",field,"invalid-synthetic-value",event);
                assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
                jdbc.update("UPDATE outbox_events SET payload=CAST(? AS jsonb) WHERE id=?",original,event);
            }
            jdbc.update("UPDATE outbox_events SET occurred_at=occurred_at+interval '1 second' WHERE id=?",event);
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE outbox_events SET occurred_at=occurred_at-interval '1 second' WHERE id=?",event);
            for(var target:java.util.Map.of("user_accounts",p.guardianUserId(),"schools",p.schoolId(),"organisations",p.organisationId(),"guardians",p.guardianId(),"learners",p.learnerId()).entrySet()) {
                String disabled=Set.of("guardians","learners").contains(target.getKey())?"INACTIVE":"SUSPENDED";
                jdbc.update("UPDATE "+target.getKey()+" SET status=? WHERE id=?",disabled,target.getValue());
                assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
                jdbc.update("UPDATE "+target.getKey()+" SET status='ACTIVE' WHERE id=?",target.getValue());
            }
            UUID enrolment=jdbc.queryForObject("SELECT enrolment_id FROM student_leave_requests WHERE id=?",UUID.class,created.id());
            jdbc.update("UPDATE student_enrolments SET status='ENDED' WHERE id=?",enrolment);
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE student_enrolments SET status='ACTIVE' WHERE id=?",enrolment);
            jdbc.update("UPDATE student_enrolments SET ends_on=starts_on WHERE id=?",enrolment);
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE student_enrolments SET ends_on=NULL WHERE id=?",enrolment);
            UUID campus=jdbc.queryForObject("SELECT campus_id FROM student_leave_requests WHERE id=?",UUID.class,created.id());
            jdbc.update("UPDATE campuses SET status='INACTIVE' WHERE id=?",campus);
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE campuses SET status='ACTIVE' WHERE id=?",campus);
            jdbc.update("UPDATE academic_sessions SET status='CLOSED' WHERE id=?",f.request().academicSessionId());
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE academic_sessions SET status='ACTIVE' WHERE id=?",f.request().academicSessionId());
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=?",p.learnerId());
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='ACTIVE' WHERE learner_id=?",p.learnerId());
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",p.guardianMembershipId());
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE memberships SET valid_until=NULL WHERE id=?",p.guardianMembershipId());
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNotNull();
            jdbc.update("UPDATE memberships SET valid_from=now()+interval '1 day' WHERE id=?",p.guardianMembershipId());
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '2 days' WHERE id=?",p.guardianMembershipId());
            jdbc.update("DELETE FROM membership_roles WHERE membership_id=?",p.guardianMembershipId());
            assertThat(leaveNotificationSource.current(p.organisationId(),p.schoolId(),event)).isNull();
        }
    }

    @Test @Transactional
    void appliesV053AndEnforcesLeaveNotificationSourceRowSecurity() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='053'",Boolean.class)).isTrue();
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"leave-source-rls"));
            studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(p.schoolId(),"APPROVED",0,"Synthetic independent decision"));
            UUID event=studentLeaveEvent(created.id(),"APPROVED");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("CREATE ROLE educore_leave_source_rls_test NOLOGIN");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_leave_source_rls_test");
            jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO educore_leave_source_rls_test");
            jdbc.execute("SET LOCAL ROLE educore_leave_source_rls_test");
            try {
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_notification_sources WHERE event_id=?",Integer.class,event)).isEqualTo(1);
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_notification_sources WHERE event_id=?",Integer.class,event)).isZero();
            } finally { jdbc.execute("RESET ROLE"); }
        }
    }

    private PtmFixture studentLeaveFixture() {
        var f=insertPtmFixture(); var p=f.portal();
        attendanceService.createPolicy(f.admin(),new com.educore.api.attendance.AttendanceController.PolicyRequest(
                p.schoolId(),f.request().academicSessionId(),"Synthetic leave attendance","DAILY",
                List.of("PRESENT","ABSENT","EXCUSED"),null,
                java.util.Map.of("PRESENT",BigDecimal.ONE,"ABSENT",BigDecimal.ZERO,"EXCUSED",BigDecimal.ZERO),
                24,7,new BigDecimal("75"),"ACTIVE",null,null));
        studentLeave.savePolicy(f.admin(),new com.educore.api.studentleave.StudentLeaveModels.PolicyRequest(
                p.schoolId(),f.request().academicSessionId(),30,7,"EXCUSED",true,null,"Synthetic school leave policy"));
        return f;
    }

    private com.educore.api.studentleave.StudentLeaveModels.SubmitRequest studentLeaveRequest(PtmFixture f,String key) {
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        return new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(f.request().academicSessionId(),
                day,day.plusDays(2),"PERSONAL","Synthetic private family reason",key);
    }

    @Test @Transactional
    void studentLeavePreservesPrivateRequestsRetriesDecisionsAndImmutableHistory() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var q=studentLeaveRequest(f,"student-leave-workflow");
            var created=studentLeave.submit(f.guardian(),p.learnerId(),q);
            assertThat(studentLeave.submit(f.guardian(),p.learnerId(),q).id()).isEqualTo(created.id());
            assertThat(studentLeave.family(f.guardian(),p.learnerId(),0).records().total()).isEqualTo(1);
            assertThat(studentLeave.workspace(f.admin(),p.schoolId(),"SUBMITTED",0).records().requests()).hasSize(1);
            assertThatThrownBy(()->studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"student-leave-overlap")))
                    .hasMessageContaining("already covers");
            assertThatThrownBy(()->studentLeave.submit(f.guardian(),p.learnerId(),
                    new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(q.sessionId(),q.startsOn(),q.endsOn(),
                            q.leaveType(),"Different synthetic reason",q.idempotencyKey()))).hasMessageContaining("retry key");
            var approved=studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic independent approval"));
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(studentLeave.familyDetail(f.guardian(),p.learnerId(),created.id()).history()).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_marks WHERE learner_id=?",Integer.class,p.learnerId())).isZero();
            assertThatThrownBy(()->studentLeave.withdraw(f.guardian(),p.learnerId(),created.id(),
                    new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(0,"Synthetic stale withdrawal"))).hasMessageContaining("changed");
            studentLeave.withdraw(f.guardian(),p.learnerId(),created.id(),
                    new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(1,"Synthetic plans changed"));
            assertThat(studentLeave.familyDetail(f.guardian(),p.learnerId(),created.id()).history()).hasSize(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_date_claims WHERE request_id=?",Integer.class,created.id())).isZero();
            assertThat(studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"student-leave-replacement")).id()).isNotEqualTo(created.id());
            assertDatabaseRejected(()->jdbc.update("UPDATE student_leave_requests SET request_reason='Changed private text' WHERE id=?",created.id()),"controlled workflow");
            assertDatabaseRejected(()->jdbc.update("DELETE FROM student_leave_requests WHERE id=?",created.id()),"cannot be deleted");
            assertDatabaseRejected(()->jdbc.update("UPDATE student_leave_history SET reason='Changed evidence' WHERE request_id=?",created.id()),"append-only");
        }
    }

    @Test @Transactional
    void studentLeaveRequiresCurrentFamilyAuthorityAndIndependentDecisionMaker() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal();
            var created=studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"student-leave-guards"));
            assertThatThrownBy(()->studentLeave.family(f.guardian(),UUID.randomUUID(),0)).hasMessageContaining("unavailable");
            assertThatThrownBy(()->studentLeave.workspace(f.guardian(),p.schoolId(),null,0)).hasMessageContaining("unavailable");
            jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?,?)",p.guardianMembershipId(),
                    UUID.fromString("00000000-0000-4000-8000-000000000001"));
            assertThatThrownBy(()->studentLeave.decide(f.guardian(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic self approval denied"))).hasMessageContaining("Another authorized");
            jdbc.update("UPDATE learner_guardian_relationships SET portal_access_status='SUSPENDED' WHERE learner_id=?",p.learnerId());
            assertThatThrownBy(()->studentLeave.familyDetail(f.guardian(),p.learnerId(),created.id())).hasMessageContaining("unavailable");
            assertThatThrownBy(()->studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic revoked family"))).hasMessageContaining("access or enrolment has changed");
            jdbc.update("UPDATE memberships SET valid_from=now()-interval '1 day',valid_until=now()-interval '1 second' WHERE id=?",p.staffMembershipId());
            assertThatThrownBy(()->studentLeave.workspace(f.admin(),p.schoolId(),null,0)).hasMessageContaining("unavailable");
        }
    }

    @Test @Transactional
    void appliesV048AndV049AndIsolatesStudentLeaveUnderRealRowSecurity() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='048'",Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='049'",Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM roles r JOIN role_capabilities c ON c.role_id=r.id WHERE r.code='principal_director' AND r.organisation_id IS NULL AND c.capability_code='student_leave.manage'",Integer.class)).isEqualTo(1);
            var f=studentLeaveFixture(); var p=f.portal();
            studentLeave.submit(f.guardian(),p.learnerId(),studentLeaveRequest(f,"student-leave-rls"));
            try(var other=TenantRequestContext.open(null,UUID.randomUUID())) {
                assertThatThrownBy(()->studentLeave.family(f.guardian(),p.learnerId(),0)).hasMessageContaining("unavailable");
            }
            var tables=List.of("student_leave_policies","student_leave_requests","student_leave_date_claims","student_leave_history");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("CREATE ROLE educore_student_leave_rls NOLOGIN");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_student_leave_rls");
            for(String table:tables) {
                assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname=?",Boolean.class,table)).isTrue();
                jdbc.execute("GRANT SELECT,UPDATE ON "+table+" TO educore_student_leave_rls");
            }
            jdbc.execute("SET LOCAL ROLE educore_student_leave_rls");
            try {
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
                for(String table:tables) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isPositive();
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
                for(String table:tables) {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
                    assertThat(jdbc.update("UPDATE "+table+" SET school_id=school_id")).isZero();
                }
            } finally { jdbc.execute("RESET ROLE"); }
        }
    }

    @Test @Transactional
    void studentLeaveEnforcesSessionBoundsFrozenPolicyAndControlledRejection() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); var q=studentLeaveRequest(f,"student-leave-policy");
            assertThatThrownBy(()->studentLeave.workspace(f.admin(),UUID.randomUUID(),null,0)).hasMessageContaining("unavailable");
            assertThatThrownBy(()->studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                    q.sessionId(),q.startsOn(),q.startsOn().plusDays(40),q.leaveType(),q.reason(),"student-leave-too-long")))
                    .hasMessageContaining("configured limit");
            assertThatThrownBy(()->studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                    UUID.randomUUID(),q.startsOn(),q.endsOn(),q.leaveType(),q.reason(),"student-leave-wrong-session")))
                    .hasMessageContaining("active enrolment");
            var created=studentLeave.submit(f.guardian(),p.learnerId(),q);
            String snapshot=jdbc.queryForObject("SELECT policy_snapshot::text FROM student_leave_requests WHERE id=?",String.class,created.id());
            studentLeave.savePolicy(f.admin(),new com.educore.api.studentleave.StudentLeaveModels.PolicyRequest(
                    p.schoolId(),q.sessionId(),2,0,"ABSENT",true,0L,"Synthetic future policy revision"));
            assertThat(jdbc.queryForObject("SELECT policy_snapshot::text FROM student_leave_requests WHERE id=?",String.class,created.id())).isEqualTo(snapshot);
            assertDatabaseRejected(()->jdbc.update("INSERT INTO student_leave_date_claims SELECT * FROM student_leave_date_claims WHERE request_id=?",created.id()),"duplicate key");
            studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"REJECTED",0,"Synthetic independent rejection"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM student_leave_date_claims WHERE request_id=?",Integer.class,created.id())).isZero();
            assertThatThrownBy(()->studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",1,"Cannot reapprove rejected evidence"))).hasMessageContaining("no longer");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE subject_id=? AND safe_metadata::text LIKE '%Synthetic private family reason%'",Integer.class,created.id().toString())).isZero();
        }
    }

    @Autowired private com.educore.api.studentleave.ApprovedStudentLeaveLookup approvedStudentLeave;

    private com.educore.api.attendance.AttendanceController.OpenRegisterRequest leaveRegisterRequest(PtmFixture f, LocalDate date) {
        var day=attendanceService.createCalendarDay(f.admin(),new com.educore.api.attendance.AttendanceController.CalendarDayRequest(
                f.portal().schoolId(),UUID.fromString("10000000-0000-4000-8000-000000000003"),
                f.request().academicSessionId(),date,"INSTRUCTIONAL","Synthetic leave school day",true,"ACTIVE",null,null));
        return new com.educore.api.attendance.AttendanceController.OpenRegisterRequest(f.portal().schoolId(),
                f.request().classSectionId(),day.id(),null,"DAILY");
    }

    @Test @Transactional
    void studentLeaveInitialAttendanceUsesFrozenApprovalWithoutOverwritingRecordedEvidence() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
            var leave=studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                    f.request().academicSessionId(),today,today,"PERSONAL","Synthetic private leave reason","leave-initial-register"));
            studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic independent approval"));
            studentLeave.savePolicy(f.admin(),new com.educore.api.studentleave.StudentLeaveModels.PolicyRequest(
                    p.schoolId(),f.request().academicSessionId(),30,7,"ABSENT",true,0L,"Synthetic later policy change"));
            var request=leaveRegisterRequest(f,today);
            var register=attendanceService.openRegister(f.admin(),request);
            UUID mark=jdbc.queryForObject("SELECT id FROM attendance_marks WHERE attendance_register_id=?",UUID.class,register.id());
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,mark)).isEqualTo("EXCUSED");
            assertThat(jdbc.queryForObject("SELECT note FROM attendance_marks WHERE id=?",String.class,mark)).isNull();
            assertThat(jdbc.queryForObject("SELECT reason FROM attendance_mark_history WHERE attendance_mark_id=?",String.class,mark))
                    .isEqualTo("Initialized from approved student leave");
            assertThat(jdbc.queryForObject("SELECT student_leave_request_id FROM attendance_mark_history WHERE attendance_mark_id=?",UUID.class,mark)).isEqualTo(leave.id());
            assertThat(jdbc.queryForObject("SELECT student_leave_request_version FROM attendance_mark_history WHERE attendance_mark_id=?",Long.class,mark)).isEqualTo(1L);
            assertThat(attendanceService.openRegister(f.admin(),request).id()).isEqualTo(register.id());
            var edited=attendanceService.recordMarks(f.admin(),register.id(),new com.educore.api.attendance.AttendanceController.RecordMarksRequest(
                    p.schoolId(),0,List.of(new com.educore.api.attendance.AttendanceController.MarkInput(p.learnerId(),"PRESENT",null,0L)),
                    "Synthetic teacher confirms attendance"));
            assertThat(attendanceService.openRegister(f.admin(),request).rowVersion()).isEqualTo(edited.rowVersion());
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,mark)).isEqualTo("PRESENT");
            attendanceService.submit(f.admin(),register.id(),new com.educore.api.attendance.AttendanceController.SubmitRegisterRequest(
                    p.schoolId(),edited.rowVersion(),"Synthetic attendance submission"));
            studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"REVOKED",1,"Synthetic confirmed attendance review"));
            assertThat(attendanceService.openRegister(f.admin(),request).id()).isEqualTo(register.id());
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,mark)).isEqualTo("PRESENT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_mark_history WHERE attendance_mark_id=?",Integer.class,mark)).isEqualTo(2);
        }
    }

    @Test @Transactional
    void studentLeaveAttendanceLookupRejectsOtherScopesAndIgnoresUnapprovedOrWithdrawnRequests() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
            UUID campus=UUID.fromString("10000000-0000-4000-8000-000000000003");
            UUID session=f.request().academicSessionId(),enrolment=uuid("ac000000",13);
            var leave=studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                    session,today.minusDays(1),today,"PERSONAL","Synthetic private request","leave-attendance-scopes"));
            var pendingRegister=attendanceService.openRegister(f.admin(),leaveRegisterRequest(f,today.minusDays(1)));
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE attendance_register_id=?",String.class,pendingRegister.id())).isEqualTo("PRESENT");
            studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic independent approval"));
            assertThat(approvedStudentLeave.forRoster(p.organisationId(),p.schoolId(),session,campus,today,List.of(enrolment))).containsOnlyKeys(enrolment);
            assertThat(approvedStudentLeave.forRoster(UUID.randomUUID(),p.schoolId(),session,campus,today,List.of(enrolment))).isEmpty();
            assertThat(approvedStudentLeave.forRoster(p.organisationId(),UUID.randomUUID(),session,campus,today,List.of(enrolment))).isEmpty();
            assertThat(approvedStudentLeave.forRoster(p.organisationId(),p.schoolId(),UUID.randomUUID(),campus,today,List.of(enrolment))).isEmpty();
            assertThat(approvedStudentLeave.forRoster(p.organisationId(),p.schoolId(),session,UUID.randomUUID(),today,List.of(enrolment))).isEmpty();
            assertThat(approvedStudentLeave.forRoster(p.organisationId(),p.schoolId(),session,campus,today,List.of(UUID.randomUUID()))).isEmpty();
            assertThat(approvedStudentLeave.forRoster(p.organisationId(),p.schoolId(),session,campus,today.plusDays(1),List.of(enrolment))).isEmpty();
            studentLeave.withdraw(f.guardian(),p.learnerId(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(
                    1,"Synthetic plans changed"));
            var request=leaveRegisterRequest(f,today);
            assertThatThrownBy(()->attendanceService.openRegister(f.guardian(),request)).hasMessageContaining("unavailable");
            var withdrawnRegister=attendanceService.openRegister(f.admin(),request);
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE attendance_register_id=?",String.class,withdrawnRegister.id())).isEqualTo("PRESENT");
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE attendance_register_id=?",String.class,pendingRegister.id())).isEqualTo("PRESENT");
        }
    }

    @Test @Transactional
    void appliesV050AndGuardsLeaveAttendanceProvenanceAndPolicyCompatibility() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='050' AND success",Integer.class)).isEqualTo(1);
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
            var leave=studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                    f.request().academicSessionId(),today.minusDays(1),today,"PERSONAL","Synthetic private source","leave-provenance-guard"));
            studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"Synthetic independent approval"));
            var register=attendanceService.openRegister(f.admin(),leaveRegisterRequest(f,today));
            UUID mark=jdbc.queryForObject("SELECT id FROM attendance_marks WHERE attendance_register_id=?",UUID.class,register.id());
            String forged="""
                    INSERT INTO attendance_mark_history(id,organisation_id,school_id,attendance_mark_id,previous_state_code,
                      new_state_code,change_kind,reason,actor_membership_id,occurred_at,student_leave_request_id,student_leave_request_version)
                    SELECT gen_random_uuid(),organisation_id,school_id,attendance_mark_id,NULL,?,'LEAVE_INITIAL',
                      'Synthetic source validation',actor_membership_id,now(),student_leave_request_id,?
                    FROM attendance_mark_history WHERE attendance_mark_id=? AND change_kind='LEAVE_INITIAL'
                    """;
            assertDatabaseRejected(()->jdbc.update(forged,"ABSENT",1,mark),"matching current approval");
            assertDatabaseRejected(()->jdbc.update(forged,"EXCUSED",2,mark),"matching current approval");
            assertDatabaseRejected(()->jdbc.update(forged,"EXCUSED",1,mark),"duplicate key");
            assertDatabaseRejected(()->jdbc.update("UPDATE attendance_mark_history SET student_leave_request_version=2 WHERE attendance_mark_id=?",mark),"append-only");
            UUID policyId=jdbc.queryForObject("SELECT id FROM attendance_policies WHERE academic_session_id=?",UUID.class,f.request().academicSessionId());
            attendanceService.updatePolicy(f.admin(),policyId,new com.educore.api.attendance.AttendanceController.PolicyRequest(
                    p.schoolId(),f.request().academicSessionId(),"Synthetic changed attendance policy","DAILY",List.of("PRESENT","ABSENT"),null,
                    java.util.Map.of("PRESENT",BigDecimal.ONE,"ABSENT",BigDecimal.ZERO),24,7,new BigDecimal("75"),"ACTIVE",0L,
                    "Synthetic absence policy review"));
            var incompatible=leaveRegisterRequest(f,today.minusDays(1));
            assertThatThrownBy(()->attendanceService.openRegister(f.admin(),incompatible)).hasMessageContaining("does not support the approved leave state");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_marks WHERE learner_id=?",Integer.class,p.learnerId())).isEqualTo(1);
        }
    }

    @Test @Transactional
    void studentLeaveEventsAreScopedRevisionBoundRetrySafeAndPrivate() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal();
            for(String ending:List.of("WITHDRAWN","REJECTED","REVOKED")) {
                var request=studentLeaveRequest(f,"synthetic-leave-event-"+ending);
                var created=studentLeave.submit(f.guardian(),p.learnerId(),request);
                assertThat(studentLeave.submit(f.guardian(),p.learnerId(),request).id()).isEqualTo(created.id());
                if(!ending.equals("REJECTED")) {
                    studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                            p.schoolId(),"APPROVED",0,"PRIVATE SYNTHETIC approval reason"));
                    assertThat(studentLeave.submit(f.guardian(),p.learnerId(),request).status()).isEqualTo("APPROVED");
                }
                if(ending.equals("WITHDRAWN")) studentLeave.withdraw(f.guardian(),p.learnerId(),created.id(),
                        new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(1,"PRIVATE SYNTHETIC withdrawal reason"));
                else studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                        p.schoolId(),ending,ending.equals("REJECTED")?0:1,"PRIVATE SYNTHETIC decision reason"));
                assertThatThrownBy(()->studentLeave.decide(f.admin(),created.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                        p.schoolId(),"APPROVED",0,"Synthetic stale decision"))).hasMessageContaining("changed");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_type='STUDENT_LEAVE_REQUEST'",Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox_events event WHERE event.aggregate_type='STUDENT_LEAVE_REQUEST'
                    AND (event.payload->>'organisationId' IS DISTINCT FROM CAST(? AS text)
                      OR event.payload->>'schoolId' IS DISTINCT FROM CAST(? AS text)
                      OR NOT EXISTS(SELECT 1 FROM student_leave_history history
                        WHERE history.id::text=event.payload->>'historyId'
                          AND history.request_id::text=event.aggregate_id
                          AND history.request_id::text=event.payload->>'requestId'
                          AND history.organisation_id=? AND history.school_id=?
                          AND history.request_version::text=event.payload->>'requestVersion'
                          AND history.new_status=event.payload->>'status'
                          AND event.event_type='student_leave.'||lower(history.new_status)
                          AND event.occurred_at=history.occurred_at))
                    """,Integer.class,p.organisationId(),p.schoolId(),p.organisationId(),p.schoolId())).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox_events WHERE aggregate_type='STUDENT_LEAVE_REQUEST'
                      AND (payload - ARRAY['organisationId','schoolId','requestId','historyId','requestVersion','status'] <> '{}'::jsonb
                        OR payload::text LIKE '%PRIVATE SYNTHETIC%' OR payload::text LIKE '%private family reason%'
                        OR headers <> '{}'::jsonb OR processed_at IS NOT NULL)
                    """,Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM (SELECT payload->>'historyId' FROM outbox_events
                      WHERE aggregate_type='STUDENT_LEAVE_REQUEST' GROUP BY payload->>'historyId' HAVING count(*)<>1) duplicate
                    """,Integer.class)).isZero();
        }
    }

    @Test @Transactional
    void recordedAttendanceShowsCurrentScopedLeaveWithoutChangingMarksOrPrivateReasons() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=studentLeaveFixture(); var p=f.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
            var leave=studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                    f.request().academicSessionId(),today.minusDays(1),today,"ILLNESS","PRIVATE SYNTHETIC leave reason","leave-register-context"));
            var earlier=attendanceService.openRegister(f.admin(),leaveRegisterRequest(f,today.minusDays(1)));
            var before=attendanceService.snapshot(f.admin(),p.schoolId(),today.minusDays(1)).registers().getFirst().marks().getFirst();
            assertThat(before.leaveEvidence()).isEmpty();
            studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"APPROVED",0,"PRIVATE SYNTHETIC approval reason"));
            var after=attendanceService.snapshot(f.admin(),p.schoolId(),today.minusDays(1)).registers().getFirst().marks().getFirst();
            assertThat(after.stateCode()).isEqualTo("PRESENT");
            assertThat(after.rowVersion()).isZero();
            assertThat(after.leaveEvidence()).hasSize(1);
            var evidence=after.leaveEvidence().getFirst();
            assertThat(evidence.requestId()).isEqualTo(leave.id());
            assertThat(evidence.requestVersion()).isEqualTo(1);
            assertThat(evidence.status()).isEqualTo("APPROVED");
            assertThat(evidence.attendanceState()).isEqualTo("EXCUSED");
            assertThat(evidence.initializedFromLeave()).isFalse();
            assertThat(applicationContext.getBean(tools.jackson.databind.ObjectMapper.class).writeValueAsString(after))
                    .doesNotContain("PRIVATE SYNTHETIC", "ILLNESS", "requestReason", "guardianId");
            assertThat(approvedStudentLeave.forRecordedMarks(UUID.randomUUID(),p.schoolId(),earlier.id(),List.of(after.id()))).isEmpty();
            assertThat(approvedStudentLeave.forRecordedMarks(p.organisationId(),UUID.randomUUID(),earlier.id(),List.of(after.id()))).isEmpty();
            assertThat(approvedStudentLeave.forRecordedMarks(p.organisationId(),p.schoolId(),UUID.randomUUID(),List.of(after.id()))).isEmpty();
            assertThat(approvedStudentLeave.forRecordedMarks(p.organisationId(),p.schoolId(),earlier.id(),List.of(UUID.randomUUID()))).isEmpty();
            assertThat(approvedStudentLeave.forRecordedMarks(p.organisationId(),p.schoolId(),earlier.id(),List.of())).isEmpty();
            assertThatThrownBy(()->attendanceService.snapshot(f.guardian(),p.schoolId(),today)).hasMessageContaining("unavailable");
            var initialized=attendanceService.openRegister(f.admin(),leaveRegisterRequest(f,today));
            var initial=attendanceService.snapshot(f.admin(),p.schoolId(),today).registers().getFirst().marks().getFirst();
            assertThat(initial.leaveEvidence().getFirst().initializedFromLeave()).isTrue();
            studentLeave.withdraw(f.guardian(),p.learnerId(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(
                    1,"PRIVATE SYNTHETIC plans changed"));
            var withdrawn=attendanceService.snapshot(f.admin(),p.schoolId(),today).registers().getFirst().marks().getFirst();
            assertThat(withdrawn.leaveEvidence().getFirst().status()).isEqualTo("WITHDRAWN");
            assertThat(withdrawn.leaveEvidence().getFirst().requestVersion()).isEqualTo(2);
            assertThat(withdrawn.stateCode()).isEqualTo("EXCUSED");
            assertThat(withdrawn.rowVersion()).isZero();
            assertThat(attendanceService.snapshot(f.admin(),p.schoolId(),today.minusDays(1)).registers().getFirst().marks().getFirst().leaveEvidence()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_mark_history WHERE attendance_mark_id=?",Integer.class,withdrawn.id())).isEqualTo(1);
            assertThat(attendanceService.openRegister(f.admin(),new com.educore.api.attendance.AttendanceController.OpenRegisterRequest(
                    p.schoolId(),f.request().classSectionId(),jdbc.queryForObject("SELECT calendar_day_id FROM attendance_registers WHERE id=?",UUID.class,initialized.id()),null,"DAILY")).id()).isEqualTo(initialized.id());
        }
    }

    private LeaveCorrectionFixture draftLeaveReviewFixture() {
        var f=studentLeaveFixture(); var p=f.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
        var leave=studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                f.request().academicSessionId(),today,today,"PERSONAL","Synthetic private source","draft-leave-review-source"));
        var register=attendanceService.openRegister(f.admin(),leaveRegisterRequest(f,today));
        studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                p.schoolId(),"APPROVED",0,"Synthetic independent approval"));
        UUID mark=jdbc.queryForObject("SELECT id FROM attendance_marks WHERE attendance_register_id=?",UUID.class,register.id());
        return new LeaveCorrectionFixture(f,leave.id(),register.id(),mark);
    }

    private com.educore.api.attendance.AttendanceController.DraftLeaveReviewRequest draftReview(
            LeaveCorrectionFixture f,UUID operation,long sourceVersion,long registerVersion,long markVersion,String decision) {
        return new com.educore.api.attendance.AttendanceController.DraftLeaveReviewRequest(f.people().portal().schoolId(),operation,
                f.markId(),f.leaveId(),sourceVersion,registerVersion,markVersion,decision,"Synthetic explicit attendance review");
    }

    @Test @Transactional
    void draftLeaveReviewsPreserveObservationsSupportRetriesAndTrackSourceChanges() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=draftLeaveReviewFixture(); var people=f.people(); var p=people.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
            var keep=draftReview(f,UUID.randomUUID(),1,0,0,"KEEP_RECORDED");
            assertThat(attendanceService.reviewDraftLeave(people.admin(),f.registerId(),keep).rowVersion()).isEqualTo(1);
            assertThat(attendanceService.reviewDraftLeave(people.admin(),f.registerId(),keep).rowVersion()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("PRESENT");
            var snapshot=attendanceService.snapshot(people.admin(),p.schoolId(),today).registers().getFirst().marks().getFirst();
            assertThat(snapshot.leaveEvidence().getFirst().review().decision()).isEqualTo("KEEP_RECORDED");
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,keep.operationId(),1,0,0,"APPLY_APPROVED"))).hasMessageContaining("already used");
            attendanceService.recordMarks(people.admin(),f.registerId(),new com.educore.api.attendance.AttendanceController.RecordMarksRequest(
                    p.schoolId(),1,List.of(new com.educore.api.attendance.AttendanceController.MarkInput(p.learnerId(),"ABSENT",null,0L)),"Synthetic changed observation"));
            assertThat(attendanceService.snapshot(people.admin(),p.schoolId(),today).registers().getFirst().marks().getFirst().leaveEvidence().getFirst().review()).isNull();
            var apply=draftReview(f,UUID.randomUUID(),1,2,1,"APPLY_APPROVED");
            attendanceService.reviewDraftLeave(people.admin(),f.registerId(),apply);
            assertThat(attendanceService.reviewDraftLeave(people.admin(),f.registerId(),apply).rowVersion()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("EXCUSED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_leave_reviews WHERE mark_history_id IS NOT NULL",Integer.class)).isEqualTo(1);
            studentLeave.withdraw(people.guardian(),p.learnerId(),f.leaveId(),new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(1,"Synthetic plans changed"));
            var withdrawn=attendanceService.snapshot(people.admin(),p.schoolId(),today).registers().getFirst().marks().getFirst();
            assertThat(withdrawn.stateCode()).isEqualTo("EXCUSED");
            assertThat(withdrawn.leaveEvidence().getFirst().status()).isEqualTo("WITHDRAWN");
            assertThat(withdrawn.leaveEvidence().getFirst().review()).isNull();
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,UUID.randomUUID(),2,3,2,"APPLY_APPROVED"))).hasMessageContaining("cannot be applied");
            attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,UUID.randomUUID(),2,3,2,"KEEP_RECORDED"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_leave_reviews",Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_mark_history WHERE attendance_mark_id=?",Integer.class,f.markId())).isEqualTo(3);
        }
    }

    @Test @Transactional
    void draftLeaveReviewsRejectWrongAuthorityStaleVersionsAndSubmittedRegisters() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=draftLeaveReviewFixture(); var people=f.people(); var p=people.portal();
            var valid=draftReview(f,UUID.randomUUID(),1,0,0,"KEEP_RECORDED");
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.guardian(),f.registerId(),valid)).hasMessageContaining("unavailable");
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,UUID.randomUUID(),2,0,0,"KEEP_RECORDED"))).hasMessageContaining("leave evidence changed");
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,UUID.randomUUID(),1,0,9,"KEEP_RECORDED"))).hasMessageContaining("changed attendance mark");
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,UUID.randomUUID(),1,9,0,"KEEP_RECORDED"))).hasMessageContaining("Refresh the register");
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),new com.educore.api.attendance.AttendanceController.DraftLeaveReviewRequest(
                    UUID.randomUUID(),UUID.randomUUID(),f.markId(),f.leaveId(),1,0,0,"KEEP_RECORDED","Synthetic wrong school"))).hasMessageContaining("unavailable");
            assertThatThrownBy(()->approvedStudentLeave.requireReviewSource(UUID.randomUUID(),p.schoolId(),f.registerId(),f.markId(),f.leaveId(),1)).hasMessageContaining("leave evidence changed");
            attendanceService.submit(people.admin(),f.registerId(),new com.educore.api.attendance.AttendanceController.SubmitRegisterRequest(p.schoolId(),0,"Synthetic completed register"));
            assertThatThrownBy(()->attendanceService.reviewDraftLeave(people.admin(),f.registerId(),draftReview(f,UUID.randomUUID(),1,1,0,"KEEP_RECORDED"))).hasMessageContaining("require a correction");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_leave_reviews",Integer.class)).isZero();
        }
    }

    @Test @Transactional
    void appliesV052AndProtectsDraftLeaveReviewHistoryWithRealRowSecurity() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='052'",Boolean.class)).isTrue();
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=draftLeaveReviewFixture(); var people=f.people(); var p=people.portal();
            var review=draftReview(f,UUID.randomUUID(),1,0,0,"APPLY_APPROVED");
            attendanceService.reviewDraftLeave(people.admin(),f.registerId(),review);
            assertDatabaseRejected(()->jdbc.update("UPDATE attendance_leave_reviews SET reason='Synthetic forged reason' WHERE id=?",review.operationId()),"append-only");
            assertDatabaseRejected(()->jdbc.update("DELETE FROM attendance_leave_reviews WHERE id=?",review.operationId()),"append-only");
            assertDatabaseRejected(()->jdbc.update("INSERT INTO attendance_leave_reviews SELECT gen_random_uuid(),organisation_id,school_id,attendance_register_id,attendance_mark_id,student_leave_request_id,student_leave_request_version+1,source_status,decision,previous_state_code,resulting_state_code,previous_mark_version,resulting_mark_version,resulting_register_version,mark_history_id,actor_membership_id,reason,request_fingerprint,occurred_at FROM attendance_leave_reviews WHERE id=?",review.operationId()),"current immutable source");
            assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname='attendance_leave_reviews'",Boolean.class)).isTrue();
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("CREATE ROLE educore_draft_leave_review_rls NOLOGIN");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_draft_leave_review_rls");
            jdbc.execute("GRANT SELECT,UPDATE ON attendance_leave_reviews TO educore_draft_leave_review_rls");
            jdbc.execute("SET LOCAL ROLE educore_draft_leave_review_rls");
            try {
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,p.organisationId().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_leave_reviews",Integer.class)).isEqualTo(1);
                jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,UUID.randomUUID().toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_leave_reviews",Integer.class)).isZero();
                assertThat(jdbc.update("UPDATE attendance_leave_reviews SET reason=reason")).isZero();
            } finally { jdbc.execute("RESET ROLE"); }
        }
    }

    private LeaveCorrectionFixture leaveCorrectionFixture() {
        var f=studentLeaveFixture(); var p=f.portal(); LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
        var leave=studentLeave.submit(f.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                f.request().academicSessionId(),today,today,"PERSONAL","Synthetic private source","leave-correction-source"));
        var register=attendanceService.openRegister(f.admin(),leaveRegisterRequest(f,today));
        attendanceService.submit(f.admin(),register.id(),new com.educore.api.attendance.AttendanceController.SubmitRegisterRequest(
                p.schoolId(),0,"Synthetic observed attendance"));
        studentLeave.decide(f.admin(),leave.id(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                p.schoolId(),"APPROVED",0,"Synthetic independent leave approval"));
        UUID mark=jdbc.queryForObject("SELECT id FROM attendance_marks WHERE attendance_register_id=?",UUID.class,register.id());
        return new LeaveCorrectionFixture(f,leave.id(),register.id(),mark);
    }
    private record LeaveCorrectionFixture(PtmFixture people,UUID leaveId,UUID registerId,UUID markId) {
        com.educore.api.attendance.AttendanceController.CorrectionRequest request() {
            return new com.educore.api.attendance.AttendanceController.CorrectionRequest(people.portal().schoolId(),markId,
                    "EXCUSED","Synthetic reviewed leave absence",new com.educore.api.attendance.AttendanceController.LeaveCorrectionSource(leaveId,1L,0L));
        }
    }

    @Test @Transactional
    void leaveLinkedCorrectionsAreRetrySafeAndPreserveManualCorrectionsAndHistory() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=leaveCorrectionFixture(); var people=f.people(); var p=people.portal();
            var correction=attendanceService.requestCorrection(people.admin(),f.request());
            assertThat(attendanceService.requestCorrection(people.admin(),f.request()).id()).isEqualTo(correction.id());
            var review=attendanceService.snapshot(people.admin(),p.schoolId(),LocalDate.now(ZoneId.of("Asia/Kolkata"))).corrections().getFirst();
            assertThat(review.leaveSource()).isEqualTo(f.request().leaveSource());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_correction_requests WHERE attendance_mark_id=?",Integer.class,f.markId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("PRESENT");
            assertThat(jdbc.queryForObject("SELECT student_leave_request_id FROM attendance_correction_requests WHERE id=?",UUID.class,correction.id())).isEqualTo(f.leaveId());
            assertThatThrownBy(()->attendanceService.requestCorrection(people.admin(),new com.educore.api.attendance.AttendanceController.CorrectionRequest(
                    p.schoolId(),f.markId(),"EXCUSED","Different synthetic reason",f.request().leaveSource()))).hasMessageContaining("already pending");
            attendanceService.decideCorrection(people.admin(),correction.id(),new com.educore.api.attendance.AttendanceController.CorrectionDecisionRequest(
                    p.schoolId(),"APPROVED","Synthetic source and observation reviewed",0));
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("EXCUSED");
            assertThat(jdbc.queryForObject("SELECT row_version FROM attendance_marks WHERE id=?",Long.class,f.markId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_mark_history WHERE attendance_mark_id=?",Integer.class,f.markId())).isEqualTo(2);
            studentLeave.decide(people.admin(),f.leaveId(),new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                    p.schoolId(),"REVOKED",1,"Synthetic later leave withdrawal"));
            var mark=attendanceService.snapshot(people.admin(),p.schoolId(),LocalDate.now(ZoneId.of("Asia/Kolkata"))).registers().getFirst().marks().getFirst();
            assertThat(mark.leaveEvidence().getFirst().status()).isEqualTo("REVOKED");
            assertThat(mark.leaveEvidence().getFirst().initializedFromLeave()).isFalse();
            assertThat(mark.stateCode()).isEqualTo("EXCUSED");
            var manual=attendanceService.requestCorrection(people.admin(),new com.educore.api.attendance.AttendanceController.CorrectionRequest(
                    p.schoolId(),f.markId(),"PRESENT","Synthetic teacher confirms physical attendance"));
            attendanceService.decideCorrection(people.admin(),manual.id(),new com.educore.api.attendance.AttendanceController.CorrectionDecisionRequest(
                    p.schoolId(),"APPROVED","Synthetic observation checked",0));
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("PRESENT");
            assertDatabaseRejected(()->jdbc.update("DELETE FROM attendance_correction_requests WHERE id=?",correction.id()),"cannot be deleted");
        }
    }

    @Test @Transactional
    void withdrawnAndRevokedLeaveCannotApprovePendingCorrectionsButCanBeRejected() {
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=leaveCorrectionFixture(); var people=f.people(); var p=people.portal();
            UUID source=f.leaveId();
            for(String state:List.of("WITHDRAWN","REVOKED")) {
                var request=new com.educore.api.attendance.AttendanceController.CorrectionRequest(p.schoolId(),f.markId(),
                        "EXCUSED","Synthetic leave review",new com.educore.api.attendance.AttendanceController.LeaveCorrectionSource(source,1L,0L));
                var correction=attendanceService.requestCorrection(people.admin(),request);
                if(state.equals("WITHDRAWN")) studentLeave.withdraw(people.guardian(),p.learnerId(),source,
                        new com.educore.api.studentleave.StudentLeaveModels.WithdrawalRequest(1,"Synthetic family plans changed"));
                else studentLeave.decide(people.admin(),source,new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                        p.schoolId(),"REVOKED",1,"Synthetic management evidence changed"));
                assertThatThrownBy(()->attendanceService.decideCorrection(people.admin(),correction.id(),
                        new com.educore.api.attendance.AttendanceController.CorrectionDecisionRequest(p.schoolId(),"APPROVED","Synthetic stale attempt",0)))
                        .hasMessageContaining("approval or attendance mark changed");
                assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("PRESENT");
                assertThat(jdbc.queryForObject("SELECT status FROM attendance_correction_requests WHERE id=?",String.class,correction.id())).isEqualTo("PENDING");
                assertDatabaseRejected(()->jdbc.update("UPDATE attendance_correction_requests SET status='APPROVED',decided_by_membership_id=requested_by_membership_id,decision_reason='Synthetic direct stale approval',decided_at=now(),updated_at=now(),row_version=row_version+1 WHERE id=?",correction.id()),"matching current approval");
                attendanceService.decideCorrection(people.admin(),correction.id(),new com.educore.api.attendance.AttendanceController.CorrectionDecisionRequest(
                        p.schoolId(),"REJECTED","Synthetic obsolete source rejected",0));
                if(state.equals("WITHDRAWN")) {
                    LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
                    var next=studentLeave.submit(people.guardian(),p.learnerId(),new com.educore.api.studentleave.StudentLeaveModels.SubmitRequest(
                            people.request().academicSessionId(),today,today,"PERSONAL","Synthetic new source","leave-correction-second"));
                    source=next.id();
                    studentLeave.decide(people.admin(),source,new com.educore.api.studentleave.StudentLeaveModels.DecisionRequest(
                            p.schoolId(),"APPROVED",0,"Synthetic new independent approval"));
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_mark_history WHERE attendance_mark_id=?",Integer.class,f.markId())).isEqualTo(1);
        }
    }

    @Test @Transactional
    void appliesV051AndRejectsForgedOrMismatchedLeaveCorrectionSources() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='051' AND success",Integer.class)).isEqualTo(1);
        try(var tenant=TenantRequestContext.open(null,UUID.fromString("10000000-0000-4000-8000-000000000001"))) {
            var f=leaveCorrectionFixture(); var people=f.people(); var p=people.portal();
            assertThatThrownBy(()->attendanceService.requestCorrection(people.guardian(),f.request())).hasMessageContaining("unavailable");
            assertThatThrownBy(()->attendanceService.requestCorrection(people.admin(),new com.educore.api.attendance.AttendanceController.CorrectionRequest(
                    UUID.randomUUID(),f.markId(),"EXCUSED","Synthetic other school",f.request().leaveSource()))).hasMessageContaining("unavailable");
            for(var source:List.of(new com.educore.api.attendance.AttendanceController.LeaveCorrectionSource(UUID.randomUUID(),1L,0L),
                    new com.educore.api.attendance.AttendanceController.LeaveCorrectionSource(f.leaveId(),2L,0L),
                    new com.educore.api.attendance.AttendanceController.LeaveCorrectionSource(f.leaveId(),1L,99L))) {
                assertThatThrownBy(()->attendanceService.requestCorrection(people.admin(),new com.educore.api.attendance.AttendanceController.CorrectionRequest(
                        p.schoolId(),f.markId(),"EXCUSED","Synthetic mismatched source",source))).hasMessageContaining("approval or attendance mark changed");
            }
            assertThatThrownBy(()->attendanceService.requestCorrection(people.admin(),new com.educore.api.attendance.AttendanceController.CorrectionRequest(
                    p.schoolId(),f.markId(),"ABSENT","Synthetic wrong policy state",f.request().leaveSource()))).hasMessageContaining("approval or attendance mark changed");
            String forged="""
                    INSERT INTO attendance_correction_requests(id,organisation_id,school_id,attendance_mark_id,requested_state_code,
                      request_reason,status,requested_by_membership_id,created_at,updated_at,row_version,
                      student_leave_request_id,student_leave_request_version,source_mark_version)
                    SELECT gen_random_uuid(),organisation_id,school_id,id,?,'Synthetic forged source','PENDING',recorded_by_membership_id,
                      now(),now(),0,?,1,? FROM attendance_marks WHERE id=?
                    """;
            assertDatabaseRejected(()->jdbc.update(forged,"ABSENT",f.leaveId(),0,f.markId()),"matching current approval");
            assertDatabaseRejected(()->jdbc.update(forged,"EXCUSED",f.leaveId(),99,f.markId()),"matching scoped mark and version");
            var correction=attendanceService.requestCorrection(people.admin(),f.request());
            assertDatabaseRejected(()->jdbc.update("UPDATE attendance_correction_requests SET status='REJECTED',decided_by_membership_id=requested_by_membership_id,decision_reason='Synthetic rewrite',decided_at=now(),updated_at=now(),row_version=row_version+1,student_leave_request_version=2 WHERE id=?",correction.id()),"source identity is immutable");
            assertDatabaseRejected(()->jdbc.update("UPDATE attendance_correction_requests SET status='APPROVED',decided_by_membership_id=requested_by_membership_id,decision_reason='Synthetic missing mark change',decided_at=now(),updated_at=now(),row_version=row_version+1 WHERE id=?",correction.id()),"matching scoped mark and version");
            // Simulate a changed mark version while the source remains approved: approval must not overwrite it.
            jdbc.queryForObject("SELECT set_config('app.attendance_correction','approved',true)",String.class);
            jdbc.update("UPDATE attendance_marks SET note='Synthetic intervening observation',row_version=row_version+1,updated_at=now() WHERE id=?",f.markId());
            assertThatThrownBy(()->attendanceService.decideCorrection(people.admin(),correction.id(),
                    new com.educore.api.attendance.AttendanceController.CorrectionDecisionRequest(p.schoolId(),"APPROVED","Synthetic changed mark attempt",0)))
                    .hasMessageContaining("approval or attendance mark changed");
            assertThat(jdbc.queryForObject("SELECT state_code FROM attendance_marks WHERE id=?",String.class,f.markId())).isEqualTo("PRESENT");
        }
    }

    private PtmFixture insertPtmFixture() {
        var p = academyPortalFixture("ac000000");
        insertPortalFixture(p, "synthetic-ptm");
        UUID campus = UUID.fromString("10000000-0000-4000-8000-000000000003");
        UUID session = uuid("ac000000", 8), section = uuid("ac000000", 9), designation = uuid("ac000000", 10);
        UUID profile = uuid("ac000000", 11), assignment = uuid("ac000000", 12), enrolment = uuid("ac000000", 13);
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES (?, ?),(?, ?),(?, ?)",
                p.staffMembershipId(), UUID.fromString("00000000-0000-4000-8000-000000000001"),
                p.staffMembershipId(), UUID.fromString("00000000-0000-4000-8000-000000000003"),
                p.guardianMembershipId(), UUID.fromString("00000000-0000-4000-8000-000000000006"));
        jdbc.update("""
                INSERT INTO staff_designations(id,organisation_id,school_id,code,display_name,staff_category,
                  cbse_staff_category,teaching_role,leadership_role,display_order,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN_PTM','Synthetic PTM teacher','TEACHING','OTHER_TEACHING',true,false,0,
                  'ACTIVE',?,now(),now(),0)
                """, designation,p.organisationId(),p.schoolId(),p.staffMembershipId());
        jdbc.update("""
                INSERT INTO staff_profiles(id,organisation_id,school_id,employee_code,legal_name,display_name,
                  user_account_id,joined_on,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'SYN-PTM','Synthetic PTM teacher','Synthetic PTM teacher',?,
                  CURRENT_DATE-30,'ACTIVE',?,now(),now(),0)
                """, profile,p.organisationId(),p.schoolId(),p.staffUserId(),p.staffMembershipId());
        jdbc.update("""
                INSERT INTO staff_assignments(id,organisation_id,school_id,staff_profile_id,staff_designation_id,
                  campus_id,employment_type,employer_kind,primary_assignment,starts_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?,'FULL_TIME','SCHOOL',true,CURRENT_DATE-30,'ACTIVE',?,now(),now(),0)
                """, assignment,p.organisationId(),p.schoolId(),profile,designation,campus,p.staffMembershipId());
        jdbc.update("""
                INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, 'Synthetic PTM session',CURRENT_DATE-30,CURRENT_DATE+330,'ACTIVE',now(),now(),0)
                """,session,p.organisationId(),p.schoolId());
        jdbc.update("""
                INSERT INTO class_sections(id,organisation_id,school_id,campus_id,academic_session_id,grade_code,
                  code,display_name,status,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?,'VI','PTM','Synthetic PTM class','ACTIVE',now(),now(),0)
                """,section,p.organisationId(),p.schoolId(),campus,session);
        jdbc.update("""
                INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,
                  learner_id,origin_application_id,grade_code,status,placement_status,starts_on,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?, ?,'VI','ACTIVE','PLACED',CURRENT_DATE-30,now(),now(),0)
                """,enrolment,p.organisationId(),p.schoolId(),campus,session,p.learnerId(),p.applicationId());
        jdbc.update("""
                INSERT INTO student_section_placements(id,organisation_id,school_id,enrolment_id,learner_id,
                  class_section_id,starts_on,status,placed_by_membership_id,placement_reason,created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, ?, ?,CURRENT_DATE-30,'ACTIVE',?,'Synthetic PTM placement',now(),now(),0)
                """,UUID.randomUUID(),p.organisationId(),p.schoolId(),enrolment,p.learnerId(),section,p.staffMembershipId());
        var now = java.time.Instant.now();
        var day = now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().plusDays(2);
        var start = day.atTime(10,0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        var request = new com.educore.api.ptm.PtmController.CreateEventRequest(p.schoolId(),session,section,
                p.staffMembershipId(),"Synthetic family meetings",null,null,null,"Synthetic room",day,
                now.minusSeconds(3600),start.minusSeconds(3600),start.minusSeconds(1800),10080,start,10,5,3,"ptm-event-key");
        var admin = new AuthenticatedUser(p.staffUserId(),UUID.randomUUID(),"synthetic-ptm-staff@example.test",
                "synthetic-ptm-staff@example.test","Synthetic teacher",true,p.organisationId(),
                Set.of(p.staffMembershipId()),Set.of("ptm.read","ptm.manage"),List.of());
        var guardian = new AuthenticatedUser(p.guardianUserId(),UUID.randomUUID(),"synthetic-ptm-guardian@example.test",
                "synthetic-ptm-guardian@example.test","Synthetic guardian",true,p.organisationId(),
                Set.of(p.guardianMembershipId()),Set.of("portal.guardian"),List.of());
        return new PtmFixture(p,admin,guardian,request,assignment);
    }
    private record PtmFixture(PortalFixture portal, AuthenticatedUser admin, AuthenticatedUser guardian,
            com.educore.api.ptm.PtmController.CreateEventRequest request, UUID assignmentId) {}

    @Test
    @Transactional
    void ptmPublicationBlocksOverlapAndRechecksLiveManagementAccess() {
        var f = insertPtmFixture();
        var q = f.request();
        var first = ptm.create(f.admin(), q);
        ptm.publish(f.admin(), first.id(), new com.educore.api.ptm.PtmController.EventDecisionRequest(
                q.schoolId(), 0, "Synthetic first publication"));
        var second = ptm.create(f.admin(), new com.educore.api.ptm.PtmController.CreateEventRequest(
                q.schoolId(), q.academicSessionId(), q.classSectionId(), q.teacherMembershipId(),
                q.titleEn(), q.titleHi(), q.instructionsEn(), q.instructionsHi(), q.venue(), q.eventDate(),
                q.bookingOpensAt(), q.bookingClosesAt(), q.changesCloseAt(), q.reminderMinutesBefore(),
                q.firstSlotStartsAt(), q.slotDurationMinutes(), q.gapMinutes(), q.slotCount(), "ptm-overlap-key"));
        assertDatabaseRejected(() -> ptm.publish(f.admin(), second.id(),
                new com.educore.api.ptm.PtmController.EventDecisionRequest(q.schoolId(), 0,
                        "Synthetic overlap publication")), "overlaps another meeting");
        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO ptm_slots(id,organisation_id,school_id,ptm_event_id,starts_at,ends_at,status,
                  created_at,updated_at,row_version)
                VALUES (?, ?, ?, ?, now()+interval '3 days',now()+interval '3 days 10 minutes',
                  'AVAILABLE',now(),now(),0)
                """, UUID.randomUUID(), f.portal().organisationId(), q.schoolId(), first.id()), "draft event");
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=? AND role_id=?",
                f.portal().staffMembershipId(), UUID.fromString("00000000-0000-4000-8000-000000000001"));
        assertThat(ptm.workspace(f.admin(), q.schoolId()).canManage()).isFalse();
        assertThat(ptm.workspace(f.admin(), q.schoolId()).events()).hasSize(2);
        assertThatThrownBy(() -> ptm.cancelEvent(f.admin(), first.id(),
                new com.educore.api.ptm.PtmController.EventDecisionRequest(q.schoolId(), 1,
                        "Revoked manager must be denied"))).hasMessageContaining("unavailable");
    }

    @Test
    @Transactional
    void appliesV042AndV043PtmAndIsolatesAppointmentEvidenceUnderRowSecurity() {
        for (String version : List.of("042", "043"))
            assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version=?", Boolean.class, version)).isTrue();
        var f = insertPtmFixture();
        var event = ptm.create(f.admin(), f.request());
        ptm.publish(f.admin(), event.id(), new com.educore.api.ptm.PtmController.EventDecisionRequest(
                f.portal().schoolId(), 0, "Synthetic scoped publication"));
        var slot = ptm.familySchedule(f.guardian(), f.portal().learnerId()).events().getFirst().slots().getFirst();
        ptm.book(f.guardian(), f.portal().learnerId(), slot.id(),
                new com.educore.api.ptm.FamilyPtmController.BookRequest(null, "ptm-rls-key"));
        List<String> tables = List.of("ptm_events", "ptm_slots", "ptm_bookings", "ptm_booking_history", "ptm_booking_reminders");
        for (String table : tables) assertThat(jdbc.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname=?", Boolean.class, table)).isTrue();
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("CREATE ROLE educore_ptm_rls_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO educore_ptm_rls_test");
        for (String table : tables) jdbc.execute("GRANT SELECT,UPDATE ON " + table + " TO educore_ptm_rls_test");
        jdbc.execute("SET LOCAL ROLE educore_ptm_rls_test");
        try {
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class,
                    f.portal().organisationId().toString());
            for (String table : tables) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isPositive();
            jdbc.queryForObject("SELECT set_config('app.current_organisation_id',?,true)", String.class, UUID.randomUUID().toString());
            for (String table : tables) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
                assertThat(jdbc.update("UPDATE " + table + " SET school_id=school_id")).isZero();
            }
        } finally { jdbc.execute("RESET ROLE"); }
    }
}
