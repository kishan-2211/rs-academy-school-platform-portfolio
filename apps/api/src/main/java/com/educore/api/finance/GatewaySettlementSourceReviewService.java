package com.educore.api.finance;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingModuleBridge.FeeJournalEvidence;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human read-only historical matching. No locks, provider calls, SYSTEM adoption or financial writes. */
@Service
public class GatewaySettlementSourceReviewService {
    private final GatewaySettlementService settlements;
    private final NamedParameterJdbcTemplate jdbc;
    private final FeeAccountingService fees;
    private final AccountingModuleBridge accounting;

    public GatewaySettlementSourceReviewService(GatewaySettlementService settlements, NamedParameterJdbcTemplate jdbc,
            FeeAccountingService fees, AccountingModuleBridge accounting) {
        this.settlements = Objects.requireNonNull(settlements); this.jdbc = Objects.requireNonNull(jdbc);
        this.fees = Objects.requireNonNull(fees); this.accounting = Objects.requireNonNull(accounting);
    }

    @Transactional(readOnly = true)
    public GatewaySettlementSourceReviewModels.Review review(AuthenticatedUser user, UUID schoolId, UUID requestId, int page, int size) {
        GatewaySettlementTerms.page(page, size);
        // Existing live human authority and canonical retained evidence are re-used, not cached principal claims.
        var chain = settlements.completeEvents(user, schoolId, requestId);
        Map<UUID, FeeJournalEvidence> journals = new HashMap<>();
        return GatewaySettlementSourceReviewEvaluator.evaluate(chain, page, size, event -> identify(user, chain, event, journals));
    }

    /** Same complete-chain resolver for the connected preparation; never accepts browser-supplied matches. */
    List<GatewaySettlementSourceReviewModels.Row> allRows(AuthenticatedUser user,GatewaySettlementSourceReviewEvidence.Chain chain) {
        Map<GatewaySettlementProvider.SettlementEvent,CaptureResolution> captures=new HashMap<>();
        var paymentIds=new java.util.LinkedHashSet<UUID>();
        // The evaluator alone decides which supported, unique physical events may
        // resolve a capture. The first pass is local discovery, never a returned
        // provisional match or a financial decision.
        GatewaySettlementSourceReviewEvaluator.allRows(chain,event->{
            var capture=resolveCapture(chain,event);captures.put(event,capture);
            if(capture.reason()!=null)return reason(capture.reason());
            paymentIds.add(capture.source().paymentId());return reason("ORIGINAL_POSTING_INVALID");
        });
        Map<UUID,FeeAccountingService.OriginalPaymentPostingProof> proofs=new HashMap<>();
        var ids=List.copyOf(paymentIds);
        for(int start=0;start<ids.size();start+=1000) {
            var chunk=ids.subList(start,Math.min(start+1000,ids.size()));
            var result=fees.verifyOriginalPaymentPostings(user,chain.schoolId(),chunk);
            if(result==null)throw new IllegalStateException("Original payment proof outcomes are unavailable");
            var covered=new java.util.HashSet<>(result.proofs().keySet());covered.addAll(result.invalidPaymentIds());
            if(!covered.equals(new java.util.HashSet<>(chunk)))throw new IllegalStateException("Complete scoped original payment proof outcomes required");
            proofs.putAll(result.proofs());
        }
        Map<UUID,FeeJournalEvidence> journals=new HashMap<>();
        var journalIds=proofs.values().stream().map(FeeAccountingService.OriginalPaymentPostingProof::accountingJournalId).distinct().toList();
        // At most 500 original and 500 counter lines per journal fit the existing
        // accounting reader's 20,000 normalized-group ceiling in each chunk.
        for(int start=0;start<journalIds.size();start+=20) {
            var chunk=journalIds.subList(start,Math.min(start+20,journalIds.size()));
            var found=accounting.feeJournalEvidence(user,chain.schoolId(),chunk);
            for(UUID id:chunk)journals.put(id,found==null?null:found.get(id));
        }
        return GatewaySettlementSourceReviewEvaluator.allRows(chain,event->{
            var capture=captures.get(event);
            if(capture==null)throw new IllegalStateException("The retained source classification changed within this invocation");
            if(capture.reason()!=null)return reason(capture.reason());
            return identifyProof(user,chain,event,capture.source(),proofs.get(capture.source().paymentId()),journals);
        });
    }

    private GatewaySettlementSourceReviewEvaluator.Identification identify(AuthenticatedUser user,
            GatewaySettlementSourceReviewEvidence.Chain chain, GatewaySettlementProvider.SettlementEvent event,
            Map<UUID, FeeJournalEvidence> journals) {
        var capture=resolveCapture(chain,event);
        if(capture.reason()!=null)return reason(capture.reason());
        var source=capture.source();
        FeeAccountingService.OriginalPaymentPostingProof proof;
        try { proof = fees.verifyOriginalPaymentPosting(user, chain.schoolId(), source.paymentId()); }
        catch (ApiException invalid) {
            if ("FEE_ORIGINAL_PAYMENT_PROOF_INVALID".equals(invalid.code())) return reason("ORIGINAL_POSTING_INVALID");
            throw invalid; // Revocation/outage is not a fabricated "unmatched" financial source.
        }
        return identifyProof(user,chain,event,source,proof,journals);
    }

    private CaptureResolution resolveCapture(GatewaySettlementSourceReviewEvidence.Chain chain,
            GatewaySettlementProvider.SettlementEvent event) {
        var binding = chain.binding();
        var p = new MapSqlParameterSource().addValue("organisationId", chain.organisationId()).addValue("schoolId", chain.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference())
                .addValue("orderReference", event.orderReference()).addValue("paymentReference", event.paymentReference());
        var candidates = jdbc.query(SOURCE, p, (row, index) -> new CaptureSource(row.getObject("capture_id", UUID.class),
                row.getBigDecimal("capture_amount"), row.getString("capture_currency"), row.getObject("payment_id", UUID.class),
                row.getObject("ledger_entry_id", UUID.class), row.getString("receipt_number"), row.getString("payment_status"),
                row.getString("payment_method"), row.getBigDecimal("receipt_amount"), row.getString("receipt_currency"),
                row.getObject("received_on", LocalDate.class), row.getObject("open_date", LocalDate.class)));
        if (candidates.isEmpty()) return new CaptureResolution(null,"SOURCE_NOT_FOUND_OR_OUT_OF_SCOPE");
        if (candidates.size() != 1) return new CaptureResolution(null,"AMBIGUOUS_PAYMENT_SOURCE");
        var source = candidates.getFirst();
        if (source.captureId() == null || source.paymentId() == null || source.ledgerId() == null
                || source.receiptNumber() == null || source.receiptNumber().isBlank() || !"POSTED".equals(source.status())
                || !"ONLINE_PROVIDER".equals(source.method()) || source.receivedOn() == null || !source.receivedOn().equals(source.openDate()))
            return new CaptureResolution(null,"UNRESOLVED_NUMBERED_RECEIPT");
        if (!"INR".equals(source.captureCurrency()) || !"INR".equals(source.receiptCurrency())
                || !exactPositiveMoney(source.captureAmount()) || !exactPositiveMoney(source.receiptAmount())
                || source.captureAmount().compareTo(event.reportedAmount()) != 0 || source.captureAmount().compareTo(source.receiptAmount()) != 0)
            return new CaptureResolution(null,"GROSS_MISMATCH");
        return new CaptureResolution(source,null);
    }

    private GatewaySettlementSourceReviewEvaluator.Identification identifyProof(AuthenticatedUser user,
            GatewaySettlementSourceReviewEvidence.Chain chain,GatewaySettlementProvider.SettlementEvent event,CaptureSource source,
            FeeAccountingService.OriginalPaymentPostingProof proof,Map<UUID,FeeJournalEvidence> journals) {
        if (proof == null || !source.paymentId().equals(proof.paymentId()) || !source.ledgerId().equals(proof.feeLedgerEntryId())
                || !"INR".equals(proof.currencyCode()) || proof.allocations().isEmpty()
                || proof.allocations().stream().anyMatch(allocation -> !exactPositiveMoney(allocation.amount()))
                || proof.allocations().stream().map(FeeAccountingService.OriginalPaymentAllocation::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(source.captureAmount()) != 0) return reason("ORIGINAL_POSTING_INVALID");
        // Accounting owns journals/account metadata. No accounting table or repository is read from finance.
        FeeJournalEvidence journal = journals.get(proof.accountingJournalId());
        if (journal == null) {
            if(journals.containsKey(proof.accountingJournalId()))return reason("CLEARING_EVIDENCE_UNAVAILABLE");
            var evidence = accounting.feeJournalEvidence(user, chain.schoolId(), List.of(proof.accountingJournalId()));
            if (evidence == null || evidence.size() != 1 || (journal = evidence.get(proof.accountingJournalId())) == null)
                return reason("CLEARING_EVIDENCE_UNAVAILABLE");
            journals.put(proof.accountingJournalId(), journal);
        }
        if (!proof.accountingJournalId().equals(journal.id()) || !proof.postingBatchId().equals(journal.sourceId())
                || !proof.originalFinancialYearId().equals(journal.financialYearId()) || !proof.originalAccountingPeriodId().equals(journal.accountingPeriodId())
                || !proof.originalPostingDate().equals(journal.postingDate()) || !proof.accountingBasis().equals(journal.accountingBasis())
                || !proof.currencyCode().equals(journal.currencyCode()) || !"POSTED".equals(journal.status())
                || journal.reversalId() != null || journal.reversalStatus() != null || !journal.reversalLines().isEmpty()) return reason("ORIGINAL_POSTING_INVALID");
        var clearing = journal.lines().stream().filter(line -> proof.depositAccountId().equals(line.accountId())).toList();
        if (clearing.isEmpty()) return reason("INVALID_CLEARING_IDENTITY");
        if (clearing.stream().anyMatch(line -> "BANK".equals(line.accountCategory()))) return reason("LEGACY_BANK_CLEARING_REVIEW");
        if (clearing.stream().anyMatch(line -> !"ASSET".equals(line.accountType()) || !"OTHER".equals(line.accountCategory())
                || line.vendorId() != null || line.learnerFeeAccountId() != null
                || line.debitAmount() == null || line.debitAmount().signum() <= 0
                || line.creditAmount() == null || line.creditAmount().signum() != 0)) return reason("INVALID_CLEARING_IDENTITY");
        // Closed books/accounts are historical evidence. Pending reversals are NOT ruled out by this non-locking reader.
        var identified = new GatewaySettlementSourceReviewModels.Source(source.captureId(), source.paymentId(), proof.feeLedgerEntryId(),
                source.receiptNumber(), proof.postingItemId(), proof.postingBatchId(), proof.configurationId(), proof.accountingJournalId(),
                proof.depositAccountId(), "OTHER", proof.originalFinancialYearId(), proof.originalAccountingPeriodId(), proof.originalPostingDate(),
                proof.accountingBasis(), proof.currencyCode(), source.captureAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return new GatewaySettlementSourceReviewEvaluator.Identification(identified, null);
    }

    private static GatewaySettlementSourceReviewEvaluator.Identification reason(String reason) {
        return new GatewaySettlementSourceReviewEvaluator.Identification(null, reason);
    }
    private static boolean exactPositiveMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("100000000.00")) > 0) return false;
        try { amount.setScale(2, RoundingMode.UNNECESSARY); return true; } catch (ArithmeticException invalid) { return false; }
    }
    private record CaptureSource(UUID captureId, BigDecimal captureAmount, String captureCurrency, UUID paymentId,
            UUID ledgerId, String receiptNumber, String status, String method, BigDecimal receiptAmount,
            String receiptCurrency, LocalDate receivedOn, LocalDate openDate) {
        @Override public String toString() { return "CaptureSource[private scoped historical payment]"; }
    }

    private record CaptureResolution(CaptureSource source,String reason) {
        @Override public String toString(){return "CaptureResolution[private invocation-local exact source lookup]";}
    }

    private static final String SOURCE = """
            /* settlement-source-review-exact-capture */
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
}
