package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Immutable human intent only. This is neither provider success nor a fee/GL posting. */
final class GatewayRefundTerms {
    record Selection(UUID chargeId, BigDecimal amount) { @Override public String toString(){return "Selection[private refund allocation]";} }
    record Terms(UUID clientRequestId, List<Selection> allocations, BigDecimal amount, String reason, String fingerprint) {
        Terms { allocations=List.copyOf(allocations); }
        @Override public String toString(){return "Terms[private immutable refund intent]";}
    }
    static Terms prepare(UUID paymentId, UUID clientRequestId, List<Selection> allocations, String reason) {
        if(paymentId==null || clientRequestId==null || allocations==null || allocations.isEmpty() || allocations.size()>100
                || allocations.stream().anyMatch(item->item==null || item.chargeId()==null)
                || allocations.stream().map(Selection::chargeId).distinct().count()!=allocations.size()) throw invalid();
        String why=reason(reason);
        var sorted=allocations.stream().map(item->new Selection(item.chargeId(),money(item.amount())))
                .sorted(Comparator.comparing(Selection::chargeId)).toList();
        BigDecimal amount=money(sorted.stream().map(Selection::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
        var canonical=new StringBuilder(paymentId.toString()).append('\n').append(why.length()).append(':').append(why);
        sorted.forEach(item->canonical.append('\n').append(item.chargeId()).append(':').append(item.amount().toPlainString()));
        return new Terms(clientRequestId,sorted,amount,why,sha256(canonical.toString()));
    }
    static String reason(String value) {
        if(value==null || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        String result=value.strip();
        if(result.length()<3 || result.length()>500) throw invalid();
        return result;
    }
    private static BigDecimal money(BigDecimal value) {
        if(value==null || value.scale()>2 || value.signum()<=0 || value.compareTo(new BigDecimal("100000000.00"))>0) throw invalid();
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    private static String sha256(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 is unavailable");}
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_REFUND_TERMS_INVALID",
            "Choose unique original fee allocations, exact positive refund amounts and a reason from 3 to 500 characters.");}
}
