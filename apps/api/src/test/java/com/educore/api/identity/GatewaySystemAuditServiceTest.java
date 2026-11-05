package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("try")
class GatewaySystemAuditServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID organisation = UUID.randomUUID();
    private final UUID school = UUID.randomUUID();
    private final UUID capture = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T04:00:00.123456789Z"), ZoneOffset.UTC);
    private final SecurityAuditService audit = new SecurityAuditService(jdbc, mapper, clock);

    @BeforeEach void writableTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @AfterEach void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test void requiresARealWritableTransactionBeforeDatabaseAccess() {
        try (var scope = TenantRequestContext.open(null, organisation)) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test void deniesMissingHumanAndOtherOrganisationContextsBeforeDatabaseAccess() {
        assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
        try (var scope = TenantRequestContext.open(UUID.randomUUID(), organisation)) {
            assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
        }
        try (var scope = TenantRequestContext.open(null, UUID.randomUUID())) {
            assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test void restrictsDispositionAndPaymentPairing() {
        assertThatThrownBy(() -> audit.recordGatewaySystemScoped(organisation, school, capture, null, "REFUND_REQUIRED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.recordGatewaySystemScoped(organisation, school, capture, null, "POSTED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.recordGatewaySystemScoped(organisation, school, capture, payment, "HOLD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.recordGatewaySystemScoped(organisation, null, capture, null, "HOLD"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void deniesDatabaseScopeAndUnpersistedCaptureOrSystemResolution() {
        try (var scope = TenantRequestContext.open(null, organisation)) {
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
            assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
            verify(jdbc, never()).update(anyString(), any(Object[].class));
            reset(jdbc);
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true, false);
            assertThatThrownBy(this::hold).isInstanceOf(IllegalStateException.class);
            verify(jdbc, never()).update(anyString(), any(Object[].class));
            reset(jdbc);
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true, true, false);
            assertThatThrownBy(() -> audit.recordGatewaySystemScoped(organisation, school, capture, payment, "POSTED"))
                    .isInstanceOf(IllegalStateException.class);
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }
    }

    @Test void recordsHeldCaptureWithNullActorAndOnlySafeIdentifiers() throws Exception {
        assertRecorded(null, "HOLD", "finance.gateway.capture.held", "FEE_GATEWAY_CAPTURE", capture);
    }

    @Test void recordsPostedPaymentUsingTheSameOrganisationHashFraming() throws Exception {
        assertRecorded(payment, "POSTED", "finance.gateway.payment.posted", "FEE_PAYMENT", payment);
    }

    private void assertRecorded(UUID paymentId, String disposition, String action, String subjectType, UUID subjectId)
            throws Exception {
        String previousHash = "a".repeat(64);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(), any(Object[].class)))
                .thenReturn(previousHash);
        try (var scope = TenantRequestContext.open(null, organisation)) {
            audit.recordGatewaySystemScoped(organisation, school, capture, paymentId, disposition);
        }
        verify(jdbc).queryForObject("SELECT current_app_organisation_id() = ? AND current_app_user_id() IS NULL",
                Boolean.class, organisation);
        verify(jdbc).queryForObject(contains("FROM fee_gateway_captures"), eq(Boolean.class),
                eq(capture), eq(organisation), eq(school));
        verify(jdbc).queryForObject(contains("FROM fee_gateway_capture_resolutions"), eq(Boolean.class),
                eq(capture), eq(organisation), eq(school), eq(paymentId), eq(disposition));
        verify(jdbc).queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class, "organisation:audit:" + organisation);
        var sql = ArgumentCaptor.forClass(String.class);
        var arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("NULL, NULL");
        Object[] values = arguments.getValue();
        assertThat(values).hasSize(13);
        assertThat(values[1]).isEqualTo(Timestamp.from(Instant.parse("2026-10-10T04:00:00.123456Z")));
        assertThat(values[2]).isEqualTo(organisation);
        assertThat(values[3]).isEqualTo(school);
        assertThat((String) values[4]).startsWith("system-");
        assertThat(values[5]).isEqualTo(action);
        assertThat(values[6]).isEqualTo(subjectType);
        assertThat(values[7]).isEqualTo(subjectId.toString());
        assertThat(values[8]).isEqualTo("SUCCEEDED");
        assertThat(mapper.readValue((String) values[10], Map.class)).isEqualTo(paymentId == null
                ? Map.of("actorKind", "SYSTEM", "component", "GATEWAY_VERIFICATION", "captureId", capture.toString())
                : Map.of("actorKind", "SYSTEM", "component", "GATEWAY_VERIFICATION", "captureId", capture.toString(),
                        "paymentId", paymentId.toString()));
        assertThat(values[11]).isEqualTo(previousHash);
        String canonical = String.join("|", values[0].toString(), ((Timestamp) values[1]).toInstant().toString(),
                organisation.toString(), school.toString(), "", "", values[4].toString(), action, subjectType,
                subjectId.toString(), "SUCCEEDED", values[9].toString(), values[10].toString(), previousHash);
        assertThat(values[12]).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))));
    }

    private void hold() {
        audit.recordGatewaySystemScoped(organisation, school, capture, null, "HOLD");
    }
}
