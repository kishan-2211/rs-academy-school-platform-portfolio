package com.educore.api.finance;

import static com.educore.api.finance.GatewaySettlementPreparationModels.*;
import static com.educore.api.finance.GatewaySettlementPreparationTerms.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Exact synthetic monetary terms only; no provider, database, user or school data. */
class GatewaySettlementPreparationTermsTest {
    private static final List<String> EXPECTED_KEYS=List.of("SERVICE_CHARGE","SERVICE_TAX","ADJUSTMENT","SETTLEMENT_CHARGE",
            "SETTLEMENT_TAX","SPLIT_CHARGE","SPLIT_TAX","VENDOR_COMMISSION");

    @Test void canonicalTermsNormalizeMoneyReasonsReferencesAndOrderingWithoutChangingTheInput() {
        var f=new Fixture();var original=f.command();
        var component=new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"2.5"," SYN INV-001 ","  Synthetic charge review  ");
        original=review(original,component);
        var associations=new ArrayList<>(List.of(new RefundAssociation(f.readB,9,f.refundB,"  Synthetic second choice  "),
                new RefundAssociation(f.readA,0,f.refundA,"  Synthetic first choice  ")));
        original=change(original,"refundAssociations",associations);
        var reversed=new ArrayList<>(original.componentReviews());Collections.reverse(reversed);
        var reordered=change(change(original,"componentReviews",reversed),"refundAssociations",List.of(associations.getLast(),associations.getFirst()));
        var normalized=normalize(original);var same=normalize(reordered);
        assertThat(normalized.fingerprint()).matches("[a-f0-9]{64}").isEqualTo(same.fingerprint());
        assertThat(normalized.command().componentReviews().stream().map(ComponentReview::key).toList()).isSorted();
        var charge=find(normalized.command(),"SERVICE_CHARGE");
        assertThat(charge.postedAmount()).isEqualTo("2.50");assertThat(charge.evidenceReference()).isEqualTo("SYN INV-001");
        assertThat(charge.reason()).isEqualTo("Synthetic charge review");
        assertThat(original.componentReviews().stream().filter(value->value.key().equals("SERVICE_CHARGE")).findFirst().orElseThrow().postedAmount()).isEqualTo("2.5");
        assertThat(normalized.command().refundAssociations()).allSatisfy(value->assertThat(value.reason()).doesNotStartWith(" ").doesNotEndWith(" "));
        assertThatThrownBy(()->normalized.command().componentReviews().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->normalized.command().refundAssociations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(normalized.toString()).doesNotContain(f.school.toString(),f.bank.toString(),"2.50");
    }

    @Test void retryClientIdentityIsExcludedWhileSchoolEvidenceDateAndReviewTermsRemainInTheFingerprint() {
        var f=new Fixture();var command=change(f.command(),"refundAssociations",List.of(new RefundAssociation(f.readA,0,f.refundA,"Synthetic refund choice")));
        String fingerprint=normalize(command).fingerprint();
        assertThat(normalize(change(command,"clientRequestId",UUID.randomUUID())).fingerprint()).isEqualTo(fingerprint);
        var changes=new LinkedHashMap<String,Object>();changes.put("schoolId",UUID.randomUUID());changes.put("contextFingerprint","b".repeat(64));
        changes.put("bankEvidenceId",UUID.randomUUID());changes.put("financialYearId",UUID.randomUUID());changes.put("postingOn",f.date.minusDays(1));
        changes.put("componentBasis","EVENTS");changes.put("previousPreparationId",UUID.randomUUID());changes.put("reason","A different synthetic preparation reason");
        for(var change:changes.entrySet())assertThat(normalize(change(command,change.getKey(),change.getValue())).fingerprint()).isNotEqualTo(fingerprint);
        for(String field:List.of("READ","ROW","REFUND","REASON")) {
            var association=new RefundAssociation("READ".equals(field)?f.readB:f.readA,"ROW".equals(field)?1:0,
                    "REFUND".equals(field)?f.refundB:f.refundA,"REASON".equals(field)?"Different synthetic association":"Synthetic refund choice");
            assertThat(normalize(change(command,"refundAssociations",List.of(association))).fingerprint()).isNotEqualTo(fingerprint);
        }
    }

    @Test void everyComponentChoiceIsFingerprintBoundAndFramingCannotConfuseNullEmptyOrDelimiters() {
        var f=new Fixture();var command=review(f.command(),new ComponentReview("ADJUSTMENT","EXTERNAL_REVIEW","DEBIT",f.expense,"1.00","SYN EVIDENCE","Synthetic adjustment review"));
        String fingerprint=normalize(command).fingerprint();
        for(var changed:List.of(new ComponentReview("ADJUSTMENT","REPORTED","DEBIT",f.expense,"1.00","SYN EVIDENCE","Synthetic adjustment review"),
                new ComponentReview("ADJUSTMENT","EXTERNAL_REVIEW","CREDIT",f.expense,"1.00","SYN EVIDENCE","Synthetic adjustment review"),
                new ComponentReview("ADJUSTMENT","EXTERNAL_REVIEW","DEBIT",UUID.randomUUID(),"1.00","SYN EVIDENCE","Synthetic adjustment review"),
                new ComponentReview("ADJUSTMENT","EXTERNAL_REVIEW","DEBIT",f.expense,"1.01","SYN EVIDENCE","Synthetic adjustment review"),
                new ComponentReview("ADJUSTMENT","EXTERNAL_REVIEW","DEBIT",f.expense,"1.00","SYN OTHER","Synthetic adjustment review"),
                new ComponentReview("ADJUSTMENT","EXTERNAL_REVIEW","DEBIT",f.expense,"1.00","SYN EVIDENCE","Different synthetic review")))
            assertThat(normalize(review(command,changed)).fingerprint()).isNotEqualTo(fingerprint);
        var framed=new StringBuilder();frame(framed,null,"","null","a|b");
        assertThat(framed.toString()).isEqualTo("|-1:|0:|4:null|3:a|b");
        var first=new StringBuilder();frame(first,"a|1:b","c");var second=new StringBuilder();frame(second,"a","b|1:c");
        assertThat(hash(first.toString())).isNotEqualTo(hash(second.toString()));
    }

    @Test void exactlyEightKnownComponentKeysAreRequiredWithoutDuplicateOrUnknownSubstitution() {
        assertThat(KEYS).containsExactlyElementsOf(EXPECTED_KEYS);
        var f=new Fixture();var command=f.command();
        var missing=new ArrayList<>(command.componentReviews());missing.removeLast();invalid(()->normalize(change(command,"componentReviews",missing)));
        var extra=new ArrayList<>(command.componentReviews());extra.add(new ComponentReview("UNKNOWN","CONFIRMED_ZERO","DEBIT",null,"0",null,"Synthetic review"));
        invalid(()->normalize(change(command,"componentReviews",extra)));
        var duplicate=new ArrayList<>(command.componentReviews());duplicate.set(0,duplicate.get(1));invalid(()->normalize(change(command,"componentReviews",duplicate)));
        var unknown=new ArrayList<>(command.componentReviews());unknown.set(0,new ComponentReview("UNKNOWN","CONFIRMED_ZERO","DEBIT",null,"0",null,"Synthetic review"));
        invalid(()->normalize(change(command,"componentReviews",unknown)));
        var nullKey=new ArrayList<>(command.componentReviews());nullKey.set(0,new ComponentReview(null,"CONFIRMED_ZERO","DEBIT",null,"0",null,"Synthetic review"));
        invalid(()->normalize(change(command,"componentReviews",nullKey)));
    }

    @Test void refundChoicesAreUniqueByPhysicalReadRowAndBySelectedCompletionRatherThanARepeatedProviderReference() {
        var f=new Fixture();var command=f.command();
        var distinct=List.of(new RefundAssociation(f.readA,0,f.refundA,"Synthetic first choice"),new RefundAssociation(f.readB,0,f.refundB,"Synthetic second choice"));
        assertThat(normalize(change(command,"refundAssociations",distinct)).command().refundAssociations()).hasSize(2);
        for(var invalidChoices:List.of(
                List.of(new RefundAssociation(f.readA,0,f.refundA,"Synthetic choice"),new RefundAssociation(f.readA,0,f.refundB,"Synthetic duplicate row")),
                List.of(new RefundAssociation(f.readA,0,f.refundA,"Synthetic choice"),new RefundAssociation(f.readB,0,f.refundA,"Synthetic duplicate completion")),
                List.of(new RefundAssociation(f.readA,-1,f.refundA,"Synthetic invalid row")),List.of(new RefundAssociation(f.readA,10,f.refundA,"Synthetic invalid row")),
                List.of(new RefundAssociation(null,0,f.refundA,"Synthetic absent read")),List.of(new RefundAssociation(f.readA,0,null,"Synthetic absent completion")),
                List.of(new RefundAssociation(f.readA,0,f.refundA," "))))
            invalid(()->normalize(change(command,"refundAssociations",invalidChoices)));
        invalid(()->normalize(change(command,"refundAssociations",Collections.nCopies(10001,distinct.getFirst()))));
    }

    @Test void positivePostingNeedsAnAccountAndEvidenceWhileZeroNeverReservesAnAccount() {
        var f=new Fixture();var command=f.command();
        for(var invalidReview:List.of(new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",null,"1.00","SYN REF","Synthetic review"),
                new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"1.00",null,"Synthetic review"),
                new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"0.00","SYN REF","Synthetic review"),
                new ComponentReview("SERVICE_CHARGE",null,"DEBIT",null,"0.00",null,"Synthetic review"),
                new ComponentReview("SERVICE_CHARGE","CONFIRMED_ZERO",null,null,"0.00",null,"Synthetic review"),
                new ComponentReview("SERVICE_CHARGE","INFERRED","DEBIT",null,"0.00",null,"Synthetic review"),
                new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"1.00","https://example.test/private","Synthetic review"),
                new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"1.00","SYN REF","bad\nreason")))
            invalid(()->normalize(review(command,invalidReview)));
        var allowed=normalize(review(command,new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"1.00"," SYN SCHOOL-001 "," स्कूली समीक्षा ")));
        assertThat(find(allowed.command(),"SERVICE_CHARGE").reason()).isEqualTo("स्कूली समीक्षा");
    }

    @Test void absentReportedAmountsRequireExplicitZeroOrExternalReviewAndAreNeverAutomaticallyZero() {
        var f=new Fixture();var facts=f.facts();facts=fact(facts,new ComponentFact("SERVICE_CHARGE",null,null,false));
        var missing=facts;
        reviewRequired(()->components(normalize(review(f.command(),new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",null,"0",null,"Synthetic reported zero"))),missing));
        var explicitZero=components(normalize(f.command()),missing);
        assertThat(component(explicitZero,"SERVICE_CHARGE").amount()).isEqualByComparingTo("0.00");
        assertThat(component(explicitZero,"SERVICE_CHARGE").basis()).isEqualTo("REVIEWED_DOCUMENT");
        var external=normalize(review(f.command(),new ComponentReview("SERVICE_CHARGE","EXTERNAL_REVIEW","DEBIT",f.expense,"2.25","SYN BANK-EVIDENCE","Synthetic external fee evidence")));
        assertThat(component(components(external,missing),"SERVICE_CHARGE").amount()).isEqualByComparingTo("2.25");
        reviewRequired(()->components(external,f.facts()));
        var externalZero=normalize(review(f.command(),new ComponentReview("SERVICE_CHARGE","EXTERNAL_REVIEW","DEBIT",null,"0",null,"Synthetic invalid external zero")));
        reviewRequired(()->components(externalZero,missing));
    }

    @Test void confirmedZeroCannotHideAKnownNonZeroProviderChargeOrTax() {
        var f=new Fixture();
        for(String key:KEYS) {
            var facts=fact(f.facts(),new ComponentFact(key,"0.01000000","0.01000000",true));
            reviewRequired(()->components(normalize(f.command()),facts));
        }
    }

    @Test void reportedEightPlacePrecisionIsRetainedAndFractionalPaiseNeedsExplicitBoundedRoundingReview() {
        var f=new Fixture();var precise=fact(f.facts(),new ComponentFact("SERVICE_CHARGE","1.23456789","9.00000000",true));
        var ordinary=normalize(review(f.command(),new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"1.23","SYN EXACT","Synthetic reported charge")));
        reviewRequired(()->components(ordinary,precise));
        var reviewed=normalize(review(f.command(),new ComponentReview("SERVICE_CHARGE","ROUNDING_REVIEW","DEBIT",f.expense,"1.23","SYN ROUNDING","Synthetic explicit fractional-paise review")));
        var result=component(components(reviewed,precise),"SERVICE_CHARGE");
        assertThat(result.fact().summaryAmount()).isEqualTo("1.23456789");assertThat(result.amount()).isEqualByComparingTo("1.23");
        assertThat(result.basis()).isEqualTo("SUMMARY_REPORTED");
        var exact=fact(f.facts(),new ComponentFact("SERVICE_CHARGE","1.23000000",null,false));
        assertThat(component(components(ordinary,exact),"SERVICE_CHARGE").amount()).isEqualByComparingTo("1.23");
        reviewRequired(()->components(reviewed,exact));
        var onePaise=fact(f.facts(),new ComponentFact("SERVICE_CHARGE","1.24000000",null,false));
        reviewRequired(()->components(reviewed,onePaise));
        var absent=fact(f.facts(),new ComponentFact("SERVICE_CHARGE",null,null,false));reviewRequired(()->components(reviewed,absent));
    }

    @Test void selectedSummaryOrCompleteEventBasisCountsServiceComponentsOnceAndDoesNotSumBothRepresentations() {
        var f=new Fixture();var facts=fact(f.facts(),new ComponentFact("SERVICE_CHARGE","2.00000000","3.00000000",true));
        var summary=normalize(review(f.command(),new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"2","SYN SUMMARY","Synthetic summary basis")));
        var fromSummary=components(summary,facts);
        assertThat(fromSummary.stream().map(Component::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("2.00");
        var eventCommand=change(review(f.command(),new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"3","SYN EVENTS","Synthetic events basis")),"componentBasis","EVENTS");
        var fromEvents=components(normalize(eventCommand),facts);
        assertThat(fromEvents.stream().map(Component::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("3.00");
        assertThat(component(fromEvents,"SERVICE_CHARGE").basis()).isEqualTo("EVENTS_REPORTED");
        reviewRequired(()->components(normalize(change(summary.command(),"componentBasis","EVENTS")),facts));
        var incomplete=fact(facts,new ComponentFact("SERVICE_CHARGE","2.00000000","3.00000000",false));
        reviewRequired(()->components(normalize(eventCommand),incomplete));
        var adjustment=fact(f.facts(),new ComponentFact("ADJUSTMENT","4.00","99.00",true));
        var command=change(review(f.command(),new ComponentReview("ADJUSTMENT","REPORTED","DEBIT",f.expense,"4","SYN ADJUSTMENT","Synthetic summary-only adjustment")),"componentBasis","EVENTS");
        assertThat(component(components(normalize(command),adjustment),"ADJUSTMENT").amount()).isEqualByComparingTo("4.00");
    }

    @Test void chargeSignsRequireTheirActualDebitOrCreditWhileAdjustmentDirectionIsExplicitlyReviewed() {
        var f=new Fixture();
        for(String key:KEYS) {
            var negative=fact(f.facts(),new ComponentFact(key,"-2.50000000","-2.50000000",true));
            var credit=normalize(review(f.command(),new ComponentReview(key,"REPORTED","CREDIT",f.expense,"2.50","SYN CREDIT","Synthetic negative reported component")));
            assertThat(component(components(credit,negative),key).amount()).isEqualByComparingTo("2.50");
            var debit=normalize(review(f.command(),new ComponentReview(key,"REPORTED","DEBIT",f.expense,"2.50","SYN DEBIT","Synthetic reviewed direction")));
            if("ADJUSTMENT".equals(key))assertThat(component(components(debit,negative),key).review().direction()).isEqualTo("DEBIT");
            else reviewRequired(()->components(debit,negative));
        }
    }

    @Test void eventBasisAlsoControlsServiceTaxAndDoesNotAddItsSummaryAliasAgain() {
        var f=new Fixture();var facts=fact(f.facts(),new ComponentFact("SERVICE_TAX","1.00000000","1.25000000",true));
        var command=change(review(f.command(),new ComponentReview("SERVICE_TAX","REPORTED","DEBIT",f.expense,"1.25","SYN TAX","Synthetic event-tax review")),"componentBasis","EVENTS");
        var result=components(normalize(command),facts);
        assertThat(component(result,"SERVICE_TAX").basis()).isEqualTo("EVENTS_REPORTED");
        assertThat(result.stream().map(Component::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1.25");
        reviewRequired(()->components(normalize(change(command,"componentBasis","SUMMARY")),facts));
    }

    @Test void exactBankEquationIncludesRefundsAndBothComponentSidesAndNeverCreatesABalancingPlug() {
        var f=new Fixture();var command=review(review(f.command(),new ComponentReview("SERVICE_CHARGE","REPORTED","DEBIT",f.expense,"10","SYN DEBIT","Synthetic provider cost")),
                new ComponentReview("ADJUSTMENT","REPORTED","CREDIT",f.expense,"2","SYN CREDIT","Synthetic documented credit"));
        var facts=fact(fact(f.facts(),new ComponentFact("SERVICE_CHARGE","10.00000000",null,false)),new ComponentFact("ADJUSTMENT","2.00000000",null,false));
        var reviewed=components(normalize(command),facts);var before=List.copyOf(reviewed);
        balanced(new BigDecimal("200.00"),new BigDecimal("50.00"),new BigDecimal("142.00"),reviewed);
        notBalanced(()->balanced(new BigDecimal("200.00"),BigDecimal.ZERO,new BigDecimal("142.00"),reviewed));
        notBalanced(()->balanced(new BigDecimal("200.00"),new BigDecimal("50.00"),new BigDecimal("141.99"),reviewed));
        notBalanced(()->balanced(new BigDecimal("200.00"),new BigDecimal("50.00"),new BigDecimal("142.00000001"),reviewed));
        assertThat(reviewed).containsExactlyElementsOf(before);
        assertThat(reviewed.stream().map(Component::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("12.00");
    }

    @Test void invalidPayloadsAndRequiredConfirmationsCannotBecomeCanonicalIntents() {
        var f=new Fixture();var command=f.command();invalid(()->normalize(null));
        for(String field:List.of("schoolId","clientRequestId","contextFingerprint","bankEvidenceId","financialYearId","postingOn",
                "summaryCurrencyCode","componentBasis","componentReviews","refundAssociations","reason"))invalid(()->normalize(change(command,field,null)));
        for(var value:Map.of("confirmedSummaryCurrency",false,"confirmedReviewed",false,"summaryCurrencyCode","USD","componentBasis","INFERRED", "contextFingerprint","A".repeat(64)).entrySet())
            invalid(()->normalize(change(command,value.getKey(),value.getValue())));
        for(String reason:List.of("ab","x".repeat(241),"bad\treason"))invalid(()->normalize(change(command,"reason",reason)));
        assertThat(normalize(change(command,"reason","x".repeat(240))).command().reason()).hasSize(240);
    }

    @Test void malformedAndOutOfPrecisionSourceFactsFailInsteadOfInventingValues() {
        var f=new Fixture();var normalized=normalize(f.command());invalid(()->components(normalized,null));invalid(()->components(null,f.facts()));
        var missing=new ArrayList<>(f.facts());missing.removeLast();invalid(()->components(normalized,missing));
        var duplicate=new ArrayList<>(f.facts());duplicate.set(0,duplicate.get(1));invalid(()->components(normalized,duplicate));
        for(String key:List.of("UNKNOWN","")) {
            var facts=new ArrayList<>(f.facts());facts.set(0,new ComponentFact(key,"0",null,false));invalid(()->components(normalized,facts));
        }
        var nullKey=new ArrayList<>(f.facts());nullKey.set(0,new ComponentFact(null,"0",null,false));invalid(()->components(normalized,nullKey));
        for(String amount:List.of("not-a-number","","NaN","Infinity","0.000000000","100000000.00000001","1E+1000")) {
            var malformed=fact(f.facts(),new ComponentFact("SERVICE_CHARGE",amount,null,false));invalid(()->components(normalized,malformed));
        }
    }

    @Test void postedMoneyRejectsNonPaiseNegativeExponentialLeadingZeroAndOutOfRangeInputs() {
        for(String amount:List.of("-1","1.001","+1","1e2","01","100000000.01",""," 1 "))invalid(()->money(amount,true));
        invalid(()->money(null,true));invalid(()->money("0",false));
        assertThat(money("100000000.00",false)).isEqualByComparingTo("100000000.00");
        assertThat(money("0",true)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test void balanceRejectsMissingNegativeAndZeroBankFactsWithoutCoercion() {
        var f=new Fixture();var reviewed=components(normalize(f.command()),f.facts());
        invalid(()->balanced(null,BigDecimal.ZERO,BigDecimal.ONE,reviewed));invalid(()->balanced(BigDecimal.ONE,null,BigDecimal.ONE,reviewed));
        invalid(()->balanced(BigDecimal.ONE,BigDecimal.ZERO,null,reviewed));invalid(()->balanced(BigDecimal.ONE.negate(),BigDecimal.ZERO,BigDecimal.ONE,reviewed));
        invalid(()->balanced(BigDecimal.ONE,BigDecimal.ONE.negate(),BigDecimal.ONE,reviewed));invalid(()->balanced(BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,reviewed));
        invalid(()->balanced(BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE,null));
    }

    private static ComponentReview find(Command command,String key){return command.componentReviews().stream().filter(value->value.key().equals(key)).findFirst().orElseThrow();}
    private static Component component(List<Component> values,String key){return values.stream().filter(value->value.review().key().equals(key)).findFirst().orElseThrow();}
    private static Command review(Command command,ComponentReview replacement){var values=new ArrayList<>(command.componentReviews());
        for(int index=0;index<values.size();index++)if(values.get(index).key().equals(replacement.key())){values.set(index,replacement);return change(command,"componentReviews",values);}throw new AssertionError(replacement.key());}
    private static List<ComponentFact> fact(List<ComponentFact> facts,ComponentFact replacement){var values=new ArrayList<>(facts);
        for(int index=0;index<values.size();index++)if(values.get(index).key().equals(replacement.key())){values.set(index,replacement);return values;}throw new AssertionError(replacement.key());}

    @SuppressWarnings("unchecked")
    private static Command change(Command command,String key,Object replacement) {
        var values=new HashMap<String,Object>();values.put(key,replacement);
        return new Command((UUID)values.getOrDefault("schoolId",command.schoolId()),(UUID)values.getOrDefault("clientRequestId",command.clientRequestId()),
                (String)values.getOrDefault("contextFingerprint",command.contextFingerprint()),(UUID)values.getOrDefault("bankEvidenceId",command.bankEvidenceId()),
                (UUID)values.getOrDefault("financialYearId",command.financialYearId()),(LocalDate)values.getOrDefault("postingOn",command.postingOn()),
                (String)values.getOrDefault("summaryCurrencyCode",command.summaryCurrencyCode()),(boolean)values.getOrDefault("confirmedSummaryCurrency",command.confirmedSummaryCurrency()),
                (String)values.getOrDefault("componentBasis",command.componentBasis()),(List<ComponentReview>)values.getOrDefault("componentReviews",command.componentReviews()),
                (List<RefundAssociation>)values.getOrDefault("refundAssociations",command.refundAssociations()),(UUID)values.getOrDefault("previousPreparationId",command.previousPreparationId()),
                (boolean)values.getOrDefault("confirmedReviewed",command.confirmedReviewed()),(String)values.getOrDefault("reason",command.reason()));
    }
    private static void invalid(Runnable action){error(action,"GATEWAY_SETTLEMENT_PREPARATION_INVALID",HttpStatus.BAD_REQUEST);}
    private static void reviewRequired(Runnable action){error(action,"GATEWAY_SETTLEMENT_COMPONENT_REVIEW_REQUIRED",HttpStatus.CONFLICT);}
    private static void notBalanced(Runnable action){error(action,"GATEWAY_SETTLEMENT_NOT_BALANCED",HttpStatus.CONFLICT);}
    private static void error(Runnable action,String code,HttpStatus status){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
            failure->{assertThat(failure.code()).isEqualTo(code);assertThat(failure.status()).isEqualTo(status);});}
    private static final class Fixture {
        final UUID school=UUID.randomUUID(),request=UUID.randomUUID(),bank=UUID.randomUUID(),year=UUID.randomUUID(),expense=UUID.randomUUID();
        final UUID readA=UUID.fromString("00000000-0000-4000-8000-000000000001"),readB=UUID.fromString("00000000-0000-4000-8000-000000000002");
        final UUID refundA=UUID.randomUUID(),refundB=UUID.randomUUID();final LocalDate date=LocalDate.of(2026,10,27);
        Command command(){return new Command(school,request,"a".repeat(64),bank,year,date,"INR",true,"SUMMARY",
                EXPECTED_KEYS.stream().map(key->new ComponentReview(key,"CONFIRMED_ZERO","DEBIT",null,"0.00",null,"Synthetic explicit zero review")).toList(),
                List.of(),null,true,"Synthetic whole-statement preparation review");}
        List<ComponentFact> facts(){return EXPECTED_KEYS.stream().map(key->new ComponentFact(key,"0.00000000","0.00000000",true)).toList();}
    }
}
