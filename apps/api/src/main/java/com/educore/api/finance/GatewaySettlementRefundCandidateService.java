package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human read-only candidate context. No provider calls, automatic association, locks or financial writes. */
@Service
public class GatewaySettlementRefundCandidateService {
    private final GatewaySettlementService settlements;
    private final NamedParameterJdbcTemplate jdbc;
    private final FeeAccountingService fees;

    public GatewaySettlementRefundCandidateService(GatewaySettlementService settlements, NamedParameterJdbcTemplate jdbc,
            FeeAccountingService fees) {
        this.settlements = Objects.requireNonNull(settlements); this.jdbc = Objects.requireNonNull(jdbc); this.fees = Objects.requireNonNull(fees);
    }

    @Transactional(readOnly = true)
    public GatewaySettlementRefundCandidateModels.Review candidates(AuthenticatedUser user, UUID schoolId, UUID requestId,
            UUID sourceReadId, int rowIndex, int page, int size) {
        GatewaySettlementTerms.page(page, size);
        var chain = settlements.completeEvents(user, schoolId, requestId);
        var retained = chain.pages().stream().filter(value -> value.readId().equals(sourceReadId)).findFirst()
                .orElseThrow(GatewaySettlementSourceReviewEvidence::unavailable);
        if (rowIndex < 0 || rowIndex >= retained.events().size()) throw GatewaySettlementSourceReviewEvidence.unavailable();
        var event = retained.events().get(rowIndex); var reasons = reasons(chain, event);
        if (!reasons.isEmpty()) return result(chain, sourceReadId, rowIndex, event, null, reasons, page, size, false, List.of());
        var binding = chain.binding();
        var p = new MapSqlParameterSource().addValue("organisationId", chain.organisationId()).addValue("schoolId", chain.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference())
                .addValue("orderReference", event.orderReference()).addValue("paymentReference", event.paymentReference());
        var sources = jdbc.query(SOURCE, p, (row, index) -> new PaymentSource(row.getObject("capture_id", UUID.class),
                row.getBigDecimal("capture_amount"), row.getString("capture_currency"), row.getObject("payment_id", UUID.class),
                row.getObject("ledger_entry_id", UUID.class), row.getString("receipt_number"), row.getString("payment_status"),
                row.getString("payment_method"), row.getBigDecimal("receipt_amount"), row.getString("receipt_currency"),
                row.getObject("received_on", LocalDate.class), row.getObject("open_date", LocalDate.class)));
        if (sources.size() > 1) reasons.add("ORIGINAL_PAYMENT_SOURCE_AMBIGUOUS");
        else if (sources.isEmpty() || !resolved(sources.getFirst())) reasons.add("ORIGINAL_RECEIPT_UNRESOLVED");
        if (!reasons.isEmpty()) return result(chain, sourceReadId, rowIndex, event, null, reasons, page, size, false, List.of());
        var source = sources.getFirst();
        p.addValue("paymentId", source.paymentId()).addValue("amount", event.reportedAmount()).addValue("currency", event.currencyCode())
                .addValue("limit", size + 1).addValue("offset", (long) page * size);
        var completions = jdbc.query(CANDIDATES, p, (row, index) -> new Completion(row.getObject("id", UUID.class),
                row.getObject("refund_request_id", UUID.class), row.getObject("posted_on", LocalDate.class),
                row.getBigDecimal("amount"), row.getString("currency_code"), row.getString("processed_at_exact"), row.getString("refund_reference")));
        var items = new ArrayList<GatewaySettlementRefundCandidateModels.Candidate>();
        for (var completion : completions.subList(0, Math.min(size, completions.size()))) {
            UUID journal = null;
            try {
                var proof = fees.verifyCompletedRefundPosting(user, schoolId, completion.id());
                if (proof != null && completion.id().equals(proof.refundPostingId()) && completion.refundId().equals(proof.refundId())
                        && source.paymentId().equals(proof.paymentId()) && completion.currency().equals(proof.currencyCode())
                        && completion.amount().compareTo(proof.refundAmount()) == 0) journal = proof.accountingJournalId();
            } catch (ApiException invalid) {
                if (!"FEE_COMPLETED_REFUND_PROOF_INVALID".equals(invalid.code())) throw invalid;
            }
            var references = event.refundReferences();
            String mention = references == null || references.isEmpty() || completion.refundReference() == null ? "NOT_REPORTED"
                    : references.contains(completion.refundReference()) ? "MENTIONED" : "NOT_MENTIONED";
            items.add(new GatewaySettlementRefundCandidateModels.Candidate(completion.id(), completion.refundId(), completion.postedOn(),
                    completion.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), completion.currency(), completion.processedAt(),
                    journal == null ? "ACCOUNTING_REVIEW_REQUIRED" : "POSTED_SOURCE_PROVEN", journal, mention));
        }
        return result(chain, sourceReadId, rowIndex, event, source.receiptNumber(), List.of(), page, size, completions.size() > size, items);
    }

    private static List<String> reasons(GatewaySettlementSourceReviewEvidence.Chain chain, GatewaySettlementProvider.SettlementEvent event) {
        var reasons = new ArrayList<String>();
        if (!"CASHFREE".equals(chain.binding().providerCode())) reasons.add("UNSUPPORTED_PROVIDER");
        if (!"REFUND".equals(event.reportedType())) reasons.add("REFUND_EVENT_REQUIRED");
        if (!"DEBIT".equals(event.reportedDirection())) reasons.add("UNSUPPORTED_DIRECTION");
        if (!"SUCCESS".equals(event.reportedStatus())) reasons.add("UNSUCCESSFUL_EVENT");
        if (!"INR".equals(event.currencyCode())) reasons.add("UNSUPPORTED_CURRENCY");
        try { event.reportedAmount().setScale(2, RoundingMode.UNNECESSARY); } catch (ArithmeticException invalid) { reasons.add("FRACTIONAL_PAISE"); }
        if (event.reportedAmount().signum() <= 0) reasons.add("NON_POSITIVE_AMOUNT");
        if (event.orderReference() == null || event.paymentReference() == null) reasons.add("MISSING_PAYMENT_REFERENCES");
        return reasons;
    }

    /** Internal exact provider binding for an explicitly selected refund completion. */
    UUID originalPaymentId(GatewaySettlementSourceReviewEvidence.Chain chain,GatewaySettlementProvider.SettlementEvent event) {
        if(!reasons(chain,event).isEmpty())throw GatewaySettlementSourceReviewEvidence.unavailable();
        var binding=chain.binding();
        var parameters=new MapSqlParameterSource().addValue("organisationId",chain.organisationId()).addValue("schoolId",chain.schoolId())
                .addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode())
                .addValue("environment",binding.environment()).addValue("merchant",binding.merchantReference())
                .addValue("orderReference",event.orderReference()).addValue("paymentReference",event.paymentReference());
        var matches=jdbc.query(SOURCE,parameters,(row,n)->new PaymentSource(row.getObject("capture_id",UUID.class),
                row.getBigDecimal("capture_amount"),row.getString("capture_currency"),row.getObject("payment_id",UUID.class),
                row.getObject("ledger_entry_id",UUID.class),row.getString("receipt_number"),row.getString("payment_status"),
                row.getString("payment_method"),row.getBigDecimal("receipt_amount"),row.getString("receipt_currency"),
                row.getObject("received_on",LocalDate.class),row.getObject("open_date",LocalDate.class)));
        if(matches.size()!=1||!resolved(matches.getFirst()))throw GatewaySettlementSourceReviewEvidence.unavailable();
        return matches.getFirst().paymentId();
    }

    private static boolean resolved(PaymentSource source) {
        return source.captureId() != null && source.paymentId() != null && source.ledgerId() != null
                && source.receiptNumber() != null && !source.receiptNumber().isBlank() && "POSTED".equals(source.status())
                && "ONLINE_PROVIDER".equals(source.method()) && source.receivedOn() != null && source.receivedOn().equals(source.openDate())
                && "INR".equals(source.captureCurrency()) && "INR".equals(source.receiptCurrency())
                && money(source.captureAmount()) && money(source.receiptAmount()) && source.captureAmount().compareTo(source.receiptAmount()) == 0;
    }
    private static boolean money(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("100000000.00")) > 0) return false;
        try { amount.setScale(2, RoundingMode.UNNECESSARY); return true; } catch (ArithmeticException invalid) { return false; }
    }
    private static GatewaySettlementRefundCandidateModels.Review result(GatewaySettlementSourceReviewEvidence.Chain chain,
            UUID sourceReadId, int rowIndex, GatewaySettlementProvider.SettlementEvent event, String receipt, List<String> reasons,
            int page, int size, boolean more, List<GatewaySettlementRefundCandidateModels.Candidate> items) {
        return new GatewaySettlementRefundCandidateModels.Review(chain.schoolId(), chain.request().id(), sourceReadId, rowIndex,
                chain.request().settlementReference(), GatewaySettlementRefundCandidateModels.STAGE,
                reasons.isEmpty() ? "CANDIDATE_CONTEXT" : "REVIEW_REQUIRED", event.eventReference(), event.reportedAmount().toPlainString(),
                event.currencyCode(), receipt, reasons, page, size, more, items);
    }
    private record PaymentSource(UUID captureId, BigDecimal captureAmount, String captureCurrency, UUID paymentId,
            UUID ledgerId, String receiptNumber, String status, String method, BigDecimal receiptAmount,
            String receiptCurrency, LocalDate receivedOn, LocalDate openDate) {
        @Override public String toString() { return "PaymentSource[private original refund receipt context]"; }
    }
    private record Completion(UUID id, UUID refundId, LocalDate postedOn, BigDecimal amount, String currency, String processedAt, String refundReference) {
        @Override public String toString() { return "Completion[private historical refund candidate]"; }
    }
    private static final String SOURCE = """
            /* settlement-refund-candidate-original-payment */
            SELECT capture.id capture_id,capture.amount capture_amount,capture.currency_code capture_currency,
              payment.id payment_id,payment.ledger_entry_id,payment.receipt_number,payment.status payment_status,
              payment.payment_method,payment.amount receipt_amount,account.currency_code receipt_currency,
              payment.received_on,resolution.open_date
            FROM fee_gateway_captures capture
            LEFT JOIN fee_gateway_capture_resolutions resolution ON resolution.capture_id=capture.id
              AND resolution.organisation_id=capture.organisation_id AND resolution.school_id=capture.school_id
              AND resolution.resolution_type='POSTED'
            LEFT JOIN fee_payments payment ON payment.id=resolution.fee_payment_id AND payment.gateway_capture_id=capture.id
              AND payment.organisation_id=capture.organisation_id AND payment.school_id=capture.school_id
            LEFT JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
              AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
            WHERE capture.organisation_id=:organisationId AND capture.school_id=:schoolId
              AND capture.payment_provider_config_id=:providerId AND capture.provider_code=:providerCode
              AND capture.provider_environment=:environment AND capture.merchant_account_reference=:merchant
              AND capture.order_reference=:orderReference AND capture.payment_reference=:paymentReference
            ORDER BY capture.id,payment.id LIMIT 2
            """;
    private static final String CANDIDATES = """
            /* settlement-refund-candidate-completions */
            SELECT completion.id,completion.refund_request_id,completion.posted_on,completion.amount,completion.currency_code,
              success.processed_at_exact,success.refund_reference
            FROM fee_gateway_refund_postings completion
            LEFT JOIN fee_gateway_refund_successes success ON success.refund_request_id=completion.refund_request_id
              AND success.organisation_id=completion.organisation_id AND success.school_id=completion.school_id
              AND success.fee_payment_id=completion.fee_payment_id AND success.verified_read_id=completion.verified_read_id
              AND success.evidence_sha256=completion.success_evidence_sha256 AND success.source_version=completion.source_version
              AND success.amount=completion.amount AND success.currency_code=completion.currency_code
            WHERE completion.organisation_id=:organisationId AND completion.school_id=:schoolId
              AND completion.fee_payment_id=:paymentId AND completion.amount=:amount AND completion.currency_code=:currency
            ORDER BY completion.posted_on,completion.id LIMIT :limit OFFSET :offset
            """;
}
