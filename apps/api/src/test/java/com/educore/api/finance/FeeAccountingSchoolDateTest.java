package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class FeeAccountingSchoolDateTest {
    @Test
    void indianMidnightAcceptsTheNewSchoolDayBeforeTheUtcDateChanges() throws Exception {
        var f = new Fixture("Asia/Kolkata", "2026-10-18T18:30:00Z", ZoneOffset.UTC);
        f.install();
        assertThat(f.prepare(LocalDate.of(2026, 10, 19)))
                .isEqualTo(new FinanceMutationResponse(f.batch, 1));
        assertThat(f.reads).hasSize(2);
        assertNoFinancialMutation(f);
    }

    @Test
    void tomorrowStillFailsAtIndianMidnightBeforeAnyBatchLookupOrFinancialWork() throws Exception {
        var f = new Fixture("Asia/Kolkata", "2026-10-18T18:30:00Z", ZoneOffset.UTC);
        f.install();
        assertDenied(() -> f.prepare(LocalDate.of(2026, 10, 20)),
                "FUTURE_FEE_POSTING_DATE", HttpStatus.BAD_REQUEST);
        assertThat(f.reads).hasSize(1);
        assertNoFinancialMutation(f);
    }

    @Test
    void theInstantBeforeIndianMidnightDoesNotPrematurelyOpenTomorrow() throws Exception {
        var f = new Fixture("Asia/Kolkata", "2026-10-18T18:29:59.999999999Z", ZoneOffset.UTC);
        f.install();
        assertDenied(() -> f.prepare(LocalDate.of(2026, 10, 19)),
                "FUTURE_FEE_POSTING_DATE", HttpStatus.BAD_REQUEST);
        assertThat(f.prepare(LocalDate.of(2026, 10, 18)))
                .isEqualTo(new FinanceMutationResponse(f.batch, 1));
        assertNoFinancialMutation(f);
    }

    @Test
    void aNegativeOffsetSchoolKeepsItsPreviousDayEvenAfterUtcMidnight() throws Exception {
        var f = new Fixture("America/New_York", "2026-10-19T00:30:00Z", ZoneOffset.UTC);
        f.install();
        assertDenied(() -> f.prepare(LocalDate.of(2026, 10, 19)),
                "FUTURE_FEE_POSTING_DATE", HttpStatus.BAD_REQUEST);
        assertThat(f.prepare(LocalDate.of(2026, 10, 18)))
                .isEqualTo(new FinanceMutationResponse(f.batch, 1));
        assertNoFinancialMutation(f);
    }

    @Test
    void theConfiguredSchoolZoneWinsOverTheHostClockZone() throws Exception {
        var india = new Fixture("Asia/Kolkata", "2026-10-18T18:30:00Z", ZoneId.of("America/Los_Angeles"));
        india.install();
        assertThat(india.prepare(LocalDate.of(2026, 10, 19)))
                .isEqualTo(new FinanceMutationResponse(india.batch, 1));
        var hawaii = new Fixture("Pacific/Honolulu", "2026-10-19T00:30:00Z", ZoneOffset.ofHours(14));
        hawaii.install();
        assertDenied(() -> hawaii.prepare(LocalDate.of(2026, 10, 19)),
                "FUTURE_FEE_POSTING_DATE", HttpStatus.BAD_REQUEST);
        assertThat(hawaii.prepare(LocalDate.of(2026, 10, 18)))
                .isEqualTo(new FinanceMutationResponse(hawaii.batch, 1));
        assertNoFinancialMutation(india);
        assertNoFinancialMutation(hawaii);
    }

    @Test
    void existingFixedOffsetSchoolZonesRetainJavaIsoOffsetSemantics() throws Exception {
        var east = new Fixture("UTC+05:30", "2026-10-18T18:30:00Z", ZoneOffset.UTC);
        east.install();
        assertThat(east.prepare(LocalDate.of(2026, 10, 19)))
                .isEqualTo(new FinanceMutationResponse(east.batch, 1));
        var west = new Fixture("-05:00", "2026-10-19T00:30:00Z", ZoneOffset.UTC);
        west.install();
        assertDenied(() -> west.prepare(LocalDate.of(2026, 10, 19)),
                "FUTURE_FEE_POSTING_DATE", HttpStatus.BAD_REQUEST);
        assertThat(west.prepare(LocalDate.of(2026, 10, 18)))
                .isEqualTo(new FinanceMutationResponse(west.batch, 1));
        assertNoFinancialMutation(east);
        assertNoFinancialMutation(west);
    }

    @Test
    void absentOrMalformedConfigurationFailsClosedWithoutUtcFallbackOrLeakingTheValue() throws Exception {
        for (String zone : Arrays.asList(null, "", " ", "\n", "Synthetic/Unknown", "Asia/Kolkata ",
                "UTC; SELECT private_configuration", "A".repeat(61))) {
            var f = new Fixture(zone, "2026-10-18T18:30:00Z", ZoneOffset.UTC);
            f.install();
            assertThatThrownBy(() -> f.prepare(LocalDate.of(2026, 10, 18)))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.code()).isEqualTo("FEE_ACCOUNTING_TIME_ZONE_INVALID");
                        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(error.getMessage()).isEqualTo(
                                "Configure a valid school time zone before preparing fee accounting.");
                    });
            assertThat(f.reads).hasSize(1);
            assertNoFinancialMutation(f);
        }
    }

    @Test
    void revokedOrForeignScopeDeniesBeforeTimeZoneValidationAndKeepsExistingLivePredicates() throws Exception {
        var f = new Fixture("Synthetic/Unknown", "2026-10-18T18:30:00Z", ZoneOffset.UTC);
        f.install();
        f.authorized = false;
        assertDenied(() -> f.prepare(LocalDate.of(2026, 10, 18)),
                "FEE_ACCOUNTING_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        f.authorized = true;
        assertDenied(() -> f.service.preparePosting(f.user, "synthetic-school-date-retry",
                new FeeAccountingController.PostingRequest(UUID.randomUUID(), f.year, LocalDate.of(2026, 10, 18),
                        "PAYMENT", 200, "Synthetic unchanged fee posting retry")),
                "FEE_ACCOUNTING_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        assertThat(f.reads).hasSize(2);
        var scope = f.reads.getFirst();
        assertThat(scope.sql()).contains("school.time_zone", "membership.user_id=:userId",
                "membership.organisation_id=:organisationId", "membership.id IN (:membershipIds)",
                "membership.status='ACTIVE'", "membership.valid_from<=CURRENT_TIMESTAMP",
                "membership.valid_until>CURRENT_TIMESTAMP", "membership.school_id=:schoolId",
                "rc.capability_code=:capability");
        assertThat(scope.parameters()).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school)
                .containsEntry("userId", f.userId).containsEntry("membershipIds", Set.of(f.membership))
                .containsEntry("capability", "fees.close.propose");
        assertNoFinancialMutation(f);
    }

    @Test
    void missingOrganisationSelectionFailsBeforeAnySchoolOrBatchLookup() {
        var f = new Fixture("Asia/Kolkata", "2026-10-18T18:30:00Z", ZoneOffset.UTC);
        var noOrganisation = new AuthenticatedUser(f.userId, UUID.randomUUID(), "synthetic@example.test", null,
                "Synthetic actor", true, null, Set.of(f.membership), Set.of("fees.close.propose"), List.of());
        assertDenied(() -> f.service.preparePosting(noOrganisation, "synthetic-school-date-retry",
                new FeeAccountingController.PostingRequest(f.school, f.year, LocalDate.of(2026, 10, 19),
                        "PAYMENT", 200, "Synthetic unchanged fee posting retry")),
                "ORGANISATION_SELECTION_REQUIRED", HttpStatus.CONFLICT);
        verifyNoInteractions(f.jdbc, f.accounting, f.audit);
    }

    private static void assertDenied(Runnable action, String code, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.status()).isEqualTo(status);
        });
    }

    private static void assertNoFinancialMutation(Fixture f) {
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verify(f.jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class));
        verifyNoInteractions(f.accounting, f.audit);
    }

    private record Read(String sql, Map<String, Object> parameters) {}

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), membership = UUID.randomUUID();
        final UUID userId = UUID.randomUUID(), year = UUID.randomUUID(), batch = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final AccountingModuleBridge accounting = mock(AccountingModuleBridge.class);
        final SecurityAuditService audit = mock(SecurityAuditService.class);
        final AuthenticatedUser user = new AuthenticatedUser(userId, UUID.randomUUID(), "synthetic@example.test", null,
                "Synthetic actor", true, org, Set.of(membership), Set.of("fees.close.propose"), List.of());
        final FeeAccountingService service;
        final String configuredZone;
        final List<Read> reads = new ArrayList<>();
        FeeAccountingController.PostingRequest currentRequest;
        boolean authorized = true;

        Fixture(String configuredZone, String instant, ZoneId hostZone) {
            this.configuredZone = configuredZone;
            service = new FeeAccountingService(jdbc, accounting, audit, JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse(instant), hostZone));
        }

        FinanceMutationResponse prepare(LocalDate businessDate) {
            currentRequest = new FeeAccountingController.PostingRequest(school, year, businessDate,
                    "PAYMENT", 200, "Synthetic unchanged fee posting retry");
            return service.preparePosting(user, "synthetic-school-date-retry", currentRequest);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void install() throws Exception {
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var parameters = call.<MapSqlParameterSource>getArgument(1);
                reads.add(new Read(sql, new LinkedHashMap<>(parameters.getValues())));
                var value = new HashMap<String, Object>();
                if (sql.contains("FROM memberships membership")) {
                    UUID requestedSchool = (UUID) parameters.getValue("schoolId");
                    if (!authorized || (requestedSchool != null && !school.equals(requestedSchool))) return List.of();
                    value.put("school_id", school);
                    value.put("display_name", "Synthetic school");
                    value.put("membership_id", membership);
                    value.put("time_zone", configuredZone);
                } else if (sql.contains("FROM fee_accounting_posting_batches")) {
                    value.put("id", batch);
                    String fingerprint = school + "|" + year + "|" + currentRequest.businessDate()
                            + "|PAYMENT|200|Synthetic unchanged fee posting retry";
                    value.put("request_fingerprint", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(fingerprint.getBytes(StandardCharsets.UTF_8))));
                    value.put("row_version", 1L);
                } else {
                    throw new AssertionError("The focused date/retry scenario must not reach unrelated financial work.");
                }
                RowMapper mapper = call.getArgument(2);
                return List.of(mapper.mapRow(row(value), 0));
            });
        }

        private static ResultSet row(Map<String, Object> values) throws Exception {
            var result = mock(ResultSet.class);
            when(result.getObject(anyString(), any(Class.class))).thenAnswer(read -> values.get(read.<String>getArgument(0)));
            when(result.getString(anyString())).thenAnswer(read -> (String) values.get(read.<String>getArgument(0)));
            when(result.getBoolean(anyString())).thenAnswer(read -> Boolean.TRUE.equals(values.get(read.<String>getArgument(0))));
            when(result.getLong(anyString())).thenAnswer(read -> ((Number) values.get(read.<String>getArgument(0))).longValue());
            return result;
        }
    }
}
