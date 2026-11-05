package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact minimal canonical projection, never an archive of the provider's raw response. */
final class GatewaySettlementFacts {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(262144).maxNestingDepth(4)
                    .maxStringLength(120).maxNameLength(40).maxTokenCount(16384).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> SUMMARY_FIELDS=Set.of("settlementReference","reportedStatus","transferReference",
            "paymentsFrom","paymentsThrough","initiatedAt","processedAt","reportedPaymentAmount","reportedServiceCharge",
            "reportedServiceTax","reportedAdjustment","reportedSettlementCharge","reportedSettlementTax","reportedSplitCharge",
            "reportedSplitTax","reportedVendorCommission","reportedAmountSettled","reportedChargeType","reportedSettlementType");
    private static final Set<String> EVENT_FIELDS=Set.of("settlementReference","eventReference","reportedType","reportedDirection",
            "reportedStatus","currencyCode","reportedAmount","reportedServiceCharge","reportedServiceTax","reportedSettlementAmount",
            "occurredAt","orderReference","paymentReference","refundReferences","chargesCurrencyCode");

    private GatewaySettlementFacts(){}

    static String summaries(List<GatewaySettlementProvider.SettlementSummary> rows){
        if(rows==null||rows.size()>GatewaySettlementProvider.PAGE_SIZE)throw invalid();
        return JSON.writeValueAsString(rows.stream().map(row->map(
                "settlementReference",row.settlementReference(),"reportedStatus",row.reportedStatus(),"transferReference",row.transferReference(),
                "paymentsFrom",row.paymentsFrom(),"paymentsThrough",row.paymentsThrough(),"initiatedAt",row.initiatedAt(),"processedAt",row.processedAt(),
                "reportedPaymentAmount",row.reportedPaymentAmount(),"reportedServiceCharge",row.reportedServiceCharge(),"reportedServiceTax",row.reportedServiceTax(),
                "reportedAdjustment",row.reportedAdjustment(),"reportedSettlementCharge",row.reportedSettlementCharge(),"reportedSettlementTax",row.reportedSettlementTax(),
                "reportedSplitCharge",row.reportedSplitCharge(),"reportedSplitTax",row.reportedSplitTax(),"reportedVendorCommission",row.reportedVendorCommission(),
                "reportedAmountSettled",row.reportedAmountSettled(),"reportedChargeType",row.reportedChargeType(),"reportedSettlementType",row.reportedSettlementType())).toList());
    }

    static String events(List<GatewaySettlementProvider.SettlementEvent> rows){
        if(rows==null||rows.size()>GatewaySettlementProvider.PAGE_SIZE)throw invalid();
        return JSON.writeValueAsString(rows.stream().map(row->map(
                "settlementReference",row.settlementReference(),"eventReference",row.eventReference(),"reportedType",row.reportedType(),
                "reportedDirection",row.reportedDirection(),"reportedStatus",row.reportedStatus(),"currencyCode",row.currencyCode(),
                "reportedAmount",row.reportedAmount(),"reportedServiceCharge",row.reportedServiceCharge(),"reportedServiceTax",row.reportedServiceTax(),
                "reportedSettlementAmount",row.reportedSettlementAmount(),"occurredAt",row.occurredAt(),"orderReference",row.orderReference(),
                "paymentReference",row.paymentReference(),"refundReferences",row.refundReferences(),"chargesCurrencyCode",row.chargesCurrencyCode())).toList());
    }

    static List<GatewaySettlementProvider.SettlementSummary> readSummaries(String canonical){
        var rows=new ArrayList<GatewaySettlementProvider.SettlementSummary>();
        for(var row:array(canonical)){
            fields(row,SUMMARY_FIELDS);
            rows.add(new GatewaySettlementProvider.SettlementSummary(text(row,"settlementReference"),text(row,"reportedStatus"),text(row,"transferReference"),
                    time(row,"paymentsFrom"),time(row,"paymentsThrough"),time(row,"initiatedAt"),time(row,"processedAt"),
                    money(row,"reportedPaymentAmount"),money(row,"reportedServiceCharge"),money(row,"reportedServiceTax"),money(row,"reportedAdjustment"),
                    money(row,"reportedSettlementCharge"),money(row,"reportedSettlementTax"),money(row,"reportedSplitCharge"),money(row,"reportedSplitTax"),
                    money(row,"reportedVendorCommission"),money(row,"reportedAmountSettled"),text(row,"reportedChargeType"),text(row,"reportedSettlementType")));
        }
        if(!summaries(rows).equals(canonical))throw invalid();return List.copyOf(rows);
    }

    static List<GatewaySettlementProvider.SettlementEvent> readEvents(String canonical){
        var rows=new ArrayList<GatewaySettlementProvider.SettlementEvent>();
        for(var row:array(canonical)){
            fields(row,EVENT_FIELDS);var refs=row.get("refundReferences");
            if(refs==null||!refs.isArray()||refs.size()>100)throw invalid();
            var refunds=new ArrayList<String>();for(var ref:refs){if(!ref.isString())throw invalid();refunds.add(ref.stringValue());}
            rows.add(new GatewaySettlementProvider.SettlementEvent(text(row,"settlementReference"),text(row,"eventReference"),text(row,"reportedType"),
                    text(row,"reportedDirection"),text(row,"reportedStatus"),text(row,"currencyCode"),money(row,"reportedAmount"),money(row,"reportedServiceCharge"),
                    money(row,"reportedServiceTax"),money(row,"reportedSettlementAmount"),time(row,"occurredAt"),text(row,"orderReference"),
                    text(row,"paymentReference"),refunds,text(row,"chargesCurrencyCode")));
        }
        if(!events(rows).equals(canonical))throw invalid();return List.copyOf(rows);
    }

    private static JsonNode array(String source){
        try{if(source==null||source.length()>262144)throw invalid();var rows=JSON.readTree(source);
            if(rows==null||!rows.isArray()||rows.size()>GatewaySettlementProvider.PAGE_SIZE)throw invalid();return rows;
        }catch(RuntimeException failed){throw invalid();}
    }
    private static void fields(JsonNode row,Set<String> expected){
        if(row==null||!row.isObject()||row.size()!=expected.size()||!expected.containsAll(row.propertyNames()))throw invalid();
    }
    private static Map<String,Object> map(Object... pairs){
        var value=new TreeMap<String,Object>();for(int i=0;i<pairs.length;i+=2){Object item=pairs[i+1];
            value.put((String)pairs[i],item instanceof Instant instant?instant.toString():item instanceof BigDecimal money?money.toPlainString():item);}
        return value;
    }
    private static String text(JsonNode row,String key){var value=row.get(key);if(value==null||value.isNull())return null;if(!value.isString())throw invalid();return value.stringValue();}
    private static BigDecimal money(JsonNode row,String key){String value=text(row,key);if(value==null)return null;
        if(!value.matches("-?(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,8})?"))throw invalid();return new BigDecimal(value);}
    private static Instant time(JsonNode row,String key){String value=text(row,key);if(value==null)return null;
        try{var result=Instant.parse(value);if(!result.toString().equals(value)||result.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                ||result.isAfter(Instant.parse("2100-01-01T00:00:00Z")))throw invalid();return result;}catch(RuntimeException failed){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Canonical minimal reported settlement projection required");}
}
