package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Package-private complete, unfiltered, immutable selected EVENTS chain. No cursors escape to clients. */
final class GatewaySettlementSourceReviewEvidence {
    private GatewaySettlementSourceReviewEvidence() {}

    record Page(UUID readId, GatewaySettlementReadStore.Prepared query, String cursorHash,
            String nextCursorHash, Instant observedAt, List<GatewaySettlementProvider.SettlementEvent> events) {
        Page { events = List.copyOf(events); }
        @Override public String toString() { return "Page[private immutable selected settlement evidence]"; }
    }

    record Chain(UUID organisationId, UUID schoolId, GatewaySettlementModels.Request request,
            GatewayWorkQueue.ProviderBinding binding, List<Page> pages) {
        Chain {
            pages = List.copyOf(pages);
            if (organisationId == null || schoolId == null || request == null || binding == null
                    || !organisationId.equals(binding.organisationId()) || !schoolId.equals(binding.schoolId())
                    || !"EVENTS".equals(request.kind()) || !"COMPLETE".equals(request.status())
                    || request.id() == null || request.sourceSummaryReadId() == null || request.settlementReference() == null
                    || request.from() != null || request.until() != null || request.pagesPending() != 0 || request.pagesReview() != 0
                    || pages.isEmpty() || pages.size() > 1000 || pages.size() != request.pagesCompleted()) throw unavailable();
            var reads = new HashSet<UUID>(); var queries = new HashSet<UUID>(); var cursors = new HashSet<String>();
            Page previous = null;
            for (int index = 0; index < pages.size(); index++) {
                Page page = pages.get(index);
                if (page == null || page.query() == null || page.readId() == null || !reads.add(page.readId())
                        || page.query().id() == null || !queries.add(page.query().id()) || !binding.equals(page.query().binding())
                        || page.query().kind() != GatewaySettlementReadStore.Kind.EVENTS
                        || !request.id().equals(page.query().rootQueryId()) || page.query().pageNumber() != index + 1
                        || !request.sourceSummaryReadId().equals(page.query().summaryReadId())
                        || !request.settlementReference().equals(page.query().settlementReference())
                        || page.query().from() != null || page.query().until() != null || page.query().cursor() != null
                        || page.observedAt() == null || page.query().preparedAt() == null
                        || page.observedAt().isBefore(page.query().preparedAt())
                        || page.events().size() > GatewaySettlementProvider.PAGE_SIZE
                        || !hash(page.cursorHash()) || !hash(page.nextCursorHash())) throw unavailable();
                if (index == 0) {
                    if (!request.id().equals(page.query().id()) || page.query().parentReadId() != null || page.cursorHash() != null) throw unavailable();
                } else if (!Objects.equals(page.query().parentReadId(), previous.readId()) || previous.nextCursorHash() == null
                        || !Objects.equals(page.cursorHash(), previous.nextCursorHash()) || !cursors.add(page.cursorHash())
                        || page.query().preparedAt().isBefore(previous.observedAt())) throw unavailable();
                if ((index == pages.size() - 1) != (page.nextCursorHash() == null)
                        || page.nextCursorHash() != null && cursors.contains(page.nextCursorHash())) throw unavailable();
                if (page.events().stream().anyMatch(event -> event == null
                        || !request.settlementReference().equals(event.settlementReference())
                        || event.occurredAt().isAfter(page.observedAt()))) throw unavailable();
                previous = page;
            }
        }
        @Override public String toString() { return "Chain[private complete selected EVENTS evidence; not reconciliation]"; }
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE",
                "A complete selected event-report chain is required for source review; no bank or posting decision is implied.");
    }
    private static boolean hash(String value) { return value == null || value.matches("[a-f0-9]{64}"); }
}
