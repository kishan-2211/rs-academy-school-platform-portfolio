package com.educore.api.finance;

import static com.educore.api.finance.GatewaySettlementProvider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Mock transport fixtures only. No merchant request, database or bank acceptance. */
class CashfreeSettlementProviderTest {
    private static final Instant NOW=Instant.parse("2031-03-02T04:30:00Z");
    private static final String SETTLEMENT="synthetic_settlement_A",PRIVATE="SYNTHETIC_PRIVATE_CONTACT_BANK_CARD_MARKER";
    private static final JsonMapper JSON=JsonMapper.builder().build();

    @Test void summaryReadBindsExactWindowPageAndReadIdentityWithoutCreatingAnyFinancialCommand() throws Exception {
        var f=new Fixture(page(null,List.of(summary())),page(null,List.of(summary())));var query=query(null);
        var first=f.adapter.fetchSettlements(query);assertThat(f.adapter.fetchSettlements(query)).isEqualTo(first);
        assertThat(first.items()).hasSize(1);assertThat(first.nextCursor()).isNull();assertThat(f.sent).hasSize(2);
        for(var request:f.sent){
            assertThat(request.method()).isEqualTo("POST");assertThat(request.uri().toString()).isEqualTo("https://sandbox.cashfree.com/pg/settlements");
            assertThat(request.timeout()).contains(Duration.ofSeconds(20));assertThat(request.headers().firstValue("x-api-version")).contains("2026-01-01");
            assertThat(request.headers().firstValue("x-idempotency-key")).contains(query.readId().toString());
            var body=JSON.readTree(bytes(request));assertThat(body.get("pagination").get("limit").intValue()).isEqualTo(PAGE_SIZE);
            assertThat(body.get("pagination").get("cursor").isNull()).isTrue();
            assertThat(body.get("filters").get("start_date").stringValue()).isEqualTo(query.from().toString());
            assertThat(body.get("filters").get("end_date").stringValue()).isEqualTo(query.until().toString());
            assertThat(body.toString()).doesNotContain("customer","refund_id","account","synthetic-secret",PRIVATE);
        }
        assertThat(f.closed).hasSize(2).allMatch(stream->stream.closed);
    }

    @Test void eventReadBindsOneStatementAndKeepsExactFactsWithoutCustomerOrProviderNotes() throws Exception {
        var f=new Fixture(page(null,List.of(event("PAYMENT"))));var query=eventQuery(null);
        var read=f.adapter.fetchSettlementEvents(query);var fact=read.items().getFirst();var request=f.sent.getFirst();
        assertThat(request.uri().toString()).isEqualTo("https://sandbox.cashfree.com/pg/settlement/recon");
        var body=JSON.readTree(bytes(request));assertThat(body.get("filters").get("cf_settlement_ids").get(0).stringValue()).isEqualTo(SETTLEMENT);
        assertThat(body.get("filters").size()).isEqualTo(1);assertThat(fact.occurredAt()).isEqualTo(NOW.minusSeconds(30).plusNanos(123));
        assertThat(fact.reportedAmount()).isEqualByComparingTo("17.29");assertThat(fact.orderReference()).isEqualTo("synthetic_order_A");
        assertThat(fact.paymentReference()).isEqualTo("12345678901234567890");assertThat(fact.refundReferences()).containsExactly("synthetic_refund_A");
        assertThat(JSON.writeValueAsString(read)).doesNotContain(PRIVATE,"customer_details","event_remarks","bank_reference","refund_note","status_description");
    }

    @Test void cursorsArePrivateBoundedContinuationNotUrlsAndEmptyCursorTerminates() throws Exception {
        var f=new Fixture(page("synthetic_cursor_2+/=",List.of()),page("",List.of()));
        var first=f.adapter.fetchSettlements(query("synthetic_cursor_1"));assertThat(first.nextCursor()).isEqualTo("synthetic_cursor_2+/=");
        assertThat(f.adapter.fetchSettlements(query(first.nextCursor())).nextCursor()).isNull();
        assertThat(JSON.readTree(bytes(f.sent.getFirst())).get("pagination").get("cursor").stringValue()).isEqualTo("synthetic_cursor_1");
        assertThat(f.sent.getFirst().uri().getRawQuery()).isNull();
    }

    @Test void reportedAmountsAreNotRecalculatedAndUnknownStatusesRemainExplicit() throws Exception {
        var row=summary();row.put("status","FUTURE_PROVIDER_REVIEW");row.put("pg_service_charge","-0.12345678");row.put("amount_settled","17.29");
        var fact=new Fixture(page(null,List.of(row))).adapter.fetchSettlements(query(null)).items().getFirst();
        assertThat(fact.reportedStatus()).isEqualTo("FUTURE_PROVIDER_REVIEW");assertThat(fact.reportedServiceCharge()).isEqualByComparingTo("-0.12345678");
        assertThat(fact.reportedAmountSettled()).isEqualByComparingTo("17.29");assertThat(fact.reportedAdjustment()).isEqualByComparingTo("-1.00");
        assertThat(JSON.valueToTree(fact).has("currencyCode")).isFalse(); // The summary has no currency proof.
    }

    @Test void missingAmountsAndPendingTimesNeverBecomeZeroOrConfirmedBankCredit() throws Exception {
        var row=new LinkedHashMap<String,Object>();row.put("cf_settlement_id",SETTLEMENT);row.put("status","PENDING_WITH_BANK");row.put("settlement_processed_on","");
        var fact=new Fixture(page(null,List.of(row))).adapter.fetchSettlements(query(null)).items().getFirst();
        assertThat(fact.reportedAmountSettled()).isNull();assertThat(fact.reportedServiceTax()).isNull();assertThat(fact.processedAt()).isNull();
        assertThat(fact.transferReference()).isNull();assertThat(fact.reportedStatus()).isEqualTo("PENDING_WITH_BANK");
    }

    @Test void repeatedEventIdsAcrossKindsAndUnexpectedCurrenciesAreNotSilentlyDroppedOrConverted() throws Exception {
        var payment=event("PAYMENT");var dispute=event("DISPUTE");nested(dispute,"event_details").put("event_currency","USD");nested(dispute,"payment_details").put("charges_currency","EUR");
        var page=new Fixture(page(null,List.of(payment,dispute))).adapter.fetchSettlementEvents(eventQuery(null));
        assertThat(page.items()).hasSize(2);assertThat(page.items().get(0).eventReference()).isEqualTo(page.items().get(1).eventReference());
        assertThat(page.items().get(1).reportedType()).isEqualTo("DISPUTE");assertThat(page.items().get(1).currencyCode()).isEqualTo("USD");assertThat(page.items().get(1).chargesCurrencyCode()).isEqualTo("EUR");
    }

    @Test void foreignStatementInReconResponseFailsClosedInsteadOfFilteringItOut() throws Exception {
        var row=event("REFUND");row.put("settlement_details",Map.of("cf_settlement_id","synthetic_other_statement"));
        var f=new Fixture(page(null,List.of(row)));unknown(()->f.adapter.fetchSettlementEvents(eventQuery(null)));assertThat(f.closed.getFirst().closed).isTrue();
    }

    @Test void malformedSummaryMoneyAndUnsafeIdentifiersRemainUnknownWithoutRounding() throws Exception {
        for(Object bad:List.of("1.000000001","1e2"," 1.00","01.00","100000000.01",true,Map.of("private",PRIVATE))){
            var row=summary();row.put("amount_settled",bad);unknown(()->new Fixture(page(null,List.of(row))).adapter.fetchSettlements(query(null)));
        }
        for(String bad:List.of("synthetic/unsafe","x".repeat(101),"",PRIVATE+"\n")){
            var row=summary();row.put("cf_settlement_id",bad);unknown(()->new Fixture(page(null,List.of(row))).adapter.fetchSettlements(query(null)));
        }
        var row=summary();row.put("pg_service_tax","0.000000001");unknown(()->new Fixture(page(null,List.of(row))).adapter.fetchSettlements(query(null)));
    }

    @Test void pageShapeLimitAndNonAdvancingCursorCannotClaimACompleteOrSafeTraversal() throws Exception {
        for(String body:List.of("{}","[]","{\"data\":[],\"limit\":10}","{\"data\":[],\"limit\":10,\"cursor\":12}",
                "{\"data\":[],\"limit\":11,\"cursor\":null}","{\"data\":[],\"limit\":-1,\"cursor\":null}",
                "{\"data\":[],\"limit\":10,\"cursor\":\"private cursor\"}","{\"data\":[],\"data\":[],\"limit\":10,\"cursor\":null}")){
            unknown(()->new Fixture(body).adapter.fetchSettlements(query(null)));
        }
        unknown(()->new Fixture(page("same_cursor",List.of())).adapter.fetchSettlements(query("same_cursor")));
        unknown(()->new Fixture(page(null,Collections.nCopies(11,summary()))).adapter.fetchSettlements(query(null)));
    }

    @Test void invalidQueriesNeverReachTransportOrBecomeArbitraryProviderPaths() throws Exception {
        var f=new Fixture(page(null,List.of()));
        assertThatThrownBy(()->new SettlementQuery(UUID.randomUUID(),NOW,NOW,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SettlementQuery(UUID.randomUUID(),NOW.minus(Duration.ofDays(32)),NOW,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SettlementQuery(UUID.randomUUID(),NOW.minusSeconds(1),NOW,"\nprivate")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SettlementEventsQuery(UUID.randomUUID(),"../synthetic_unsafe",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.adapter.fetchSettlements(new SettlementQuery(UUID.randomUUID(),NOW,NOW.plusSeconds(1),null)))
                .isInstanceOfSatisfying(CashfreePaymentProviderAdapter.ProviderFailure.class,error->assertThat(error.code()).isEqualTo(CashfreePaymentProviderAdapter.FailureCode.INVALID_REQUEST));
        assertThatThrownBy(()->f.adapter.fetchSettlementEvents(null)).isInstanceOf(CashfreePaymentProviderAdapter.ProviderFailure.class);assertThat(f.sent).isEmpty();
    }

    @Test void actualFutureOccurrenceTimesAreNotAcceptedAsObservedEvidence() throws Exception {
        var row=summary();row.put("settlement_processed_on",NOW.plusNanos(1).toString());unknown(()->new Fixture(page(null,List.of(row))).adapter.fetchSettlements(query(null)));
        var event=event("PAYMENT");nested(event,"event_details").put("event_time",NOW.plusSeconds(1).toString());unknown(()->new Fixture(page(null,List.of(event))).adapter.fetchSettlementEvents(eventQuery(null)));
    }

    @Test void nonSuccessfulHttpAndPrivatePayloadsNeverEscapeThroughCausesOrDiagnostics() throws Exception {
        var f=new Fixture(PRIVATE);f.status=503;unknown(()->f.adapter.fetchSettlements(query(null)));assertThat(f.closed.getFirst().closed).isTrue();
        var malformed=new Fixture(PRIVATE);unknown(()->malformed.adapter.fetchSettlementEvents(eventQuery(null)));
    }

    @Test void summaryAndEventSerializationRetainOnlyTheExplicitFinancialProjection() throws Exception {
        var summary=new Fixture(page(null,List.of(summary()))).adapter.fetchSettlements(query(null));
        var events=new Fixture(page(null,List.of(event("REFUND")))).adapter.fetchSettlementEvents(eventQuery(null));
        for(var value:List.of(summary,events)){assertThat(JSON.writeValueAsString(value)).doesNotContain(PRIVATE,"customer_details","customer_phone","bank_account","card","refund_note","event_remarks");assertThat(value.toString()).doesNotContain(SETTLEMENT,"17.29","synthetic_cursor");}
        assertThat(query("synthetic_cursor").toString()).doesNotContain("synthetic_cursor",NOW.toString());assertThat(eventQuery(null).toString()).doesNotContain(SETTLEMENT);
    }

    @Test void optionalNestedRelationsAreExplicitAndRequiredEventFactsCannotBeInvented() throws Exception {
        var row=event("RISK");row.remove("order_details");row.remove("payment_details");row.put("refund_details",null);
        var fact=new Fixture(page(null,List.of(row))).adapter.fetchSettlementEvents(eventQuery(null)).items().getFirst();
        assertThat(fact.orderReference()).isNull();assertThat(fact.paymentReference()).isNull();assertThat(fact.chargesCurrencyCode()).isNull();assertThat(fact.refundReferences()).isEmpty();
        for(String field:List.of("event_id","event_amount","event_time","event_currency","event_status","sale_type")){
            var invalid=event("PAYMENT");nested(invalid,"event_details").remove(field);unknown(()->new Fixture(page(null,List.of(invalid))).adapter.fetchSettlementEvents(eventQuery(null)));
        }
    }

    @Test void liveReportReadsStayOnTheFixedLiveHostAndNeverFollowRedirects() throws Exception {
        var f=new Fixture(page(null,List.of()));var live=new CashfreePaymentProviderAdapter(CashfreePaymentProviderAdapter.Environment.LIVE,"synthetic-client","synthetic-secret",URI.create("https://school.example.invalid"),Clock.fixed(NOW,ZoneOffset.UTC),f.client);
        live.fetchSettlements(query(null));assertThat(f.sent.getFirst().uri().toString()).isEqualTo("https://api.cashfree.com/pg/settlements");
        var redirected=new Fixture(PRIVATE);redirected.status=302;unknown(()->redirected.adapter.fetchSettlements(query(null)));assertThat(redirected.sent).hasSize(1);
    }

    @Test void valueObjectsKeepCollectionsImmutableAndExactSignedAmountsBounded() throws Exception {
        var page=new Fixture(page(null,List.of(summary()))).adapter.fetchSettlements(query(null));assertThatThrownBy(()->page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->new SettlementPage("",List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SettlementEventsPage(null,Collections.nCopies(11,new Fixture(page(null,List.of(event("PAYMENT")))).adapter.fetchSettlementEvents(eventQuery(null)).items().getFirst()))).isInstanceOf(IllegalArgumentException.class);
    }

    private static SettlementQuery query(String cursor){return new SettlementQuery(UUID.fromString("00000000-0000-4000-8000-000000000201"),NOW.minus(Duration.ofDays(1)),NOW,cursor);}
    private static SettlementEventsQuery eventQuery(String cursor){return new SettlementEventsQuery(UUID.fromString("00000000-0000-4000-8000-000000000202"),SETTLEMENT,cursor);}
    private static Map<String,Object> summary(){var row=new LinkedHashMap<String,Object>();row.put("cf_settlement_id",SETTLEMENT);row.put("status","SUCCESS");row.put("settlement_utr","SYN-UTR-ONLY");row.put("payment_from",NOW.minus(Duration.ofDays(2)).toString());row.put("payment_till",NOW.minusSeconds(3600).toString());row.put("settlement_initiated_on",NOW.minusSeconds(60).toString());row.put("settlement_processed_on",NOW.minusSeconds(30).toString());row.put("payment_amount","17.29");row.put("pg_service_charge","0.10");row.put("pg_service_tax","0.02");row.put("adjustment","-1.00");row.put("amount_settled","17.29");row.put("charge_type","PREPAID");row.put("settlement_type","NORMAL_SETTLEMENT");row.put("status_description",PRIVATE);row.put("customer_details",Map.of("customer_phone",PRIVATE));return row;}
    private static Map<String,Object> event(String type){var row=new LinkedHashMap<String,Object>();row.put("settlement_details",Map.of("cf_settlement_id",SETTLEMENT));var details=new LinkedHashMap<String,Object>();details.put("event_id","synthetic_event_A");details.put("event_type",type);details.put("sale_type","CREDIT");details.put("event_status","SUCCESS");details.put("event_currency","INR");details.put("event_amount","17.29");details.put("event_service_charge","0.10");details.put("event_settlement_amount","17.29");details.put("event_time",NOW.minusSeconds(30).plusNanos(123).toString());details.put("event_remarks",PRIVATE);row.put("event_details",details);row.put("order_details",Map.of("order_id","synthetic_order_A"));var payment=new LinkedHashMap<String,Object>();payment.put("cf_payment_id","12345678901234567890");payment.put("charges_currency","INR");payment.put("bank_reference",PRIVATE);row.put("payment_details",payment);row.put("refund_details",List.of(Map.of("refund_id","synthetic_refund_A","refund_note",PRIVATE)));row.put("customer_details",Map.of("customer_bank_account_number",PRIVATE,"customer_name",PRIVATE));return row;}
    @SuppressWarnings("unchecked") private static Map<String,Object> nested(Map<String,Object> row,String field){return (Map<String,Object>)row.get(field);}
    private static String page(String cursor,List<?> items){var value=new LinkedHashMap<String,Object>();value.put("cursor",cursor);value.put("limit",Math.max(1,items.size()));value.put("data",items);return JSON.writeValueAsString(value);}
    private static void unknown(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(CashfreePaymentProviderAdapter.ProviderFailure.class,error->{assertThat(error.code()).isEqualTo(CashfreePaymentProviderAdapter.FailureCode.UNKNOWN);assertThat(error.getCause()).isNull();assertThat(error.toString()).doesNotContain(PRIVATE,"synthetic-secret","https://");});}

    private static final class Fixture {
        final HttpClient client=mock(HttpClient.class);final List<HttpRequest> sent=new ArrayList<>();final List<ClosingStream> closed=new ArrayList<>();final CashfreePaymentProviderAdapter adapter;int status=200,index;
        @SuppressWarnings("unchecked") Fixture(String... bodies)throws Exception {
            when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);when(client.connectTimeout()).thenReturn(Optional.of(Duration.ofSeconds(5)));
            when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenAnswer(call->{sent.add(call.getArgument(0));var stream=new ClosingStream(bodies[index++].getBytes(StandardCharsets.UTF_8));closed.add(stream);HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(stream);return response;});
            adapter=new CashfreePaymentProviderAdapter(CashfreePaymentProviderAdapter.Environment.TEST,"synthetic-client","synthetic-secret",URI.create("https://school.example.invalid"),Clock.fixed(NOW,ZoneOffset.UTC),client);
        }
    }
    private static final class ClosingStream extends ByteArrayInputStream {boolean closed;ClosingStream(byte[] bytes){super(bytes);}@Override public void close()throws java.io.IOException{closed=true;super.close();}}
    private static byte[] bytes(HttpRequest request)throws Exception {
        var buffer=new ByteArrayOutputStream();var ready=new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer part){var bytes=new byte[part.remaining()];part.get(bytes);buffer.writeBytes(bytes);}
            public void onError(Throwable failure){ready.completeExceptionally(failure);}public void onComplete(){ready.complete(buffer.toByteArray());}
        });return ready.get(1,TimeUnit.SECONDS);
    }
}
