package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic projection/scope tests only; these are not PostgreSQL or bank acceptance. */
class GatewaySettlementFactsTest {
    private static final Instant WHEN=Instant.parse("2026-10-22T12:30:00.123456789Z");
    private static final String SETTLEMENT="synthetic_statement", EVENT="synthetic_event";

    @AfterEach void clearOnlyTestTransactionMarkers(){TransactionSynchronizationManager.clear();}

    @Test void summaryRoundTripPreservesEightPlaceReportedMoneyAndNanosecondInstants(){
        var source=summary();String canonical=GatewaySettlementFacts.summaries(List.of(source));
        var restored=GatewaySettlementFacts.readSummaries(canonical).getFirst();
        assertThat(restored).isEqualTo(source);
        assertThat(restored.reportedPaymentAmount()).isEqualTo(new BigDecimal("1000.12345678"));
        assertThat(restored.reportedAdjustment()).isEqualTo(new BigDecimal("-0.12345678"));
        assertThat(restored.initiatedAt()).isEqualTo(WHEN);
        assertThat(canonical).contains("\"reportedPaymentAmount\":\"1000.12345678\"",WHEN.toString());
        assertThat(GatewaySettlementFacts.summaries(List.of(restored))).isEqualTo(canonical);
    }

    @Test void nullableSummaryAmountsRemainMissingRatherThanBecomingZeroOrAssumedCurrency(){
        var source=new GatewaySettlementProvider.SettlementSummary(SETTLEMENT,"PENDING_WITH_BANK",null,
                null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        String canonical=GatewaySettlementFacts.summaries(List.of(source));
        var restored=GatewaySettlementFacts.readSummaries(canonical).getFirst();
        assertThat(restored).isEqualTo(source);
        assertThat(restored.reportedAmountSettled()).isNull();
        assertThat(restored.reportedPaymentAmount()).isNull();
        assertThat(canonical).contains("\"reportedAmountSettled\":null").doesNotContain("currency","INR");
    }

    @Test void eventsKeepUnknownCategoriesCurrenciesSignedAmountsAndOriginalPrecision(){
        var source=event("NEW_PROVIDER_KIND","USD");String canonical=GatewaySettlementFacts.events(List.of(source));
        var restored=GatewaySettlementFacts.readEvents(canonical).getFirst();
        assertThat(restored).isEqualTo(source);
        assertThat(restored.reportedAmount()).isEqualTo(new BigDecimal("-7.12345678"));
        assertThat(restored.currencyCode()).isEqualTo("USD");
        assertThat(restored.reportedStatus()).isEqualTo("NEW_PROVIDER_STATUS");
        assertThat(restored.reportedSettlementAmount()).isNull();
        assertThat(restored.occurredAt()).isEqualTo(WHEN);
    }

    @Test void repeatedEventIdentityAcrossDifferentKindsIsRetainedWithoutSilentDeduplication(){
        var one=event("PAYMENT","INR");var two=event("DISPUTE","INR");
        var restored=GatewaySettlementFacts.readEvents(GatewaySettlementFacts.events(List.of(one,two)));
        assertThat(restored).containsExactly(one,two);
        assertThat(restored).extracting(GatewaySettlementProvider.SettlementEvent::eventReference).containsExactly(EVENT,EVENT);
    }

    @Test void emptyCompleteArraysAreValidButNullUnknownIsNotFabricatedAsAnEmptyCompleteProjection(){
        assertThat(GatewaySettlementFacts.summaries(List.of())).isEqualTo("[]");
        assertThat(GatewaySettlementFacts.events(List.of())).isEqualTo("[]");
        assertThat(GatewaySettlementFacts.readSummaries("[]")).isEmpty();
        assertThat(GatewaySettlementFacts.readEvents("[]")).isEmpty();
        assertThatThrownBy(()->GatewaySettlementFacts.readSummaries(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GatewaySettlementFacts.readEvents("null")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GatewaySettlementFacts.readSummaries("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void restoredCollectionsAndNestedRefundReferencesAreImmutable(){
        var summaries=GatewaySettlementFacts.readSummaries(GatewaySettlementFacts.summaries(List.of(summary())));
        var events=GatewaySettlementFacts.readEvents(GatewaySettlementFacts.events(List.of(event("PAYMENT","INR"))));
        assertThatThrownBy(()->summaries.add(summary())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->events.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->events.getFirst().refundReferences().add("synthetic_other_refund")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void privateAndUnknownJsonPropertiesAreRejectedRatherThanArchivedOrSilentlyStripped(){
        String summaries=GatewaySettlementFacts.summaries(List.of(summary()));
        String events=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));
        for(String field:List.of("customer_details","bank_account","card_number","refund_notes","unexpected")){
            assertThatThrownBy(()->GatewaySettlementFacts.readSummaries(summaries.replaceFirst("\\{","{\""+field+"\":\"synthetic_private_marker\",")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Canonical minimal reported settlement projection required").hasNoCause();
            assertThatThrownBy(()->GatewaySettlementFacts.readEvents(events.replaceFirst("\\{","{\""+field+"\":\"synthetic_private_marker\",")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Canonical minimal reported settlement projection required").hasNoCause();
        }
    }

    @Test void duplicatePropertiesCannotReplaceAnEarlierReportedFact(){
        String summaries=GatewaySettlementFacts.summaries(List.of(summary()));
        String events=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));
        assertThatThrownBy(()->GatewaySettlementFacts.readSummaries(summaries.replaceFirst("\\{","{\"reportedStatus\":\"SUCCESS\",")))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(()->GatewaySettlementFacts.readEvents(events.replaceFirst("\\{","{\"eventReference\":\"synthetic_shadowed_event\",")))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void trailingDataWhitespaceAndNoncanonicalKeyOrderDoNotCreateAlternativeEvidenceEncodings(){
        String canonical=GatewaySettlementFacts.summaries(List.of(summary()));
        for(String altered:List.of(canonical+"[]",canonical+" false"," "+canonical,canonical+"\n",
                canonical.replaceFirst("\\{","{ "),canonical.replace("\"reportedStatus\":\"SUCCESS\",","")
                        .replaceFirst("\\{","{\"reportedStatus\":\"SUCCESS\","))){
            assertThatThrownBy(()->GatewaySettlementFacts.readSummaries(altered)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void missingRequiredOrWronglyTypedFieldsAreNotRepairedIntoSuccessfulFacts(){
        String canonical=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));
        for(String altered:List.of(canonical.replace("\"reportedAmount\":\"-7.12345678\"","\"reportedAmount\":null"),
                canonical.replace("\"occurredAt\":\""+WHEN+"\"","\"occurredAt\":null"),
                canonical.replace("\"currencyCode\":\"INR\"","\"currencyCode\":3"),
                canonical.replace("\"refundReferences\":[\"synthetic_refund\"]","\"refundReferences\":\"synthetic_refund\""),
                canonical.replace("\"eventReference\":\""+EVENT+"\",",""))){
            assertThatThrownBy(()->GatewaySettlementFacts.readEvents(altered)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void numericEncodingRoundingScientificNotationAndOutOfRangeMoneyAreRejected(){
        String canonical=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));
        for(String value:List.of("-7.123456789","01.00","1e2","100000000.00000001","-100000000.00000001","-0.00")){
            assertThatThrownBy(()->GatewaySettlementFacts.readEvents(canonical.replace("\"-7.12345678\"","\""+value+"\"")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->GatewaySettlementFacts.readEvents(canonical.replace("\"-7.12345678\"","-7.12345678")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void noncanonicalOrOutOfRangeEventTimeCannotBeAcceptedThroughEquivalentInstantParsing(){
        String canonical=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));
        for(String value:List.of("2026-10-22T12:30:00.123456789+00:00","2026-10-22T12:30:00.1234567890Z",
                "1999-12-31T23:59:59Z","2100-01-01T00:00:00.000000001Z","synthetic_invalid_time")){
            assertThatThrownBy(()->GatewaySettlementFacts.readEvents(canonical.replace(WHEN.toString(),value)))
                    .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        }
    }

    @Test void pageLimitsRejectExcessRowsOnBothEncodingAndParsingWithoutTruncation(){
        var summaries=Collections.nCopies(11,summary());var events=Collections.nCopies(11,event("PAYMENT","INR"));
        assertThatThrownBy(()->GatewaySettlementFacts.summaries(summaries)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GatewaySettlementFacts.events(events)).isInstanceOf(IllegalArgumentException.class);
        String summaryRow=GatewaySettlementFacts.summaries(List.of(summary()));summaryRow=summaryRow.substring(1,summaryRow.length()-1);
        String eventRow=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));eventRow=eventRow.substring(1,eventRow.length()-1);
        String tooManySummaries="["+String.join(",",Collections.nCopies(11,summaryRow))+"]";
        String tooManyEvents="["+String.join(",",Collections.nCopies(11,eventRow))+"]";
        assertThatThrownBy(()->GatewaySettlementFacts.readSummaries(tooManySummaries)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GatewaySettlementFacts.readEvents(tooManyEvents)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidPayloadDiagnosticsNeverEchoPrivateInputOrParserSource(){
        for(String source:List.of("[{\"private_payload\":\"synthetic_private_marker\"}]","synthetic_private_marker", "[\"synthetic_private_marker\"]")){
            assertThatThrownBy(()->GatewaySettlementFacts.readEvents(source)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Canonical minimal reported settlement projection required").hasNoCause();
        }
    }

    @Test void exactReadFingerprintDistinguishesUnknownEmptyFactsCursorsAndIndependentReads(){
        var binding=binding();var query=prepared(binding,UUID.randomUUID(),"synthetic_cursor");UUID readId=UUID.randomUUID();
        String empty=GatewaySettlementReadStore.fingerprint(query,readId,"[]",null);
        assertThat(empty).matches("[a-f0-9]{64}")
                .isNotEqualTo(GatewaySettlementReadStore.fingerprint(query,readId,null,null))
                .isNotEqualTo(GatewaySettlementReadStore.fingerprint(query,readId,"[]","synthetic_next_cursor"))
                .isNotEqualTo(GatewaySettlementReadStore.fingerprint(query,UUID.randomUUID(),"[]",null))
                .isNotEqualTo(GatewaySettlementReadStore.fingerprint(prepared(binding,UUID.randomUUID(),"synthetic_cursor"),readId,"[]",null))
                .isNotEqualTo(GatewaySettlementReadStore.fingerprint(prepared(binding,query.id(),"synthetic_other_cursor"),readId,"[]",null))
                .isEqualTo(GatewaySettlementReadStore.fingerprint(query,readId,"[]",null));
    }

    @Test void fingerprintRetainsFractionalReportedValueAndExactTimeDifferences(){
        var query=prepared(binding(),UUID.randomUUID(),null);UUID readId=UUID.randomUUID();
        String canonical=GatewaySettlementFacts.events(List.of(event("PAYMENT","INR")));
        String original=GatewaySettlementReadStore.fingerprint(query,readId,canonical,null);
        assertThat(original).isNotEqualTo(GatewaySettlementReadStore.fingerprint(query,readId,
                canonical.replace("-7.12345678","-7.12345679"),null))
                .isNotEqualTo(GatewaySettlementReadStore.fingerprint(query,readId,
                        canonical.replace(WHEN.toString(),WHEN.plusNanos(1).toString()),null));
    }

    @Test void privatePreparedAndReadDiagnosticsDoNotExposeBindingsCursorsReferencesOrAmounts(){
        var binding=binding();var query=prepared(binding,UUID.randomUUID(),"synthetic_private_cursor");
        var read=new GatewaySettlementReadStore.Read(UUID.randomUUID(),query,WHEN,true,
                new GatewaySettlementProvider.SettlementPage("synthetic_next_cursor",List.of(summary())),null,"a".repeat(64));
        for(String diagnostic:List.of(query.toString(),read.toString(),read.summary().toString(),summary().toString(),event("PAYMENT","INR").toString())){
            assertThat(diagnostic).doesNotContain(binding.organisationId().toString(),binding.schoolId().toString(),binding.merchantReference(),
                    binding.credentialsReference(),"synthetic_private_cursor","synthetic_next_cursor",SETTLEMENT,EVENT,"1000.12345678");
        }
        assertThatThrownBy(()->query.eventsQuery(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingHumanWrongTenantAndReadOnlyScopeFailBeforeDatabaseOrEncryption(){
        var jdbc=mock(NamedParameterJdbcTemplate.class);var cipher=mock(SensitiveValueCipher.class);
        var store=new GatewaySettlementReadStore(jdbc,cipher);var binding=binding();
        assertThatThrownBy(()->store.load(binding,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        try(var ignored=TenantRequestContext.open(UUID.randomUUID(),binding.organisationId())){
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(()->store.retainUnknown(binding,UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        }
        try(var ignored=TenantRequestContext.open(null,UUID.randomUUID())){
            assertThatThrownBy(()->store.prepareSummaries(binding,UUID.randomUUID(),WHEN.minusSeconds(60),WHEN)).isInstanceOf(IllegalStateException.class);
        }
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThatThrownBy(()->store.read(binding,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(()->store.retainSummaries(binding,UUID.randomUUID(),UUID.randomUUID(),new GatewaySettlementProvider.SettlementPage(null,List.of())))
                    .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc,cipher);
    }

    private static GatewaySettlementProvider.SettlementSummary summary(){
        return new GatewaySettlementProvider.SettlementSummary(SETTLEMENT,"SUCCESS","synthetic_transfer",
                WHEN.minusSeconds(120),WHEN.minusSeconds(60),WHEN,WHEN.plusNanos(1),
                new BigDecimal("1000.12345678"),new BigDecimal("1.00000000"),null,new BigDecimal("-0.12345678"),
                null,null,null,null,null,new BigDecimal("999.12345678"),"PREPAID","STANDARD");
    }
    private static GatewaySettlementProvider.SettlementEvent event(String kind,String currency){
        return new GatewaySettlementProvider.SettlementEvent(SETTLEMENT,EVENT,kind,"DEBIT","NEW_PROVIDER_STATUS",currency,
                new BigDecimal("-7.12345678"),new BigDecimal("0.10000000"),null,null,WHEN,
                "synthetic_order","synthetic_payment",List.of("synthetic_refund"),null);
    }
    private static GatewayWorkQueue.ProviderBinding binding(){return new GatewayWorkQueue.ProviderBinding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            "CASHFREE","TEST","synthetic_merchant","synthetic:unconfigured");}
    private static GatewaySettlementReadStore.Prepared prepared(GatewayWorkQueue.ProviderBinding binding,UUID id,String cursor){
        return new GatewaySettlementReadStore.Prepared(id,binding,GatewaySettlementReadStore.Kind.SUMMARY,id,1,null,null,
                WHEN.minusSeconds(60),WHEN,null,cursor,WHEN);
    }
}
