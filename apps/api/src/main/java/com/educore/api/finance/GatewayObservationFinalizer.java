package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal boundary for independently authenticated provider GET results, not an
 * endpoint, provider client, scheduler, human approval or general-ledger posting.
 * A trusted runner must finish its provider I/O before entering this boundary.
 * Minimal facts and exact checkout snapshots commit first; receipt/capture work
 * then runs in a separate short transaction whose LAST write finishes the lease.
 * A lost lease rolls back every financial mutation, not the earlier real facts.
 *
 * All runner GET-fact writers must use this fence-aware boundary, not the queue's
 * low-level retention primitive: V069 append-only facts do not themselves take
 * the finance fence. Both stages fence before row locks/unique inserts so another
 * finalizer cannot hide stronger money between the conflict check and posting.
 */
@Service
public class GatewayObservationFinalizer {
    private final GatewayProviderRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayWorkQueue queue;
    private final GatewayAttemptDispatch dispatch;
    private final FinanceService finance;
    private final GatewayInboxDispatch inboxDispatch;
    private final TransactionTemplate factTransactions;
    private final TransactionTemplate financialTransactions;

    public GatewayObservationFinalizer(GatewayProviderRegistry registry, NamedParameterJdbcTemplate jdbc,
            GatewayWorkQueue queue, GatewayAttemptDispatch dispatch, FinanceService finance,
            PlatformTransactionManager transactionManager) {
        this(registry, jdbc, queue, dispatch, finance, transactionManager,
                new GatewayInboxDispatch(jdbc, registry, dispatch));
    }

    @Autowired
    public GatewayObservationFinalizer(GatewayProviderRegistry registry, NamedParameterJdbcTemplate jdbc,
            GatewayWorkQueue queue, GatewayAttemptDispatch dispatch, FinanceService finance,
            PlatformTransactionManager transactionManager, GatewayInboxDispatch inboxDispatch) {
        this.registry = Objects.requireNonNull(registry);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.queue = Objects.requireNonNull(queue);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.finance = Objects.requireNonNull(finance);
        this.inboxDispatch = Objects.requireNonNull(inboxDispatch);
        this.factTransactions = transactions(Objects.requireNonNull(transactionManager));
        this.financialTransactions = transactions(transactionManager);
    }

    /** Internal outcome only. Never expose a claim, provider payload or payment reference through an HTTP DTO. */
    public GatewayWorkQueue.Outcome finalizeObservation(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery) {
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable();
        }
        var binding = trustedBinding(routeKey, claim, discovery);
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            Staged staged = factTransactions.execute(status -> retainFacts(claim, discovery));
            if (staged == null) throw unavailable();
            GatewayWorkQueue.Outcome outcome = financialTransactions.execute(
                    status -> finalizeFinancially(routeKey, claim, discovery, staged));
            return Objects.requireNonNull(outcome);
        } catch (RuntimeException failure) {
            // SQL/adapter/finance exceptions can contain private references. No
            // success acknowledgement or diagnostic payload escapes on failure.
            throw unavailable();
        }
    }

    /**
     * A fresh authenticated inbox lease can recover money after original attempt
     * work is terminal. Only the inbox is finished; its original callback and the
     * attempt's completed work/one-use CREATE marker are never reset or rewritten.
     */
    public GatewayWorkQueue.Outcome finalizeInboxObservation(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery) {
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
        var binding = trustedBinding(routeKey, claim, discovery, GatewayWorkQueue.WorkKind.WEBHOOK);
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            InboxStaged staged = factTransactions.execute(status -> retainInboxFacts(routeKey, claim, discovery));
            if (staged == null) throw unavailable();
            // Preserve independent facts during interrupted GET/shutdown, but
            // do not begin financial completion or consume that inbox lease.
            if (Thread.currentThread().isInterrupted()) throw unavailable();
            return Objects.requireNonNull(financialTransactions.execute(
                    status -> finalizeInboxFinancially(routeKey, claim, discovery, staged)));
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    private InboxStaged retainInboxFacts(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery) {
        var p = scope(claim.binding());
        fence(p, claim.binding());
        var source = inboxDispatch.loadHistorical(routeKey, claim);
        if (!paymentEvent(source.eventType())) throw unavailable();
        Instant now = databaseNow(p);
        if (discovery.observedAt().isAfter(now)) throw unavailable();
        if (source.attempt().isEmpty()) {
            // The only permitted unassociated lookup is this immutable inbox's
            // provider order. No callback amount/payer is used to guess a child.
            if (discovery.orderReads().stream().anyMatch(read -> !source.orderReference().equals(read.orderReference()))) throw unavailable();
            retainPaymentFacts(claim.binding(), discovery, Set.of(), null, now);
            return new InboxStaged(source, null);
        }
        var snapshot = retainFacts(claim, discovery, source.attempt().orElseThrow().attemptId());
        return new InboxStaged(source, snapshot);
    }

    private GatewayWorkQueue.Outcome finalizeInboxFinancially(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery, InboxStaged staged) {
        if (Thread.currentThread().isInterrupted()) throw unavailable();
        trustedBinding(routeKey, claim, discovery, GatewayWorkQueue.WorkKind.WEBHOOK);
        var p = scope(claim.binding());
        fence(p, claim.binding());
        var current = inboxDispatch.loadForReadRecovery(routeKey, claim);
        if (!sameInboxRouting(staged.source(), current) || !paymentEvent(current.eventType())) throw unavailable();
        requireCurrentLease(claim, databaseNow(p));
        if (staged.snapshot() == null || current.attempt().isEmpty()) {
            // Association may become available through a later original-worker
            // GET. That needs a new complete read, never this orphan order fact.
            return finish(claim, GatewayWorkQueue.Outcome.RETRY, "GATEWAY_CALLBACK_ASSOCIATION_UNRESOLVED", p, false);
        }
        var snapshot = current.attempt().orElseThrow();
        var expected = expected(snapshot);
        if (!staged.snapshot().expected().equals(expected)) throw unavailable();
        if (!current.financiallyEnabled()) {
            return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_CALLBACK_POSTING_REVIEW_REQUIRED", p, false);
        }
        var result = GatewayDiscoveryEvidence.classify(expected, discovery);
        if (result.checkout().isPresent() && !callbackMatchesDiscovery(current, discovery)) {
            // Authentic callback routing still cannot choose an unrelated child
            // or order. Known independent money remains in the first transaction.
            return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_CALLBACK_SOURCE_REVIEW_REQUIRED", p);
        }
        return finalizeCapture(claim, discovery, staged.snapshot(), snapshot, p.addValue("attemptId", snapshot.attemptId()));
    }

    private static boolean paymentEvent(String event) {
        return Set.of("PAYMENT_SUCCESS_WEBHOOK", "PAYMENT_FAILED_WEBHOOK", "PAYMENT_USER_DROPPED_WEBHOOK").contains(event);
    }

    private static boolean sameInboxRouting(GatewayInboxDispatch.Admission first, GatewayInboxDispatch.Admission second) {
        return first.eventType().equals(second.eventType()) && Objects.equals(first.orderReference(), second.orderReference())
                && first.paymentReference().equals(second.paymentReference()) && Objects.equals(first.refundReference(), second.refundReference());
    }

    private static boolean callbackMatchesDiscovery(GatewayInboxDispatch.Admission source, GatewayDiscoveryEvidence.Discovery discovery) {
        return discovery.checkout() != null && discovery.checkout().orderReferences().contains(source.orderReference())
                && discovery.orderReads().stream().filter(read -> source.orderReference().equals(read.orderReference()))
                .flatMap(read -> read.payments().stream().flatMap(List::stream))
                .anyMatch(payment -> source.orderReference().equals(payment.orderReference())
                        && source.paymentReference().equals(payment.paymentReference()));
    }

    private static GatewayDiscoveryEvidence.ExpectedAttempt expected(GatewayAttemptDispatch.ReadRecovery snapshot) {
        return new GatewayDiscoveryEvidence.ExpectedAttempt(snapshot.attemptId(), snapshot.accountId(), snapshot.binding(),
                snapshot.amount(), snapshot.currencyCode(), snapshot.createdAt(), snapshot.expiresAt(), snapshot.schoolTimeZone());
    }

    private Staged retainFacts(GatewayWorkQueue.Claim claim, GatewayDiscoveryEvidence.Discovery discovery) {
        return retainFacts(claim, discovery, claim.id());
    }

    private Staged retainFacts(GatewayWorkQueue.Claim claim, GatewayDiscoveryEvidence.Discovery discovery, UUID attemptId) {
        var p = scope(claim.binding()).addValue("attemptId", attemptId);
        fence(p, claim.binding());
        var expected = jdbc.query("""
                SELECT attempt.id,attempt.learner_fee_account_id,attempt.amount,attempt.currency_code,
                  attempt.created_at,attempt.expires_at,attempt.school_time_zone
                FROM fee_gateway_attempt_work work JOIN fee_gateway_attempts attempt
                  ON attempt.id=work.attempt_id AND attempt.organisation_id=work.organisation_id
                  AND attempt.school_id=work.school_id AND attempt.payment_provider_config_id=work.payment_provider_config_id
                  AND attempt.provider_code=work.provider_code AND attempt.provider_environment=work.provider_environment
                  AND attempt.merchant_account_reference=work.merchant_account_reference
                JOIN payment_provider_configs provider
                  ON provider.id=work.payment_provider_config_id AND provider.organisation_id=work.organisation_id
                  AND provider.school_id=work.school_id AND provider.provider_code=work.provider_code
                  AND provider.environment=work.provider_environment
                  AND provider.merchant_account_reference=work.merchant_account_reference
                  AND provider.credentials_secret_reference=work.credentials_secret_reference
                WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode
                  AND work.provider_environment=:environment AND work.merchant_account_reference=:merchant
                  AND work.credentials_secret_reference=:credentialsReference
                FOR SHARE OF attempt,provider
                """, p, row -> row.next() ? new GatewayDiscoveryEvidence.ExpectedAttempt(
                        row.getObject("id", UUID.class), row.getObject("learner_fee_account_id", UUID.class), claim.binding(),
                        row.getBigDecimal("amount"), row.getString("currency_code"), row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant(), row.getString("school_time_zone")) : null);
        // Frozen WORK identity is authoritative even for stale/expired/completed
        // claims. A newly rotated route label cannot be substituted for it. No
        // lease-state/token/time/counter predicate discards genuine old GET facts.
        if (expected == null) throw unavailable();
        Instant now = databaseNow(p);
        if (discovery.observedAt().isAfter(now)) throw unavailable();
        var result = GatewayDiscoveryEvidence.classify(expected, discovery);
        var associatedFacts = Set.copyOf(result.paymentFacts());
        retainPaymentFacts(claim.binding(), discovery, associatedFacts, expected.attemptId(), now);
        // An inactive exact historical binding may retain old money. Financial
        // admission below still requires the existing ACTIVE GET/worker controls.
        if (result.checkout().isEmpty()) return new Staged(expected, result, null, null);
        var checkout = result.checkout().orElseThrow();
        var orders = checkout.orderReferences().stream().sorted().toList();
        String hash = checkoutHash(expected, result, orders);
        p.addValue("proofHash", hash);
        UUID observation = jdbc.query("""
                SELECT id FROM fee_gateway_checkout_observations WHERE organisation_id=:organisationId
                  AND school_id=:schoolId AND attempt_id=:attemptId AND payment_provider_config_id=:providerId
                  AND evidence_sha256=:proofHash
                ORDER BY id LIMIT 1
                """, p, row -> row.next() ? row.getObject("id", UUID.class) : null);
        if (observation == null) {
            observation = UUID.randomUUID();
            int changed = jdbc.update("""
                    INSERT INTO fee_gateway_checkout_observations(id,organisation_id,school_id,attempt_id,
                      payment_provider_config_id,provider_checkout_reference,attempt_reference,amount,currency_code,
                      amount_paid,checkout_status,provider_expires_at,order_references,pending_payment_count,
                      successful_payment_count,discovery_complete,evidence_sha256,observed_at,observed_at_exact)
                    VALUES(:observationId,:organisationId,:schoolId,:attemptId,:providerId,:attemptId,:attemptId,
                      :amount,:currency,:paidAmount,:checkoutStatus,:expiry,
                      ARRAY(SELECT jsonb_array_elements_text(CAST(:ordersJson AS jsonb))),:pending,:successful,
                      :complete,:proofHash,:observedAt,:observedAtExact)
                    """, p.addValue("observationId", observation).addValue("amount", checkout.amount())
                            .addValue("currency", checkout.currencyCode()).addValue("paidAmount", checkout.amountPaid())
                            .addValue("checkoutStatus", checkout.status().name()).addValue("expiry", Timestamp.from(checkout.expiresAt()))
                            .addValue("ordersJson", ordersJson(orders)).addValue("pending", result.pendingPaymentCount())
                            .addValue("successful", result.successfulPaymentCount()).addValue("complete", result.discoveryComplete())
                            .addValue("observedAt", GatewayExactInstant.parameter(result.observedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                            .addValue("observedAtExact", GatewayExactInstant.canonical(result.observedAt())));
            if (changed != 1) throw unavailable();
        }
        return new Staged(expected, result, observation, hash);
    }

    private void retainPaymentFacts(GatewayWorkQueue.ProviderBinding binding, GatewayDiscoveryEvidence.Discovery discovery,
            Set<PaymentProviderAdapter.PaymentObservation> associatedFacts, UUID attemptId, Instant now) {
        for (var read : discovery.orderReads()) {
            if (read.payments().isEmpty()) continue;
            for (var payment : read.payments().get()) {
                try {
                    GatewayWorkQueue.validatePayment(payment, discovery.observedAt());
                    GatewayWorkQueue.validatePayment(payment, now);
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    // Incomplete/invalid metadata cannot satisfy V069 minimal-fact
                    // constraints. Classification stays unresolved/review; do not
                    // invent paidAt or interpret this as proof of no money.
                    continue;
                }
                boolean bound = read.orderReference().equals(payment.orderReference())
                        && associatedFacts.contains(payment);
                // Missing/mismatched checkout or foreign order membership never
                // assigns a sibling's fact to the expected learner/fee attempt.
                queue.retainPaymentObservation(binding, bound ? attemptId : null, payment);
            }
        }
    }

    private GatewayWorkQueue.Outcome finalizeFinancially(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery, Staged staged) {
        trustedBinding(routeKey, claim, discovery);
        var p = scope(claim.binding()).addValue("attemptId", claim.id()).addValue("leaseToken", claim.token())
                .addValue("leaseStart", Timestamp.from(claim.startedAt())).addValue("leaseUntil", Timestamp.from(claim.leaseUntil()))
                .addValue("attemptNumber", claim.attemptNumber());
        fence(p, claim.binding());
        // Full claim equality, not just token or the caller's stale CREATE hint.
        // A legitimate first claim's false hint becomes a committed true marker.
        UUID owned = jdbc.query("""
                SELECT work.attempt_id FROM fee_gateway_attempt_work work JOIN fee_gateway_attempts attempt
                  ON attempt.id=work.attempt_id AND attempt.organisation_id=work.organisation_id
                  AND attempt.school_id=work.school_id
                WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode
                  AND work.provider_environment=:environment AND work.merchant_account_reference=:merchant
                  AND work.credentials_secret_reference=:credentialsReference AND work.status='LEASED'
                  AND work.lease_token=:leaseToken AND work.lease_started_at=:leaseStart
                  AND work.lease_until=:leaseUntil AND work.attempt_count=:attemptNumber
                FOR UPDATE OF work,attempt
                """, p, row -> row.next() ? row.getObject("attempt_id", UUID.class) : null);
        if (!claim.id().equals(owned)) throw unavailable();
        var snapshot = dispatch.loadForReadRecovery(routeKey, claim);
        var expected = expected(snapshot);
        if (!staged.expected().equals(expected)) throw unavailable();
        requireCurrentLease(claim, databaseNow(p));
        return finalizeCapture(claim, discovery, staged, snapshot, p);
    }

    // The same economic classifier/capture/receipt/HOLD path serves both kinds
    // of genuine current lease. No separate callback financial writer exists.
    private GatewayWorkQueue.Outcome finalizeCapture(GatewayWorkQueue.Claim claim, GatewayDiscoveryEvidence.Discovery discovery,
            Staged staged, GatewayAttemptDispatch.ReadRecovery snapshot, MapSqlParameterSource p) {
        var expected = expected(snapshot);
        requireCurrentLease(claim, databaseNow(p));
        var result = GatewayDiscoveryEvidence.classify(expected, discovery);
        if (result.disposition() == GatewayDiscoveryEvidence.Disposition.INCOMPLETE
                || result.disposition() == GatewayDiscoveryEvidence.Disposition.PENDING) {
            return finish(claim, GatewayWorkQueue.Outcome.RETRY, "GATEWAY_DISCOVERY_UNRESOLVED", p);
        }
        if (result.disposition() != GatewayDiscoveryEvidence.Disposition.EXACT_CAPTURE
                || !snapshot.createPreviouslyDispatched() || staged.observationId() == null) {
            // Terminal zero is a snapshot for HUMAN review, not automatic release.
            // Conflict-only facts cannot put an attempt into CAPTURED_REVIEW_REQUIRED,
            // whose database semantics require an actual retained exact capture.
            return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_DISCOVERY_REVIEW_REQUIRED", p);
        }
        var candidate = result.exactCapture().orElseThrow();
        var payment = candidate.payment();
        var orders = result.checkout().orElseThrow().orderReferences();
        p.addValue("order", payment.orderReference()).addValue("payment", payment.paymentReference())
                .addValue("amount", payment.amount()).addValue("currency", payment.currencyCode())
                .addValue("paidAt", GatewayExactInstant.parameter(payment.providerPaidAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("paidAtExact", GatewayExactInstant.canonical(payment.providerPaidAt()))
                .addValue("paidAtMicrosecondExact", GatewayExactInstant.floor(payment.providerPaidAt()).equals(payment.providerPaidAt()))
                .addValue("method", payment.paymentMethod())
                .addValue("ordersJson", ordersJson(orders)).addValue("originalDate", candidate.originalBusinessDate());
        if (conflictingMoney(p)) {
            return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_RETAINED_MONEY_CONFLICT", p);
        }
        UUID captureId = exactExistingCapture(p, expected);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_captures WHERE organisation_id=:organisationId
                  AND school_id=:schoolId AND attempt_id=:attemptId
                  AND (CAST(:existingCaptureId AS uuid) IS NULL OR id<>:existingCaptureId))
                """, p.addValue("existingCaptureId", captureId), Boolean.class))) {
            return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_EXISTING_CAPTURE_CONFLICT", p);
        }
        if (captureId == null) {
            if ("POSTED".equals(snapshot.attemptStatus())) {
                return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_EXISTING_CAPTURE_CONFLICT", p);
            }
            UUID closeId = jdbc.queryForObject("""
                    SELECT (SELECT id FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='APPROVED' AND business_date>=:originalDate ORDER BY business_date DESC,id LIMIT 1)
                    """, p, UUID.class);
            String classification = closeId != null || "RELEASED".equals(snapshot.attemptStatus()) ? "REVIEW_REQUIRED" : "MATCHED";
            Instant detectedAt = databaseNow(p);
            requireCurrentLease(claim, detectedAt);
            if (result.observedAt().isAfter(detectedAt)) throw unavailable();
            captureId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO fee_gateway_captures(id,organisation_id,school_id,attempt_id,payment_provider_config_id,
                      checkout_observation_id,provider_code,provider_environment,merchant_account_reference,
                      checkout_reference,order_reference,payment_reference,amount,currency_code,paid_at,paid_at_exact,detected_at,
                      original_business_date,closed_day_close_id,classification,evidence_sha256)
                    VALUES(:captureId,:organisationId,:schoolId,:attemptId,:providerId,:observationId,:providerCode,
                      :environment,:merchant,:checkout,:order,:payment,:amount,:currency,:paidAt,:paidAtExact,:detectedAt,
                      :originalDate,:closeId,:classification,:captureHash)
                    ON CONFLICT(provider_code,provider_environment,merchant_account_reference,payment_reference) DO NOTHING
                    """, p.addValue("captureId", captureId).addValue("observationId", staged.observationId())
                            .addValue("checkout", expected.attemptId().toString()).addValue("detectedAt", Timestamp.from(detectedAt))
                            .addValue("closeId", closeId).addValue("classification", classification)
                            .addValue("captureHash", captureHash(staged, candidate, detectedAt, closeId, classification)));
            if (inserted != 1) {
                // Global merchant/payment uniqueness includes other organisations:
                // an invisible/different capture is never rebound or acknowledged.
                captureId = exactExistingCapture(p, expected);
                if (captureId == null) throw unavailable();
            }
        }
        var posted = finance.postVerifiedGatewayCapture(expected.binding().organisationId(), expected.binding().schoolId(),
                captureId, null, null, null);
        if (posted == null) throw unavailable();
        if ("POSTED".equals(posted.status())) {
            return finish(claim, GatewayWorkQueue.Outcome.COMPLETE, "GATEWAY_VERIFIED_RECEIPT_POSTED", p);
        }
        if ("CAPTURED_REVIEW_REQUIRED".equals(posted.status())) {
            return finish(claim, GatewayWorkQueue.Outcome.REVIEW_REQUIRED, "GATEWAY_VERIFIED_CAPTURE_HELD", p);
        }
        throw unavailable();
    }

    private boolean conflictingMoney(MapSqlParameterSource p) {
        // No latest-status/time/config/credential filter: stronger historical money
        // cannot disappear after credential rotation or an incomplete later GET.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_payment_observations fact
                  WHERE fact.organisation_id=:organisationId AND fact.school_id=:schoolId
                    AND fact.provider_code=:providerCode AND fact.provider_environment=:environment
                    AND fact.merchant_account_reference=:merchant AND fact.captured
                    AND (fact.attempt_id=:attemptId OR fact.payment_reference=:payment
                      OR fact.order_reference IN (SELECT jsonb_array_elements_text(CAST(:ordersJson AS jsonb))))
                    AND (fact.payment_status<>'SUCCESS' OR fact.payment_reference<>:payment OR fact.order_reference<>:order
                      OR fact.amount<>:amount OR fact.currency_code<>:currency
                      OR (fact.provider_paid_at_exact IS NOT NULL AND fact.provider_paid_at_exact IS DISTINCT FROM :paidAtExact)
                      OR (fact.provider_paid_at_exact IS NULL AND
                        (NOT :paidAtMicrosecondExact OR fact.provider_paid_at IS DISTINCT FROM :paidAt))
                      OR (fact.attempt_id IS NOT NULL AND fact.attempt_id<>:attemptId)))
                """, p, Boolean.class));
    }

    private UUID exactExistingCapture(MapSqlParameterSource p, GatewayDiscoveryEvidence.ExpectedAttempt expected) {
        var existing = jdbc.query("""
                SELECT capture.id,
                  capture.organisation_id=:organisationId AND capture.school_id=:schoolId
                  AND capture.payment_provider_config_id=:providerId AND capture.attempt_id=:attemptId
                  AND capture.checkout_reference=:checkout AND capture.order_reference=:order
                  AND capture.amount=:amount AND capture.currency_code=:currency AND capture.paid_at=:paidAt
                  AND (capture.paid_at_exact=:paidAtExact OR
                    (capture.paid_at_exact IS NULL AND :paidAtMicrosecondExact))
                  AND capture.original_business_date=:originalDate AND capture.classification IN ('MATCHED','REVIEW_REQUIRED')
                  AND observed.attempt_id=:attemptId AND observed.payment_provider_config_id=:providerId
                  AND observed.provider_checkout_reference=:attemptId AND observed.attempt_reference=:attemptId
                  AND observed.amount=:amount AND observed.currency_code=:currency AND observed.provider_expires_at=:expectedExpiry
                  AND observed.checkout_status='PAID' AND observed.amount_paid=:amount AND observed.discovery_complete
                  AND observed.pending_payment_count=0 AND observed.successful_payment_count=1
                  AND :order=ANY(observed.order_references) AND observed.observed_at>=capture.paid_at
                  AND observed.observed_at<=capture.detected_at
                  AND gateway_receipt_evidence_matches(capture.id,:organisationId,:schoolId) AS exact
                FROM fee_gateway_captures capture LEFT JOIN fee_gateway_checkout_observations observed
                  ON observed.id=capture.checkout_observation_id AND observed.organisation_id=capture.organisation_id
                  AND observed.school_id=capture.school_id
                WHERE capture.organisation_id=:organisationId AND capture.school_id=:schoolId
                  AND capture.provider_code=:providerCode AND capture.provider_environment=:environment
                  AND capture.merchant_account_reference=:merchant AND capture.payment_reference=:payment
                """, p.addValue("checkout", expected.attemptId().toString()).addValue("expectedExpiry", Timestamp.from(expected.expiresAt())),
                row -> row.next() ? new ExistingCapture(row.getObject("id", UUID.class), row.getBoolean("exact")) : null);
        if (existing != null && !existing.exact()) throw unavailable();
        return existing == null ? null : existing.id();
    }

    private GatewayWorkQueue.Outcome finish(GatewayWorkQueue.Claim claim, GatewayWorkQueue.Outcome outcome,
            String code, MapSqlParameterSource p) {
        return finish(claim, outcome, code, p, true);
    }

    private GatewayWorkQueue.Outcome finish(GatewayWorkQueue.Claim claim, GatewayWorkQueue.Outcome outcome,
            String code, MapSqlParameterSource p, boolean requireActive) {
        requireCurrentLease(claim, databaseNow(p));
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM payment_provider_configs provider JOIN schools school
                  ON school.id=provider.school_id AND school.organisation_id=provider.organisation_id
                  JOIN organisations organisation ON organisation.id=school.organisation_id
                  WHERE provider.id=:providerId AND provider.organisation_id=:organisationId AND provider.school_id=:schoolId
                    AND provider.provider_code=:providerCode AND provider.environment=:environment
                    AND provider.merchant_account_reference=:merchant AND provider.credentials_secret_reference=:credentialsReference
                    AND (NOT :requireActive OR (provider.status='ACTIVE' AND school.status='ACTIVE' AND organisation.status='ACTIVE')))
                """, p.addValue("requireActive", requireActive), Boolean.class))) throw unavailable();
        // Absolutely LAST persistence call: false/exception rolls back capture,
        // hold/audit, receipt number, credits and consumed allocations together.
        if (!queue.complete(claim, outcome, code, Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(), claim.token())))) {
            throw unavailable();
        }
        return outcome;
    }

    private GatewayWorkQueue.ProviderBinding trustedBinding(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery) {
        return trustedBinding(routeKey, claim, discovery, GatewayWorkQueue.WorkKind.ATTEMPT);
    }

    private GatewayWorkQueue.ProviderBinding trustedBinding(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayDiscoveryEvidence.Discovery discovery, GatewayWorkQueue.WorkKind kind) {
        if (!registry.workerEnabled() || claim == null || claim.kind() != kind || discovery == null) {
            throw unavailable();
        }
        var route = registry.findRoute(routeKey).orElseThrow(GatewayObservationFinalizer::unavailable);
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw unavailable();
        var binding = new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(),
                route.providerCode(), route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
        if (!binding.equals(claim.binding()) || !binding.equals(discovery.binding())) throw unavailable();
        return binding;
    }

    private MapSqlParameterSource scope(GatewayWorkQueue.ProviderBinding binding) {
        var context = TenantRequestContext.current().orElseThrow(GatewayObservationFinalizer::unavailable);
        if (context.userId() != null || !binding.organisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId()).addValue("schoolId", binding.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference())
                .addValue("credentialsReference", binding.credentialsReference());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL",
                p, Boolean.class))) throw unavailable();
        return p;
    }

    private void fence(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding binding) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                p.addValue("lockKey", binding.schoolId() + ":finance"), (row, number) -> true);
    }

    private Instant databaseNow(MapSqlParameterSource p) {
        Timestamp time = jdbc.queryForObject("SELECT clock_timestamp()", p, Timestamp.class);
        if (time == null) throw unavailable();
        return time.toInstant();
    }

    private static void requireCurrentLease(GatewayWorkQueue.Claim claim, Instant now) {
        if (now.isBefore(claim.startedAt()) || !now.isBefore(claim.leaseUntil())) throw unavailable();
    }

    private static TransactionTemplate transactions(PlatformTransactionManager manager) {
        var transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setReadOnly(false);
        transactions.setTimeout(15);
        return transactions;
    }

    private static String ordersJson(List<String> orders) {
        // Classifier/OrderRead canonical references cannot contain JSON metacharacters.
        return "[" + String.join(",", orders.stream().map(order -> "\"" + order + "\"").toList()) + "]";
    }

    private static String checkoutHash(GatewayDiscoveryEvidence.ExpectedAttempt expected,
            GatewayDiscoveryEvidence.Result result, List<String> sortedOrders) {
        var checkout = result.checkout().orElseThrow();
        var binding = expected.binding();
        return hash(String.join("|", "CHECKOUT_GET_EXACT_V2", binding.organisationId().toString(), binding.schoolId().toString(),
                binding.providerConfigId().toString(), binding.providerCode(), binding.environment(), binding.merchantReference(),
                binding.credentialsReference(), expected.attemptId().toString(), expected.accountId().toString(),
                checkout.checkoutReference(), checkout.attemptReference(), checkout.amount().setScale(2).toPlainString(),
                checkout.currencyCode(), checkout.amountPaid().setScale(2).toPlainString(), checkout.status().name(),
                checkout.expiresAt().toString(), ordersJson(sortedOrders), Integer.toString(result.pendingPaymentCount()),
                Integer.toString(result.successfulPaymentCount()), Boolean.toString(result.discoveryComplete()), result.observedAt().toString()));
    }

    private static String captureHash(Staged staged, GatewayDiscoveryEvidence.CaptureCandidate candidate,
            Instant detectedAt, UUID closeId, String classification) {
        var payment = candidate.payment();
        return hash(String.join("|", "CAPTURE_GET_EXACT_V2", staged.proofHash(), staged.observationId().toString(),
                payment.orderReference(), payment.paymentReference(), payment.amount().setScale(2).toPlainString(), payment.currencyCode(),
                payment.providerPaidAt().toString(), candidate.originalBusinessDate().toString(), detectedAt.toString(),
                String.valueOf(closeId), classification));
    }

    private static String hash(String canonical) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw unavailable(); }
    }

    private record Staged(GatewayDiscoveryEvidence.ExpectedAttempt expected, GatewayDiscoveryEvidence.Result result,
            UUID observationId, String proofHash) {
        @Override public String toString() { return "Staged[durable minimal authenticated discovery evidence]"; }
    }
    private record InboxStaged(GatewayInboxDispatch.Admission source, Staged snapshot) {
        @Override public String toString() { return "InboxStaged[private retained callback and independent GET evidence]"; }
    }
    private record ExistingCapture(UUID id, boolean exact) {
        @Override public String toString() { return "ExistingCapture[immutable original scoped proof]"; }
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway finalization requires trusted scoped facts, a current lease and exact immutable financial proof");
    }
}
