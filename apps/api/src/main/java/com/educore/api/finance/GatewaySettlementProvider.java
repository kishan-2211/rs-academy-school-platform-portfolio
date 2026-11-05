package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Optional server-only, read-only provider capability. These are reported facts,
 * not bank confirmation, arithmetic identities, matching authority or journals.
 * No customer/card/contact payload, credentials or free-form provider notes belong here.
 * Reported precision is retained, not rounded into a currency's posting precision.
 */
public interface GatewaySettlementProvider {
    int PAGE_SIZE=10;
    SettlementPage fetchSettlements(SettlementQuery query);
    SettlementEventsPage fetchSettlementEvents(SettlementEventsQuery query);

    record SettlementQuery(UUID readId,Instant from,Instant until,String cursor) {
        public SettlementQuery {
            Objects.requireNonNull(readId,"Independent read identity required");
            if(from==null||until==null||!from.isBefore(until)||Duration.between(from,until).compareTo(Duration.ofDays(31))>0
                    ||from.isBefore(Instant.parse("2000-01-01T00:00:00Z"))||until.isAfter(Instant.parse("2100-01-01T00:00:00Z")))throw invalid();
            cursor=checkedCursor(cursor);
        }
        @Override public String toString(){return "SettlementQuery[private bounded independent read]";}
    }
    record SettlementEventsQuery(UUID readId,String settlementReference,String cursor) {
        public SettlementEventsQuery {
            Objects.requireNonNull(readId,"Independent read identity required");
            PaymentProviderAdapter.requireReference(settlementReference);cursor=checkedCursor(cursor);
        }
        @Override public String toString(){return "SettlementEventsQuery[private same-settlement read]";}
    }
    record SettlementPage(String nextCursor,List<SettlementSummary> items) {
        public SettlementPage {nextCursor=checkedCursor(nextCursor);items=List.copyOf(items);if(items.size()>PAGE_SIZE)throw invalid();}
        @Override public String toString(){return "SettlementPage[private reported facts, not bank evidence]";}
    }
    record SettlementEventsPage(String nextCursor,List<SettlementEvent> items) {
        public SettlementEventsPage {nextCursor=checkedCursor(nextCursor);items=List.copyOf(items);if(items.size()>PAGE_SIZE)throw invalid();}
        @Override public String toString(){return "SettlementEventsPage[private reported event facts]";}
    }

    /** The summary API does not state currency: do not silently assume INR or treat amounts as bank money. */
    record SettlementSummary(String settlementReference,String reportedStatus,String transferReference,
            Instant paymentsFrom,Instant paymentsThrough,Instant initiatedAt,Instant processedAt,
            BigDecimal reportedPaymentAmount,BigDecimal reportedServiceCharge,BigDecimal reportedServiceTax,
            BigDecimal reportedAdjustment,BigDecimal reportedSettlementCharge,BigDecimal reportedSettlementTax,
            BigDecimal reportedSplitCharge,BigDecimal reportedSplitTax,BigDecimal reportedVendorCommission,
            BigDecimal reportedAmountSettled,String reportedChargeType,String reportedSettlementType) {
        public SettlementSummary {
            PaymentProviderAdapter.requireReference(settlementReference);checkedTag(reportedStatus);checkedTransferReference(transferReference);
            if(paymentsFrom!=null&&paymentsThrough!=null&&paymentsFrom.isAfter(paymentsThrough))throw invalid();
            reportedPaymentAmount=checkedMoney(reportedPaymentAmount);reportedServiceCharge=checkedMoney(reportedServiceCharge);
            reportedServiceTax=checkedMoney(reportedServiceTax);reportedAdjustment=checkedMoney(reportedAdjustment);
            reportedSettlementCharge=checkedMoney(reportedSettlementCharge);reportedSettlementTax=checkedMoney(reportedSettlementTax);
            reportedSplitCharge=checkedMoney(reportedSplitCharge);reportedSplitTax=checkedMoney(reportedSplitTax);
            reportedVendorCommission=checkedMoney(reportedVendorCommission);reportedAmountSettled=checkedMoney(reportedAmountSettled);
            if(reportedChargeType!=null)checkedTag(reportedChargeType);if(reportedSettlementType!=null)checkedTag(reportedSettlementType);
        }
        @Override public String toString(){return "SettlementSummary[private reported facts, not matched bank credit]";}
    }

    /** Event identity must include its kind and statement; event ID alone is not a global economic key. */
    record SettlementEvent(String settlementReference,String eventReference,String reportedType,String reportedDirection,
            String reportedStatus,String currencyCode,BigDecimal reportedAmount,BigDecimal reportedServiceCharge,
            BigDecimal reportedServiceTax,BigDecimal reportedSettlementAmount,Instant occurredAt,
            String orderReference,String paymentReference,List<String> refundReferences,String chargesCurrencyCode) {
        public SettlementEvent {
            PaymentProviderAdapter.requireReference(settlementReference);PaymentProviderAdapter.requireReference(eventReference);
            checkedTag(reportedType);checkedTag(reportedDirection);checkedTag(reportedStatus);checkedCurrency(currencyCode);
            if(reportedAmount==null)throw invalid();reportedAmount=checkedMoney(reportedAmount);
            reportedServiceCharge=checkedMoney(reportedServiceCharge);reportedServiceTax=checkedMoney(reportedServiceTax);
            reportedSettlementAmount=checkedMoney(reportedSettlementAmount);Objects.requireNonNull(occurredAt,"Reported event time required");
            if(orderReference!=null)PaymentProviderAdapter.requireReference(orderReference);
            if(paymentReference!=null)PaymentProviderAdapter.requireReference(paymentReference);
            refundReferences=List.copyOf(refundReferences);if(refundReferences.size()>100)throw invalid();
            refundReferences.forEach(PaymentProviderAdapter::requireReference);
            if(chargesCurrencyCode!=null)checkedCurrency(chargesCurrencyCode);
        }
        @Override public String toString(){return "SettlementEvent[private reported event, not permission to post]";}
    }

    private static String checkedCursor(String value){if(value!=null&&!value.matches("[A-Za-z0-9+/_=.\\-]{1,4096}"))throw invalid();return value;}
    private static void checkedTag(String value){if(value==null||!value.matches("[A-Z][A-Z0-9_]{0,59}"))throw invalid();}
    private static void checkedCurrency(String value){if(value==null||!value.matches("[A-Z]{3}"))throw invalid();}
    private static void checkedTransferReference(String value){if(value!=null&&!value.matches("[A-Za-z0-9][A-Za-z0-9_.:/\\-]{0,119}"))throw invalid();}
    private static BigDecimal checkedMoney(BigDecimal value){
        if(value==null)return null;
        if(value.scale()>8||value.abs().compareTo(new BigDecimal("100000000.00"))>0)throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Bounded exact reported settlement facts required");}
}
