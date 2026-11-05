package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Pure bounded human report intent; never provider activation, bank matching or money. */
final class GatewaySettlementTerms {
    static final Set<String> STATUSES=Set.of("ALL","REQUESTED","SYNCING","RETRYING","COMPLETE","REVIEW_REQUIRED");
    static final Set<String> KINDS=Set.of("ALL","SUMMARY","EVENTS");
    record Terms(UUID schoolId,UUID clientRequestId,String schoolTimeZone,String kind,Instant from,Instant until,
            UUID sourceSummaryReadId,String settlementReference,String fingerprint){
        @Override public String toString(){return "Terms[private bounded settlement report intent]";}
    }
    private GatewaySettlementTerms(){}
    static Terms prepare(GatewaySettlementModels.Submit command){
        if(command==null||command.schoolId()==null||command.clientRequestId()==null||command.schoolTimeZone()==null
                ||!Set.of("SUMMARY","EVENTS").contains(command.kind()==null?"":command.kind()))throw invalid();
        try{
            if(command.schoolTimeZone().length()>60||!ZoneId.of(command.schoolTimeZone()).getId().equals(command.schoolTimeZone()))throw invalid();
            if("SUMMARY".equals(command.kind())){
                if(command.sourceSummaryReadId()!=null||command.settlementReference()!=null)throw invalid();
                new GatewaySettlementProvider.SettlementQuery(command.clientRequestId(),command.from(),command.until(),null);
            }else{
                if(command.from()!=null||command.until()!=null||command.sourceSummaryReadId()==null)throw invalid();
                new GatewaySettlementProvider.SettlementEventsQuery(command.clientRequestId(),command.settlementReference(),null);
            }
        }catch(RuntimeException bad){throw invalid();}
        var frame=new StringBuilder("SETTLEMENT_REQUEST_V1");
        for(Object value:new Object[]{command.schoolId(),command.schoolTimeZone(),command.kind(),command.from(),command.until(),command.sourceSummaryReadId(),command.settlementReference()}){
            String text=value==null?null:value.toString();frame.append('|').append(text==null?-1:text.length()).append(':');if(text!=null)frame.append(text);
        }
        return new Terms(command.schoolId(),command.clientRequestId(),command.schoolTimeZone(),command.kind(),command.from(),command.until(),
                command.sourceSummaryReadId(),command.settlementReference(),hash(frame.toString()));
    }
    static String search(String query){
        if(query==null)return "";
        if(query.length()>100||query.codePoints().anyMatch(Character::isISOControl))throw filters();
        return query.strip();
    }
    static String like(String literal){return "%"+literal.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    static void page(int page,int size){if(page<0||page>10000||size<1||size>50)throw filters();}
    static ApiException filters(){return new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_SETTLEMENT_FILTER_INVALID","Choose a school, bounded page and literal search up to 100 characters.");}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_SETTLEMENT_TERMS_INVALID","Choose the school timezone and either a bounded past summary interval or an observed settlement reference.");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 required");}}
}
