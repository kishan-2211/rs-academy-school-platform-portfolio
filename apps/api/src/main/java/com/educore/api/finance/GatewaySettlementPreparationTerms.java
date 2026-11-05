package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import static com.educore.api.finance.GatewaySettlementPreparationModels.*;

/** Exact reviewed terms, not a provider-specific guessed net formula or balancing plug. */
final class GatewaySettlementPreparationTerms {
    static final List<String> KEYS=List.of("SERVICE_CHARGE","SERVICE_TAX","ADJUSTMENT","SETTLEMENT_CHARGE",
            "SETTLEMENT_TAX","SPLIT_CHARGE","SPLIT_TAX","VENDOR_COMMISSION");
    record Terms(Command command,String fingerprint) {
        @Override public String toString(){return "Terms[private canonical settlement intent]";}
    }
    record Component(ComponentReview review,String basis,BigDecimal amount,ComponentFact fact) {
        @Override public String toString(){return "Component[private exact reviewed statement fact]";}
    }
    private GatewaySettlementPreparationTerms() {}

    static Terms normalize(Command command) {
        if(command==null||command.schoolId()==null||command.clientRequestId()==null||command.bankEvidenceId()==null
                ||command.financialYearId()==null||command.postingOn()==null||command.contextFingerprint()==null
                ||!command.contextFingerprint().matches("[a-f0-9]{64}")||!"INR".equals(command.summaryCurrencyCode())
                ||!command.confirmedSummaryCurrency()||!command.confirmedReviewed()
                ||!Set.of("SUMMARY","EVENTS").contains(command.componentBasis()==null?"":command.componentBasis())
                ||command.componentReviews()==null||command.componentReviews().size()!=KEYS.size()
                ||command.refundAssociations()==null||command.refundAssociations().size()>10000)throw invalid();
        var components=new ArrayList<ComponentReview>();
        var seen=new java.util.HashSet<String>();
        for(var value:command.componentReviews()) {
            if(value==null||value.key()==null||!KEYS.contains(value.key())||!seen.add(value.key())
                    ||!Set.of("REPORTED","ROUNDING_REVIEW","CONFIRMED_ZERO","EXTERNAL_REVIEW").contains(value.mode()==null?"":value.mode())
                    ||!Set.of("DEBIT","CREDIT").contains(value.direction()==null?"":value.direction()))throw invalid();
            BigDecimal amount=money(value.postedAmount(),true);
            String reference=reference(value.evidenceReference());
            if(amount.signum()==0&&value.accountId()!=null||amount.signum()>0&&(value.accountId()==null||reference==null))throw invalid();
            components.add(new ComponentReview(value.key(),value.mode(),value.direction(),value.accountId(),amount.toPlainString(),reference,reason(value.reason())));
        }
        components.sort(Comparator.comparing(ComponentReview::key));
        var associations=new ArrayList<RefundAssociation>();var rows=new java.util.HashSet<String>();var refunds=new java.util.HashSet<java.util.UUID>();
        for(var value:command.refundAssociations()) {
            if(value==null||value.sourceReadId()==null||value.refundPostingId()==null||value.rowIndex()<0||value.rowIndex()>=10
                    ||!rows.add(value.sourceReadId()+":"+value.rowIndex())||!refunds.add(value.refundPostingId()))throw invalid();
            associations.add(new RefundAssociation(value.sourceReadId(),value.rowIndex(),value.refundPostingId(),reason(value.reason())));
        }
        associations.sort(Comparator.comparing((RefundAssociation value)->value.sourceReadId().toString()).thenComparingInt(RefundAssociation::rowIndex));
        var normalized=new Command(command.schoolId(),command.clientRequestId(),command.contextFingerprint(),command.bankEvidenceId(),
                command.financialYearId(),command.postingOn(),"INR",true,command.componentBasis(),components,associations,
                command.previousPreparationId(),true,reason(command.reason()));
        var canonical=new StringBuilder("GATEWAY_SETTLEMENT_PREPARATION_V1");
        frame(canonical,normalized.schoolId(),normalized.contextFingerprint(),normalized.bankEvidenceId(),normalized.financialYearId(),
                normalized.postingOn(),normalized.summaryCurrencyCode(),normalized.componentBasis(),normalized.previousPreparationId(),normalized.reason());
        for(var value:components)frame(canonical,value.key(),value.mode(),value.direction(),value.accountId(),value.postedAmount(),value.evidenceReference(),value.reason());
        for(var value:associations)frame(canonical,value.sourceReadId(),value.rowIndex(),value.refundPostingId(),value.reason());
        return new Terms(normalized,hash(canonical.toString()));
    }

    static List<Component> components(Terms terms,List<ComponentFact> facts) {
        if(terms==null||facts==null||facts.size()!=KEYS.size())throw invalid();
        Map<String,ComponentFact> byKey=new LinkedHashMap<>();
        for(var fact:facts)if(fact==null||fact.key()==null||!KEYS.contains(fact.key())||byKey.putIfAbsent(fact.key(),fact)!=null)throw invalid();
        var result=new ArrayList<Component>();
        for(var reviewed:terms.command().componentReviews()) {
            var fact=byKey.get(reviewed.key());
            boolean eventBasis=Set.of("SERVICE_CHARGE","SERVICE_TAX").contains(fact.key())&&"EVENTS".equals(terms.command().componentBasis());
            String raw=eventBasis?(fact.eventsComplete()?fact.eventsAmount():null):fact.summaryAmount();
            BigDecimal reported=reported(raw),amount=money(reviewed.postedAmount(),true);
            switch(reviewed.mode()) {
                case "REPORTED" -> {
                    if(reported==null||reported.abs().compareTo(amount)!=0)throw componentReview();
                    try{reported.setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException fractional){throw componentReview();}
                }
                case "ROUNDING_REVIEW" -> {
                    if(reported==null||reported.abs().compareTo(amount)==0||reported.abs().subtract(amount).abs().compareTo(new BigDecimal("0.01"))>=0
                            ||reviewed.evidenceReference()==null)throw componentReview();
                }
                case "CONFIRMED_ZERO" -> {if(amount.signum()!=0||reported!=null&&reported.signum()!=0)throw componentReview();}
                case "EXTERNAL_REVIEW" -> {if(reported!=null||amount.signum()==0||reviewed.evidenceReference()==null)throw componentReview();}
                default -> throw invalid();
            }
            // Charge signs are explicit provider facts. Adjustment direction is
            // reviewed rather than inferred from an undocumented provider convention.
            if(reported!=null&&reported.signum()!=0&&!"ADJUSTMENT".equals(reviewed.key())
                    &&!(reported.signum()>0?"DEBIT":"CREDIT").equals(reviewed.direction()))throw componentReview();
            result.add(new Component(reviewed,Set.of("CONFIRMED_ZERO","EXTERNAL_REVIEW").contains(reviewed.mode())?"REVIEWED_DOCUMENT":
                    eventBasis?"EVENTS_REPORTED":"SUMMARY_REPORTED",amount,fact));
        }
        return List.copyOf(result);
    }

    static void balanced(BigDecimal payments,BigDecimal refunds,BigDecimal bank,List<Component> components) {
        if(payments==null||refunds==null||bank==null||components==null||components.stream().anyMatch(value->value==null)
                ||payments.signum()<0||refunds.signum()<0||bank.signum()<=0)throw invalid();
        BigDecimal debits=components.stream().filter(value->"DEBIT".equals(value.review().direction())).map(Component::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal credits=components.stream().filter(value->"CREDIT".equals(value.review().direction())).map(Component::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(payments.subtract(refunds).compareTo(bank.add(debits).subtract(credits))!=0)
            throw new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_NOT_BALANCED","Verified payment/refund clearing must equal the actual bank credit and every explicitly reviewed component. No balancing amount is generated.");
    }

    static BigDecimal money(String value,boolean zeroAllowed) {
        if(value==null||!value.matches("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,2})?"))throw invalid();
        BigDecimal result=new BigDecimal(value).setScale(2);
        if(result.compareTo(new BigDecimal("100000000.00"))>0||!zeroAllowed&&result.signum()==0)throw invalid();
        return result;
    }
    private static BigDecimal reported(String value) {
        if(value==null)return null;
        try {BigDecimal amount=new BigDecimal(value);if(amount.scale()>8||amount.abs().compareTo(new BigDecimal("100000000.00"))>0)throw invalid();return amount;}
        catch(NumberFormatException bad){throw invalid();}
    }
    static String reason(String value) {
        if(value==null||value.strip().length()<3||value.strip().length()>240||value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return value.strip();
    }
    private static String reference(String value) {
        if(value==null||value.isBlank())return null;
        String result=value.strip();
        if(!result.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} /_.:-]{0,119}")||result.contains("://"))throw invalid();
        return result;
    }
    static void frame(StringBuilder target,Object... values){for(Object value:values){String text=value==null?null:value.toString();target.append('|').append(text==null?-1:text.length()).append(':');if(text!=null)target.append(text);}}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 required");}}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_SETTLEMENT_PREPARATION_INVALID","Review the complete scoped statement, source choices, exact amounts and required confirmations.");}
    private static ApiException componentReview(){return new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_COMPONENT_REVIEW_REQUIRED","Each component must match its retained reported value or explicitly documented review; missing amounts are never assumed zero.");}
}
