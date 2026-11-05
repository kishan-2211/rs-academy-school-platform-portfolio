package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Pure bounded intent. Exact UTC time is retained separately from its floor-microsecond DB projection. */
final class GatewaySettlementBankEvidenceTerms {
    record RecordTerms(UUID schoolId,UUID requestId,UUID clientId,UUID bankId,String currency,BigDecimal amount,
            Instant actualAt,String actualExact,String kind,String reference,String normalizedReference,String reason,String fingerprint){
        @Override public String toString(){return "RecordTerms[private immutable bank-evidence intent]";}
    }
    record RejectionTerms(UUID schoolId,UUID requestId,UUID evidenceId,UUID clientId,String reason,String fingerprint){
        @Override public String toString(){return "RejectionTerms[private independent mistaken-evidence intent]";}
    }
    private GatewaySettlementBankEvidenceTerms(){}
    static RecordTerms record(UUID requestId,GatewaySettlementBankEvidenceModels.Command command){
        if(command==null||requestId==null||command.schoolId()==null||command.clientRequestId()==null||command.bankAccountId()==null
                ||!"INR".equals(command.currencyCode())||!command.confirmedReceived()
                ||!Set.of("BANK_APP_CREDIT","BANK_STATEMENT_CREDIT").contains(command.verificationKind()==null?"":command.verificationKind()))throw invalid();
        BigDecimal amount=amount(command.creditedAmount());String reason=reason(command.reason()),reference=reference(command.bankCreditReference());
        String exact;try{exact=GatewayExactInstant.canonical(command.actualCreditedAt());}catch(RuntimeException malformed){throw invalid();}
        String normalized=ManualCollectionEvidence.normalizeBankReference(reference);
        String fingerprint=hash("SETTLEMENT_BANK_EVIDENCE_V1",command.schoolId(),requestId,command.bankAccountId(),"INR",amount.toPlainString(),exact,
                command.verificationKind(),reference,normalized,true,reason);
        return new RecordTerms(command.schoolId(),requestId,command.clientRequestId(),command.bankAccountId(),"INR",amount,command.actualCreditedAt(),exact,
                command.verificationKind(),reference,normalized,reason,fingerprint);
    }
    static RejectionTerms rejection(UUID requestId,UUID evidenceId,GatewaySettlementBankEvidenceModels.RejectionCommand command){
        if(requestId==null||evidenceId==null||command==null||command.schoolId()==null||command.clientRequestId()==null||!command.confirmedMistakenEvidence())throw invalid();
        String reason=reason(command.reason());return new RejectionTerms(command.schoolId(),requestId,evidenceId,command.clientRequestId(),reason,
                hash("SETTLEMENT_BANK_EVIDENCE_REJECTION_V1",command.schoolId(),requestId,evidenceId,true,reason));
    }
    static BigDecimal amount(String value){
        if(value==null||!value.matches("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,2})?"))throw invalid();
        var amount=new BigDecimal(value).setScale(2,RoundingMode.UNNECESSARY);
        if(amount.signum()<=0||amount.compareTo(new BigDecimal("100000000.00"))>0)throw invalid();return amount;
    }
    static String reference(String raw){
        if(raw==null||raw.chars().anyMatch(value->value>127||value==0))throw invalid();String clean=raw.strip();
        if(clean.isEmpty()||clean.length()>120||!clean.matches("[A-Za-z0-9][A-Za-z0-9/_.: \\t-]{0,119}")
                ||clean.matches("[A-Za-z][A-Za-z0-9+.-]*://.*"))throw invalid();
        String normalized=ManualCollectionEvidence.normalizeBankReference(clean);
        if(normalized==null||!normalized.matches("[A-Z0-9][A-Z0-9/_.:-]{0,119}")||normalized.matches("[A-Z][A-Z0-9+.-]*://.*"))throw invalid();return clean;
    }
    static String reason(String raw){
        if(raw==null)throw invalid();String clean=raw.strip();
        if(clean.length()<3||clean.length()>240||clean.codePoints().anyMatch(Character::isISOControl))throw invalid();return clean;
    }
    private static String hash(String namespace,Object...values){
        var frame=new StringBuilder(namespace);for(Object value:values){String s=value==null?null:value.toString();frame.append('|').append(s==null?-1:s.length()).append(':');if(s!=null)frame.append(s);}
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException missing){throw new IllegalStateException("SHA-256 required");}
    }
    static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_SETTLEMENT_BANK_TERMS_INVALID","Use a confirmed actual bank credit, exact positive INR amount, bounded bank reference, time and safe review reason.");}
}
