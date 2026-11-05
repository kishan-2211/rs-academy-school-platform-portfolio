package com.educore.api.finance;

import static com.educore.api.finance.GatewaySettlementSourceReviewFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Pure/mock chain verification; real selected-chain integrity/RLS remains a PostgreSQL release gate. */
class GatewaySettlementSourceReviewEvidenceTest {
    @AfterEach void clearSyntheticThreadMarkers() {
        TransactionSynchronizationManager.clear(); assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void fullUnfilteredBoundedCanonicalChainIsReturnedWithoutProviderRegistryCredentialsOrWrites() {
        var f = new Fixture(); f.use(f.pure.chain(List.of(List.of(payment("synthetic_one", "synthetic_p1")), List.of(payment("synthetic_two", "synthetic_p2")))));
        var chain = f.run(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request));
        assertThat(chain.pages()).hasSize(2); assertThat(chain.pages().getFirst().events().getFirst().reportedAmount().toPlainString()).isEqualTo("100.00000000");
        assertThat(f.reads).anySatisfy(sql -> assertThat(sql).contains("human-settlement-live-scope", "IN('fees.read','accounting.read')", ")=2"));
        assertThat(f.reads).anySatisfy(sql -> assertThat(sql).contains("WHERE request_id=:requestId ORDER BY page_number,query_id LIMIT 1001")
                .doesNotContain("ILIKE", "jsonb_array_elements", "OFFSET", "FOR UPDATE"));
        assertThatThrownBy(() -> chain.pages().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class)); verifyNoInteractions(f.registry, f.audit);
    }

    @Test void unauthenticatedSystemMismatchingTenantAndMissingTransactionAreDeniedBeforeJdbc() {
        var f = new Fixture(); denied(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var ignored = TenantRequestContext.open(null, f.pure.org)) { denied(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); }
        try (var ignored = TenantRequestContext.open(f.user.userId(), UUID.randomUUID())) { denied(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); }
        try (var ignored = TenantRequestContext.open(f.user.userId(), f.pure.org)) { denied(() -> f.service.completeEvents(null, f.pure.school, f.pure.request)); }
        verifyNoInteractions(f.jdbc, f.registry, f.audit);
    }

    @Test void revokedOrForeignLiveDatabaseAuthorityIsDeniedNotConvertedToAnEmptyReview() {
        var f = new Fixture(); f.databaseScope = false; f.run(() -> { denied(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
        f.databaseScope = true; f.authorized = false; f.run(() -> { denied(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
        assertThat(f.reads).noneMatch(sql -> sql.contains("selected_pages") || sql.contains("WITH request_state"));
    }

    @Test void missingRequestIsNotFoundWhileSummaryIncompleteCappedOrReviewRequiredRequestsFailClosed() {
        var f = new Fixture(); f.requestExists = false;
        f.run(() -> { code(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request), "GATEWAY_SETTLEMENT_NOT_FOUND"); return null; });
        f.requestExists = true;
        for (var changed : List.of(Map.entry("query_kind", "SUMMARY"), Map.entry("derived_status", "SYNCING"),
                Map.entry("derived_status", "REVIEW_REQUIRED"), Map.entry("pages_pending", 1), Map.entry("pages_review", 1),
                Map.entry("pages_completed", 0), Map.entry("pages_completed", 1001))) {
            f.use(f.pure.chain(List.of(List.of()))); f.request.put(changed.getKey(), changed.getValue());
            f.run(() -> { unavailable(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
        }
        assertThat(f.reads).noneMatch(sql -> sql.contains("selected_pages"));
    }

    @Test void canonicalFactsAndFrozenBindingHashMustVerifyBeforeAnyChainCanEscape() {
        var f = new Fixture(); f.pages.getFirst().put("evidence_sha256", "0".repeat(64));
        f.run(() -> { unavailable(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
        f.use(f.pure.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))));
        f.pages.getFirst().put("merchant_account_reference", "synthetic_foreign_merchant");
        f.run(() -> { unavailable(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
        f.use(f.pure.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))));
        var retained = f.chain.pages().getFirst(); String noncanonical = " " + f.pages.getFirst().get("facts_canonical");
        f.pages.getFirst().put("facts_canonical", noncanonical);
        f.pages.getFirst().put("evidence_sha256", GatewaySettlementReadStore.fingerprintFromHashes(retained.query(), retained.readId(), noncanonical, null, null));
        f.run(() -> { unavailable(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
    }

    @Test void missingSelectedPageCannotBecomePartialSuccessEvenIfTheRequestHeaderSaysComplete() {
        var f = new Fixture(); f.pages.clear();
        f.run(() -> { unavailable(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
        f.use(f.pure.chain(List.of(List.of(), List.of()))); f.pages.removeLast();
        f.run(() -> { unavailable(() -> f.service.completeEvents(f.user, f.pure.school, f.pure.request)); return null; });
    }

    @Test void forgedParentCursorPageOrderAndNonterminalFinalPageCannotBecomeCompleteEvidence() {
        var f = new GatewaySettlementSourceReviewFixtures(); var chain = f.chain(List.of(List.of(), List.of()));
        var first = chain.pages().getFirst(); var second = chain.pages().getLast(); var q = second.query();
        unavailable(() -> withPages(chain, List.of(second, first)));
        unavailable(() -> withPages(chain, List.of(first, new GatewaySettlementSourceReviewEvidence.Page(second.readId(),
                query(q, q.binding(), 2, UUID.randomUUID(), q.summaryReadId()), second.cursorHash(), null, second.observedAt(), second.events()))));
        unavailable(() -> withPages(chain, List.of(first, new GatewaySettlementSourceReviewEvidence.Page(second.readId(), q,
                hash(9), null, second.observedAt(), second.events()))));
        unavailable(() -> withPages(chain, List.of(first, new GatewaySettlementSourceReviewEvidence.Page(second.readId(), q,
                second.cursorHash(), hash(2), second.observedAt(), second.events()))));
    }

    @Test void duplicateReadsQueriesCursorCyclesAndSkippedPagesAreRejected() {
        var f = new GatewaySettlementSourceReviewFixtures(); var chain = f.chain(List.of(List.of(), List.of()));
        var first = chain.pages().getFirst(); var second = chain.pages().getLast(); var q = second.query();
        unavailable(() -> withPages(chain, List.of(first, new GatewaySettlementSourceReviewEvidence.Page(first.readId(), q,
                second.cursorHash(), null, second.observedAt(), second.events()))));
        var duplicate = new GatewaySettlementReadStore.Prepared(first.query().id(), q.binding(), q.kind(), q.rootQueryId(), 2,
                first.readId(), q.summaryReadId(), null, null, q.settlementReference(), null, q.preparedAt());
        unavailable(() -> withPages(chain, List.of(first, new GatewaySettlementSourceReviewEvidence.Page(second.readId(), duplicate,
                second.cursorHash(), null, second.observedAt(), second.events()))));
        unavailable(() -> withPages(chain, List.of(first, new GatewaySettlementSourceReviewEvidence.Page(second.readId(),
                query(q, q.binding(), 3, q.parentReadId(), q.summaryReadId()), second.cursorHash(), null, second.observedAt(), second.events()))));
        var three = f.chain(List.of(List.of(), List.of(), List.of())); var p2 = three.pages().get(1);
        unavailable(() -> withPages(three, List.of(three.pages().getFirst(), new GatewaySettlementSourceReviewEvidence.Page(p2.readId(),
                p2.query(), p2.cursorHash(), p2.cursorHash(), p2.observedAt(), p2.events()), three.pages().getLast())));
    }

    @Test void aDifferentProviderCredentialSchoolOrSummaryPageCannotJoinTheSelectedChain() {
        var f = new GatewaySettlementSourceReviewFixtures(); var chain = f.chain(List.of(List.of(), List.of()));
        var second = chain.pages().getLast(); var q = second.query(); var b = q.binding();
        var foreign = new GatewayWorkQueue.ProviderBinding(b.organisationId(), b.schoolId(), b.providerConfigId(), b.providerCode(),
                b.environment(), b.merchantReference(), "synthetic:rotated-label");
        unavailable(() -> withPages(chain, List.of(chain.pages().getFirst(), new GatewaySettlementSourceReviewEvidence.Page(second.readId(),
                query(q, foreign, 2, q.parentReadId(), q.summaryReadId()), second.cursorHash(), null, second.observedAt(), second.events()))));
        unavailable(() -> withPages(chain, List.of(chain.pages().getFirst(), new GatewaySettlementSourceReviewEvidence.Page(second.readId(),
                query(q, b, 2, q.parentReadId(), UUID.randomUUID()), second.cursorHash(), null, second.observedAt(), second.events()))));
        unavailable(() -> new GatewaySettlementSourceReviewEvidence.Chain(f.org, UUID.randomUUID(), chain.request(), b, chain.pages()));
    }

    @Test void factsMustBelongToSelectedStatementAndNotOccurAfterTheRetainedObservation() {
        var f = new GatewaySettlementSourceReviewFixtures(); var chain = f.chain(List.of(List.of())); var page = chain.pages().getFirst();
        var valid = payment("synthetic_event", "synthetic_payment");
        var foreign = new GatewaySettlementProvider.SettlementEvent("synthetic_other_statement", valid.eventReference(), valid.reportedType(),
                valid.reportedDirection(), valid.reportedStatus(), valid.currencyCode(), valid.reportedAmount(), null, null, null, TIME, valid.orderReference(), valid.paymentReference(), List.of(), null);
        unavailable(() -> withPages(chain, List.of(new GatewaySettlementSourceReviewEvidence.Page(page.readId(), page.query(), null, null,
                page.observedAt(), List.of(foreign)))));
        unavailable(() -> withPages(chain, List.of(new GatewaySettlementSourceReviewEvidence.Page(page.readId(), page.query(), null, null,
                TIME.minusSeconds(1), List.of(valid)))));
    }

    private static GatewaySettlementReadStore.Prepared query(GatewaySettlementReadStore.Prepared q, GatewayWorkQueue.ProviderBinding b,
            int page, UUID parent, UUID summary) {
        return new GatewaySettlementReadStore.Prepared(q.id(), b, q.kind(), q.rootQueryId(), page, parent, summary, null, null,
                q.settlementReference(), null, q.preparedAt());
    }
    private static GatewaySettlementSourceReviewEvidence.Chain withPages(GatewaySettlementSourceReviewEvidence.Chain chain,
            List<GatewaySettlementSourceReviewEvidence.Page> pages) {
        return new GatewaySettlementSourceReviewEvidence.Chain(chain.organisationId(), chain.schoolId(), chain.request(), chain.binding(), pages);
    }
    private static void denied(Runnable action) { code(action, "GATEWAY_SETTLEMENT_SCOPE_DENIED"); }
    private static void unavailable(Runnable action) { code(action, "GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE"); }
    private static void code(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static final class Fixture {
        final GatewaySettlementSourceReviewFixtures pure = new GatewaySettlementSourceReviewFixtures();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class); final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
        final SecurityAuditService audit = mock(SecurityAuditService.class); final GatewaySettlementService service = new GatewaySettlementService(jdbc, registry, audit);
        final UUID member = UUID.randomUUID(); final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(),
                "synthetic@example.test", null, "Synthetic", false, pure.org, Set.of(member), Set.of(), List.of());
        final List<String> reads = new ArrayList<>(); final List<Map<String, Object>> pages = new ArrayList<>();
        Map<String, Object> request; GatewaySettlementSourceReviewEvidence.Chain chain; boolean databaseScope = true, authorized = true, requestExists = true;
        @SuppressWarnings({"unchecked", "rawtypes"}) Fixture() {
            use(pure.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))));
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> databaseScope);
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0); reads.add(sql); List<Map<String, Object>> source;
                if (sql.contains("human-settlement-live-scope")) source = authorized ? List.of(Map.of("membership_id", member, "time_zone", "Asia/Kolkata", "can_request", false)) : List.of();
                else if (sql.contains("selected_pages")) source = pages; else source = requestExists ? List.of(request) : List.of();
                var result = new ArrayList<>(); RowMapper mapper = call.getArgument(2);
                for (int index = 0; index < source.size(); index++) {
                    var values = source.get(index); var rs = mock(ResultSet.class);
                    when(rs.getObject(anyString(), any(Class.class))).thenAnswer(value -> values.get(value.getArgument(0, String.class)));
                    when(rs.getString(anyString())).thenAnswer(value -> values.get(value.getArgument(0, String.class)));
                    when(rs.getInt(anyString())).thenAnswer(value -> ((Number) values.getOrDefault(value.getArgument(0, String.class), 0)).intValue());
                    when(rs.getBoolean(anyString())).thenAnswer(value -> Boolean.TRUE.equals(values.get(value.getArgument(0, String.class))));
                    result.add(mapper.mapRow(rs, index));
                }
                return result;
            });
        }
        void use(GatewaySettlementSourceReviewEvidence.Chain selected) {
            chain = selected; pages.clear(); var r = selected.request(); request = new HashMap<>();
            request.put("id", r.id()); request.put("query_kind", r.kind()); request.put("school_time_zone", r.schoolTimeZone());
            request.put("derived_status", r.status()); request.put("source_summary_read_id", r.sourceSummaryReadId());
            request.put("settlement_reference", r.settlementReference()); request.put("requested_at", TIME.atOffset(ZoneOffset.UTC));
            request.put("requested_by", "Synthetic"); request.put("pages_completed", r.pagesCompleted()); request.put("pages_pending", 0); request.put("pages_review", 0);
            for (var page : selected.pages()) {
                var q = page.query(); var b = q.binding(); var row = new HashMap<String, Object>();
                row.put("query_id", q.id()); row.put("organisation_id", b.organisationId()); row.put("school_id", b.schoolId());
                row.put("payment_provider_config_id", b.providerConfigId()); row.put("provider_code", b.providerCode()); row.put("provider_environment", b.environment());
                row.put("merchant_account_reference", b.merchantReference()); row.put("credentials_secret_reference", b.credentialsReference());
                row.put("query_kind", q.kind().name()); row.put("root_query_id", q.rootQueryId()); row.put("page_number", q.pageNumber());
                row.put("parent_read_id", q.parentReadId()); row.put("summary_read_id", q.summaryReadId()); row.put("settlement_reference", q.settlementReference());
                row.put("cursor_sha256", page.cursorHash()); row.put("next_cursor_sha256", page.nextCursorHash()); row.put("prepared_at", q.preparedAt().atOffset(ZoneOffset.UTC));
                row.put("read_id", page.readId()); row.put("read_outcome", "COMPLETE"); row.put("observed_at", page.observedAt().atOffset(ZoneOffset.UTC));
                String facts = GatewaySettlementFacts.events(page.events()); row.put("facts_canonical", facts);
                row.put("evidence_sha256", GatewaySettlementReadStore.fingerprintFromHashes(q, page.readId(), facts, page.cursorHash(), page.nextCursorHash())); pages.add(row);
            }
        }
        <T> T run(Supplier<T> action) {
            TransactionSynchronizationManager.setActualTransactionActive(true); TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try (var ignored = TenantRequestContext.open(user.userId(), pure.org)) { return action.get(); }
            finally { TransactionSynchronizationManager.clear(); }
        }
    }
}
