package com.educore.api.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.common.api.ApiException;
import com.educore.api.people.PeopleStaffAccountBridge.StaffAccountCandidate;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked service/input guards only; not evidence of real PostgreSQL locking, RLS or trigger execution. */
class StaffAccountLinkServiceTest {
    private static final UUID ORGANISATION = UUID.randomUUID();
    private static final UUID SCHOOL = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID ACCEPTED_USER = UUID.randomUUID();
    private static final UUID ACCEPTED_MEMBERSHIP = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-01T08:00:00Z");
    private static final String REASON = "Staff invitation accepted";
    private static ValidatorFactory validators;
    private NamedParameterJdbcTemplate jdbc;
    private StaffAccountLinkService service;

    @BeforeAll
    static void validation() { validators = Validation.buildDefaultValidatorFactory(); }

    @AfterAll
    static void closeValidation() { validators.close(); }

    @BeforeEach
    void fixture() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        service = new StaffAccountLinkService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), validators.getValidator());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        candidate(valid());
    }

    @AfterEach
    void clearSyntheticTransactionFlags() { TransactionSynchronizationManager.clear(); }

    @Test
    void requiresMandatoryWritableCallerTransaction() {
        assertThat(StaffAccountLinkService.class.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.lockCandidate(ORGANISATION, SCHOOL, STAFF)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> service.lockCandidate(ORGANISATION, SCHOOL, STAFF)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void refusesMissingOrDifferentActualDatabaseOrganisationBeforeReadingStaff() {
        when(jdbc.queryForObject(contains("current_app_organisation_id()"), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(null, false);
        assertCode(() -> service.lockCandidate(null, SCHOOL, STAFF), "STAFF_ACCOUNT_SCOPE_DENIED");
        assertCode(() -> service.lockCandidate(ORGANISATION, SCHOOL, STAFF), "STAFF_ACCOUNT_SCOPE_DENIED");
        assertCode(() -> service.lockCandidate(ORGANISATION, SCHOOL, STAFF), "STAFF_ACCOUNT_SCOPE_DENIED");
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void locksOnlyExplicitScopedStaffAndKeepsSchoolOrganisationActive() {
        assertThat(service.lockCandidate(ORGANISATION, SCHOOL, STAFF)).isEqualTo(valid());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("staff.id=:staffId", "staff.organisation_id=:organisationId", "staff.school_id=:schoolId",
                "organisation.status='ACTIVE'", "school.status='ACTIVE'", "FOR UPDATE OF staff");
        assertThat(sql.getValue()).doesNotContain("FOR SHARE OF organisation");
        assertThat(parameters.getValue().getValue("organisationId")).isEqualTo(ORGANISATION);
        assertThat(parameters.getValue().getValue("schoolId")).isEqualTo(SCHOOL);
        assertThat(parameters.getValue().getValue("staffId")).isEqualTo(STAFF);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "ended", "future", "linked", "null-email", "invalid-email", "long-email",
            "uppercase-email", "blank-name", "long-name", "control-name", "unsupported-language", "negative-version"})
    void refusesIneligibleEmploymentOrContactWithoutWriting(String invalidity) {
        StaffAccountCandidate source = valid();
        String status = switch (invalidity) { case "inactive" -> "INACTIVE"; case "ended" -> "ENDED"; default -> source.status(); };
        LocalDate joined = "future".equals(invalidity) ? LocalDate.of(2026, 6, 2) : source.joinedOn();
        LocalDate ended = "ended".equals(invalidity) ? LocalDate.of(2026, 5, 31) : null;
        String email = switch (invalidity) {
            case "null-email" -> null;
            case "invalid-email" -> "not-an-email";
            case "long-email" -> "s".repeat(250) + "@example.test";
            case "uppercase-email" -> "SYNTHETIC-STAFF@EXAMPLE.TEST";
            default -> source.email();
        };
        String name = switch (invalidity) {
            case "blank-name" -> " ";
            case "long-name" -> "s".repeat(121);
            case "control-name" -> "Synthetic\u0000staff";
            default -> source.displayName();
        };
        candidate(new StaffAccountCandidate(STAFF, ORGANISATION, SCHOOL, source.schoolName(), name, email,
                "unsupported-language".equals(invalidity) ? "unsupported" : source.language(),
                "negative-version".equals(invalidity) ? -1 : source.version(), joined, ended, status,
                "linked".equals(invalidity) ? UUID.randomUUID() : null));
        assertCode(() -> service.lockCandidate(ORGANISATION, SCHOOL, STAFF), "STAFF_ACCOUNT_CANDIDATE_UNAVAILABLE");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void refusesAbsentScopedStaffRatherThanCreatingAnEmploymentProfile() {
        doReturn(List.of()).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        assertCode(() -> service.lockCandidate(ORGANISATION, SCHOOL, STAFF), "STAFF_ACCOUNT_CANDIDATE_NOT_FOUND");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void refusesStaleVersionOrChangedSourceSnapshotWithoutLinking() {
        StaffAccountCandidate expected = valid();
        for (String changed : List.of("version", "name", "email", "language", "school-name", "joined-on")) {
            candidate(new StaffAccountCandidate(STAFF, ORGANISATION, SCHOOL,
                    "school-name".equals(changed) ? "Synthetic renamed school" : expected.schoolName(),
                    "name".equals(changed) ? "Synthetic renamed employee" : expected.displayName(),
                    "email".equals(changed) ? "synthetic-different@example.test" : expected.email(),
                    "language".equals(changed) ? "hi" : expected.language(),
                    "version".equals(changed) ? expected.version() + 1 : expected.version(),
                    "joined-on".equals(changed) ? expected.joinedOn().plusDays(1) : expected.joinedOn(), null, "ACTIVE", null));
            assertCode(() -> service.linkAcceptedAccount(expected, ACCEPTED_USER, ACCEPTED_MEMBERSHIP, REASON),
                    "STAFF_ACCOUNT_CANDIDATE_CHANGED");
        }
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void requiresAcceptedUsersOwnActiveSameSchoolStaffMembershipAndEmail() {
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM memberships"), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        assertCode(() -> service.linkAcceptedAccount(valid(), ACCEPTED_USER, ACCEPTED_MEMBERSHIP, REASON), "STAFF_ACCOUNT_LINK_INVALID");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(2)).queryForObject(sql.capture(), parameters.capture(), eq(Boolean.class));
        assertThat(sql.getAllValues().getLast()).contains("membership.id=:actorMembershipId", "membership.user_id=:userId",
                "membership.organisation_id=:organisationId", "membership.school_id=:schoolId", "membership.status='ACTIVE'",
                "membership.valid_from<=:now", "membership.valid_until>:now",
                "account.status='ACTIVE'", "account.email_normalized=:email", "role.code<>'parent_guardian'");
        SqlParameterSource values = parameters.getAllValues().getLast();
        assertThat(values.getValue("actorMembershipId")).isEqualTo(ACCEPTED_MEMBERSHIP);
        assertThat(values.getValue("userId")).isEqualTo(ACCEPTED_USER);
        assertThat(values.getValue("organisationId")).isEqualTo(ORGANISATION);
        assertThat(values.getValue("schoolId")).isEqualTo(SCHOOL);
        assertThat(values.getValue("email")).isEqualTo(valid().email());
        assertThat(values.getValue("now")).isEqualTo(Timestamp.from(NOW));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void linksOnceWithOptimisticVersionAndActualAcceptedMembershipHistory() {
        service.linkAcceptedAccount(valid(), ACCEPTED_USER, ACCEPTED_MEMBERSHIP, REASON);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), parameters.capture());
        assertThat(sql.getAllValues().getFirst()).contains("UPDATE staff_profiles SET user_account_id=:userId", "row_version=row_version+1",
                "organisation_id=:organisationId", "school_id=:schoolId", "row_version=:version", "user_account_id IS NULL");
        assertThat(sql.getAllValues().getLast()).contains("INSERT INTO staff_profile_history", "'PROFILE_UPDATED'", ":userId", ":actorMembershipId");
        SqlParameterSource values = parameters.getAllValues().getLast();
        assertThat(values.getValue("organisationId")).isEqualTo(ORGANISATION);
        assertThat(values.getValue("schoolId")).isEqualTo(SCHOOL);
        assertThat(values.getValue("staffId")).isEqualTo(STAFF);
        assertThat(values.getValue("version")).isEqualTo(valid().version());
        assertThat(values.getValue("email")).isEqualTo(valid().email());
        assertThat(values.getValue("userId")).isEqualTo(ACCEPTED_USER);
        assertThat(values.getValue("actorMembershipId")).isEqualTo(ACCEPTED_MEMBERSHIP);
        assertThat(values.getValue("reason")).isEqualTo(REASON);
        assertThat(values.getValue("now")).isEqualTo(Timestamp.from(NOW));
    }

    @Test
    void writesNoHistoryIfTheGuardedLinkDoesNotChangeExactlyOneProfile() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertCode(() -> service.linkAcceptedAccount(valid(), ACCEPTED_USER, ACCEPTED_MEMBERSHIP, REASON), "STAFF_ACCOUNT_CANDIDATE_CHANGED");
        verify(jdbc).update(contains("UPDATE staff_profiles"), any(SqlParameterSource.class));
        verify(jdbc, never()).update(contains("INSERT INTO staff_profile_history"), any(SqlParameterSource.class));
    }

    @Test
    void refusesMissingAcceptedIdentityMembershipOrNoncanonicalReason() {
        assertThatThrownBy(() -> service.linkAcceptedAccount(null, ACCEPTED_USER, ACCEPTED_MEMBERSHIP, REASON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.linkAcceptedAccount(valid(), null, ACCEPTED_MEMBERSHIP, REASON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.linkAcceptedAccount(valid(), ACCEPTED_USER, null, REASON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.linkAcceptedAccount(valid(), ACCEPTED_USER, ACCEPTED_MEMBERSHIP, "Issuer approved this action"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    private void candidate(StaffAccountCandidate candidate) {
        doReturn(List.of(candidate)).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    private static StaffAccountCandidate valid() {
        return new StaffAccountCandidate(STAFF, ORGANISATION, SCHOOL, "Synthetic test school", "Synthetic test employee",
                "synthetic-staff@example.test", "en", 3, LocalDate.of(2026, 4, 1), null, "ACTIVE", null);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
