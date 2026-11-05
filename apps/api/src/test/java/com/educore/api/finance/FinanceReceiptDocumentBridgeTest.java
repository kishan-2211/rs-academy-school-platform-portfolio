package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.educore.api.tenancy.TenantRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class FinanceReceiptDocumentBridgeTest {
    @Test
    void rejectsMissingScopeBeforeReadingAnyFinancialEvidence() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var bridge = new FinanceReceiptDocumentBridge(jdbc);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> bridge.receiptTiming(null, id, id, id, id)).hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> bridge.receiptTiming(id, null, id, id, id)).hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> bridge.receiptTiming(id, id, null, id, id)).hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> bridge.receiptTiming(id, id, id, null, id)).hasMessageContaining("authorized school work");
        assertThatThrownBy(() -> bridge.receiptTiming(id, id, id, id, null)).hasMessageContaining("authorized school work");
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsOnlyImmutableTimingWithExactTenantSchoolPaymentAndLiveDocumentActorScope() throws Exception {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID user = UUID.randomUUID(), organisation = UUID.randomUUID(), school = UUID.randomUUID(), payment = UUID.randomUUID(), actor = UUID.randomUUID();
        Instant received = Instant.parse("2026-09-10T03:05:07.123Z");
        var result = mock(ResultSet.class);
        when(result.getObject("posting_on", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 11));
        when(result.getObject("original_received_on", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 10));
        when(result.getObject("actual_received_at", java.time.OffsetDateTime.class)).thenReturn(received.atOffset(java.time.ZoneOffset.UTC));
        when(result.getString("received_time_precision")).thenReturn("INSTANT");
        when(result.getString("received_time_zone")).thenReturn("Asia/Kolkata");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            MapSqlParameterSource scope = invocation.getArgument(1);
            assertThat(scope.getValue("organisationId")).isEqualTo(organisation);
            assertThat(scope.getValue("schoolId")).isEqualTo(school);
            assertThat(scope.getValue("paymentId")).isEqualTo(payment);
            assertThat(scope.getValue("actorMembershipId")).isEqualTo(actor);
            assertThat(scope.getValue("actorUserId")).isEqualTo(user);
            assertThat(sql).contains("payment.organisation_id=:organisationId", "payment.school_id=:schoolId",
                    "payment.id=:paymentId", "payment.status='POSTED'", "member.id=:actorMembershipId",
                    "member.user_id=:actorUserId", "current_app_user_id()=:actorUserId",
                    "current_app_organisation_id()=:organisationId",
                    "member.status='ACTIVE'", "actor.status='ACTIVE'", "school.status='ACTIVE'",
                    "organisation.status='ACTIVE'", "clock_timestamp()", "grant_record.capability_code='fees.read'",
                    "('documents.issue','documents.approve')", "role.organisation_id=member.organisation_id",
                    "capture.paid_at", "capture.paid_at_exact AS actual_received_at_exact", "capture.original_business_date", "manual.actual_received_at",
                    "manual.received_time_precision", "manual.school_time_zone,attempt.school_time_zone");
            assertThat(sql).doesNotContain("bank_credit_reference", "external_reference", "review_reason", "verified_by", "created_at");
            RowMapper<FinanceReceiptDocumentBridge.ReceiptTiming> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(result, 0));
        });
        try (var ignored = TenantRequestContext.open(user, organisation)) {
            var timing = new FinanceReceiptDocumentBridge(jdbc).receiptTiming(user, organisation, school, payment, actor);
            assertThat(timing.postingOn()).isEqualTo(LocalDate.of(2026, 9, 11));
            assertThat(timing.originalReceivedOn()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(timing.actualReceivedAt()).isEqualTo(received);
            assertThat(timing.receivedTimePrecision()).isEqualTo("INSTANT");
            assertThat(timing.schoolTimeZone()).isEqualTo("Asia/Kolkata");
        }
    }

    @Test
    void newGatewayReceiptUsesFullSourceBeforeTheSchoolMidnightWithoutChangingItsPostingDate() throws Exception {
        String exact = "2026-09-10T18:29:59.999999500Z";
        var timing = mappedTiming(exact, Instant.parse("2026-09-10T18:29:59.999999Z"));
        assertThat(timing.actualReceivedAt()).isEqualTo(Instant.parse(exact));
        assertThat(timing.originalReceivedOn()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(timing.postingOn()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    void legacyGatewayTimingRetainsOnlyItsKnownStoredPrecision() throws Exception {
        Instant legacy = Instant.parse("2026-09-10T18:29:59.999999Z");
        assertThat(mappedTiming(null, legacy).actualReceivedAt()).isEqualTo(legacy);
    }

    @Test
    void inconsistentOrMalformedExactReceiptSourceIsRejectedInsteadOfReturningRoundedOrPrivateEvidence() {
        assertThatThrownBy(() -> mappedTiming("2026-09-10T18:29:59.999999500Z", Instant.parse("2026-09-10T18:30:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("matching microsecond projection").hasNoCause();
        assertThatThrownBy(() -> mappedTiming("synthetic-private-provider-payload", Instant.parse("2026-09-10T18:29:59.999999Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("synthetic-private-provider-payload").hasNoCause();
        assertThatThrownBy(() -> mappedTiming("2026-09-10T18:29:59.999999500Z", null))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @SuppressWarnings("unchecked")
    private FinanceReceiptDocumentBridge.ReceiptTiming mappedTiming(String exact, Instant projected) throws Exception {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var result = mock(ResultSet.class);
        when(result.getObject("posting_on", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 11));
        when(result.getObject("original_received_on", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 10));
        when(result.getObject("actual_received_at", java.time.OffsetDateTime.class)).thenReturn(projected == null ? null : projected.atOffset(java.time.ZoneOffset.UTC));
        when(result.getString("actual_received_at_exact")).thenReturn(exact);
        when(result.getString("received_time_precision")).thenReturn("INSTANT");
        when(result.getString("received_time_zone")).thenReturn("Asia/Kolkata");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<FinanceReceiptDocumentBridge.ReceiptTiming> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(result, 0));
        });
        UUID user = UUID.randomUUID(), organisation = UUID.randomUUID(), id = UUID.randomUUID();
        try (var ignored = TenantRequestContext.open(user, organisation)) {
            return new FinanceReceiptDocumentBridge(jdbc).receiptTiming(user, organisation, id, id, id);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsNoTimingWhenTheCurrentScopedSourceOrDocumentAuthorityIsUnavailable() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        UUID id = UUID.randomUUID();
        try (var ignored = TenantRequestContext.open(id, id)) {
            assertThatThrownBy(() -> new FinanceReceiptDocumentBridge(jdbc).receiptTiming(id, id, id, id, id))
                    .hasMessageContaining("current fee receipt timing is unavailable");
        }
    }

    @Test
    void dateOnlyHistoricalEvidenceRetainsAnUnknownZoneAndNeverInventsAnInstant() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 22);
        var timing = new FinanceReceiptDocumentBridge.ReceiptTiming(day, day, null, "DATE_ONLY", null);
        assertThat(timing.actualReceivedAt()).isNull();
        assertThat(timing.schoolTimeZone()).isNull();
        assertThat(timing.receivedTimePrecision()).isEqualTo("DATE_ONLY");
        var contract = FinanceReceiptDocumentBridge.class.getMethod("receiptTiming", UUID.class, UUID.class,
                UUID.class, UUID.class, UUID.class).getAnnotation(Transactional.class);
        assertThat(contract.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(contract.readOnly()).isTrue();
    }

    @Test
    void rejectsContradictoryPrecisionDatesAndInvalidStoredZones() {
        LocalDate day = LocalDate.of(2026, 8, 22);
        Instant instant = Instant.parse("2026-08-22T04:31:00Z");
        assertThatThrownBy(() -> new FinanceReceiptDocumentBridge.ReceiptTiming(day, day, null, "INSTANT", "Asia/Kolkata"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinanceReceiptDocumentBridge.ReceiptTiming(day, day, instant, "DATE_ONLY", "Asia/Kolkata"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinanceReceiptDocumentBridge.ReceiptTiming(day, day.plusDays(1), null, "DATE_ONLY", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinanceReceiptDocumentBridge.ReceiptTiming(day, day, instant, "INSTANT", "Not/AZone"))
                .isInstanceOf(java.time.DateTimeException.class);
        assertThatThrownBy(() -> new FinanceReceiptDocumentBridge.ReceiptTiming(day, day,
                Instant.parse("2026-08-22T23:59:59Z"), "INSTANT", "Asia/Kolkata"))
                .hasMessageContaining("instant and received day disagree");
    }

    @Test
    void missingBackgroundWrongUserAndWrongTenantContextsCannotBorrowAnotherDocumentActor() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var bridge = new FinanceReceiptDocumentBridge(jdbc);
        UUID user = UUID.randomUUID(), organisation = UUID.randomUUID(), id = UUID.randomUUID();
        assertThatThrownBy(() -> bridge.receiptTiming(user, organisation, id, id, id)).hasMessageContaining("authorized school work");
        try (var ignored = TenantRequestContext.open(null, organisation)) {
            assertThatThrownBy(() -> bridge.receiptTiming(user, organisation, id, id, id)).hasMessageContaining("authorized school work");
        }
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), organisation)) {
            assertThatThrownBy(() -> bridge.receiptTiming(user, organisation, id, id, id)).hasMessageContaining("authorized school work");
        }
        try (var ignored = TenantRequestContext.open(user, UUID.randomUUID())) {
            assertThatThrownBy(() -> bridge.receiptTiming(user, organisation, id, id, id)).hasMessageContaining("authorized school work");
        }
        verifyNoInteractions(jdbc);
    }
}
