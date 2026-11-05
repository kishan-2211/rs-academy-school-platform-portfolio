package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewaySettlementBankEvidenceTermsTest {
    private final UUID school=UUID.randomUUID(),request=UUID.randomUUID(),client=UUID.randomUUID(),bank=UUID.randomUUID();
    private static final Instant TIME=Instant.parse("2026-10-25T00:00:00.999999999Z");
    @Test void exactAmountFullNanosecondTimeAndEstablishedReferenceNormalizationArePreserved(){
        var t=GatewaySettlementBankEvidenceTerms.record(request,command("100.00",TIME," utr / 001\t ","Checked school bank credit",true));
        assertThat(t.amount()).isEqualByComparingTo("100.00");assertThat(t.amount().scale()).isEqualTo(2);
        assertThat(t.actualExact()).isEqualTo("2026-10-25T00:00:00.999999999Z");assertThat(t.reference()).isEqualTo("utr / 001");
        assertThat(t.normalizedReference()).isEqualTo("UTR/001");assertThat(GatewayExactInstant.parameter(t.actualAt()).toInstant()).isEqualTo(Instant.parse("2026-10-25T00:00:00.999999Z"));
    }
    @Test void canonicalAmountInputsAreExactPaiseWithoutRoundingScientificNotationOrLeadingZeroes(){
        for(String amount:List.of("1","1.0","1.00"))assertThat(GatewaySettlementBankEvidenceTerms.record(request,command(amount,TIME,"utr-1","Checked credit",true)).amount().toPlainString()).isEqualTo("1.00");
        for(String amount:List.of("0","0.00","-1.00","0.001","100000000.01","1e2","01.00"," 1.00","1.","NaN","999999999999999999999.00"))
            invalid(()->GatewaySettlementBankEvidenceTerms.record(request,command(amount,TIME,"utr-1","Checked credit",true)));
    }
    @Test void confirmationRequiredSupportedCurrencyAndVerificationKindCannotBeInferred(){
        invalid(()->GatewaySettlementBankEvidenceTerms.record(request,command("1.00",TIME,"utr-1","Checked credit",false)));
        var valid=command("1.00",TIME,"utr-1","Checked credit",true);
        for(String currency:List.of("USD","inr",""))invalid(()->GatewaySettlementBankEvidenceTerms.record(request,new GatewaySettlementBankEvidenceModels.Command(
                school,client,bank,currency,"1.00",TIME,valid.verificationKind(),valid.bankCreditReference(),true,valid.reason())));
        invalid(()->GatewaySettlementBankEvidenceTerms.record(request,new GatewaySettlementBankEvidenceModels.Command(school,client,bank,"INR","1.00",TIME,"PAYER_SCREENSHOT","utr-1",true,"Checked credit")));
    }
    @Test void missingIdentitiesAndUnsupportedInstantsFailWithControlledPrivateError(){
        invalid(()->GatewaySettlementBankEvidenceTerms.record(null,command("1.00",TIME,"utr-1","Checked credit",true)));
        invalid(()->GatewaySettlementBankEvidenceTerms.record(request,null));
        invalid(()->GatewaySettlementBankEvidenceTerms.record(request,command("1.00",null,"utr-1","Checked credit",true)));
        invalid(()->GatewaySettlementBankEvidenceTerms.record(request,command("1.00",Instant.MAX,"utr-1","Checked credit",true)));
        invalid(()->GatewaySettlementBankEvidenceTerms.record(request,new GatewaySettlementBankEvidenceModels.Command(null,client,bank,"INR","1.00",TIME,"BANK_APP_CREDIT","utr-1",true,"Checked credit")));
    }
    @Test void safeBilingualReasonIsStrippedWhileControlsAndUnboundedTextAreRejected(){
        assertThat(GatewaySettlementBankEvidenceTerms.reason("  बैंक क्रेडिट की जाँच की गई  ")).isEqualTo("बैंक क्रेडिट की जाँच की गई");
        for(String reason:List.of("xx","x".repeat(241),"private\nline","private\u007fvalue","private\u0085value"))invalid(()->GatewaySettlementBankEvidenceTerms.reason(reason));
    }
    @Test void bankReferencesKeepMeaningfulPunctuationAndRejectUrlsUnicodeAndScreenshotDescriptions(){
        for(String reference:List.of("https://bank.example.test","UTR@12","UTR₹12","UTR\n12","\u0000UTR","x".repeat(121),""))invalid(()->GatewaySettlementBankEvidenceTerms.reference(reference));
        assertThat(ManualCollectionEvidence.normalizeBankReference(GatewaySettlementBankEvidenceTerms.reference(" a / b-1._:2 "))).isEqualTo("A/B-1._:2");
        assertThat(ManualCollectionEvidence.normalizeBankReference("AB-1")).isNotEqualTo(ManualCollectionEvidence.normalizeBankReference("AB1"));
    }
    @Test void fingerprintsBindRecordTermsButNotVolatileCurrentConfigurationOrRecordingTime(){
        var command=command("100",TIME,"utr-1","Checked credit",true);
        var terms=GatewaySettlementBankEvidenceTerms.record(request,command);
        assertThat(GatewaySettlementBankEvidenceTerms.record(request,command("100.00",TIME,"utr-1","Checked credit",true)).fingerprint()).isEqualTo(terms.fingerprint());
        assertThat(GatewaySettlementBankEvidenceTerms.record(UUID.randomUUID(),command).fingerprint()).isNotEqualTo(terms.fingerprint());
        assertThat(GatewaySettlementBankEvidenceTerms.record(request,command("100",TIME.plusNanos(1),"utr-1","Checked credit",true)).fingerprint()).isNotEqualTo(terms.fingerprint());
        assertThat(GatewaySettlementBankEvidenceTerms.record(request,command("100",TIME,"utr-2","Checked credit",true)).fingerprint()).isNotEqualTo(terms.fingerprint());
    }
    @Test void independentRejectionIntentRequiresExplicitMistakeAndBindsEvidenceAndRequest(){
        UUID evidence=UUID.randomUUID();var command=new GatewaySettlementBankEvidenceModels.RejectionCommand(school,client,"Mistaken bank reference",true);
        var terms=GatewaySettlementBankEvidenceTerms.rejection(request,evidence,command);
        assertThat(GatewaySettlementBankEvidenceTerms.rejection(request,evidence,command)).isEqualTo(terms);
        assertThat(GatewaySettlementBankEvidenceTerms.rejection(request,UUID.randomUUID(),command).fingerprint()).isNotEqualTo(terms.fingerprint());
        invalid(()->GatewaySettlementBankEvidenceTerms.rejection(request,evidence,new GatewaySettlementBankEvidenceModels.RejectionCommand(school,client,"Mistaken bank reference",false)));
        invalid(()->GatewaySettlementBankEvidenceTerms.rejection(request,null,command));
    }
    @Test void reviewReasonsAlwaysSeparateAttestationFromCurrencySourcesAndFullReconciliation(){
        var summary=summary("SUCCESS","utr-1",new BigDecimal("100.00000000"));
        assertThat(GatewaySettlementBankEvidenceService.reviewReasons(summary,new BigDecimal("100.00"),"UTR-1")).containsExactly("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING");
        assertThat(GatewaySettlementBankEvidenceService.reviewReasons(summary("UNKNOWN",null,null),new BigDecimal("100.00"),"UTR-1"))
                .containsExactly("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING","SUMMARY_AMOUNT_UNKNOWN","TRANSFER_REFERENCE_UNKNOWN","SUMMARY_STATUS_REVIEW");
        assertThat(GatewaySettlementBankEvidenceService.reviewReasons(summary("SUCCESS","UTR-2",new BigDecimal("99.99999999")),new BigDecimal("100.00"),"UTR-1"))
                .contains("SUMMARY_AMOUNT_MISMATCH","TRANSFER_REFERENCE_MISMATCH");
    }
    @Test void allCommandAndTermDiagnosticsAreRedacted(){
        var command=command("100.00",TIME,"private_utr","Private safe reason",true);var terms=GatewaySettlementBankEvidenceTerms.record(request,command);
        assertThat(command.toString()).doesNotContain("private_utr","Private safe reason");
        assertThat(terms.toString()).doesNotContain("private_utr","Private safe reason",terms.fingerprint());
        var rejection=new GatewaySettlementBankEvidenceModels.RejectionCommand(school,client,"Private safe mistake",true);
        assertThat(rejection.toString()).doesNotContain("Private safe mistake");
        assertThat(GatewaySettlementBankEvidenceTerms.rejection(request,UUID.randomUUID(),rejection).toString()).doesNotContain("Private safe mistake");
    }
    private GatewaySettlementBankEvidenceModels.Command command(String amount,Instant at,String reference,String reason,boolean confirmed){
        return new GatewaySettlementBankEvidenceModels.Command(school,client,bank,"INR",amount,at,"BANK_APP_CREDIT",reference,confirmed,reason);
    }
    private static GatewaySettlementProvider.SettlementSummary summary(String status,String reference,BigDecimal amount){
        return new GatewaySettlementProvider.SettlementSummary("synthetic_statement",status,reference,null,null,null,null,null,null,null,null,null,null,null,null,null,amount,null,null);
    }
    private static void invalid(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->{
        assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_BANK_TERMS_INVALID");assertThat(error.getMessage()).doesNotContain("private_utr");});}
}
