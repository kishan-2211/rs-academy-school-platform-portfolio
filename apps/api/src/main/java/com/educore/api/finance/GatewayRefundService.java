package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Governed intent and scoped processing projection; never performs provider I/O or writes fees/GL. */
@Service
public class GatewayRefundService {
    private static final Set<String> STATES=Set.of("ALL","SUBMITTED","APPROVED","REJECTED","CANCELLED");
    private static final String STATE="CASE decision.decision WHEN 'APPROVE' THEN 'APPROVED' WHEN 'REJECT' THEN 'REJECTED' WHEN 'CANCEL' THEN 'CANCELLED' ELSE 'SUBMITTED' END";
    private final NamedParameterJdbcTemplate jdbc;
    private final FinanceService finance;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final GatewayProviderRegistry registry;
    public GatewayRefundService(NamedParameterJdbcTemplate jdbc,FinanceService finance,SecurityAuditService audit,Clock clock){
        this(jdbc,finance,audit,clock,null);
    }
    @Autowired
    public GatewayRefundService(NamedParameterJdbcTemplate jdbc,FinanceService finance,SecurityAuditService audit,Clock clock,GatewayProviderRegistry registry){
        this.jdbc=jdbc;this.finance=finance;this.audit=audit;this.clock=clock;this.registry=registry;
    }

    @Transactional(readOnly=true)
    public GatewayRefundModels.Payment payment(AuthenticatedUser user,UUID schoolId,UUID paymentId){
        requireContext(user,false);
        var scope=finance.requireRefundReadScope(user,schoolId);
        return projection(scope,source(scope,paymentId));
    }

    @Transactional(readOnly=true)
    public GatewayRefundModels.Queue queue(AuthenticatedUser user,UUID schoolId,String status,String query,int page,int pageSize){
        requireContext(user,false);
        var scope=finance.requireRefundReadScope(user,schoolId);
        String q=search(query);
        if(!STATES.contains(status==null?"":status)||page<0||page>10000||pageSize<1||pageSize>50)throw filters();
        var p=scope.params().addValue("state",status).addValue("search",q==null?null:"%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%")
                .addValue("limit",pageSize).addValue("offset",(long)page*pageSize);
        String sql="""
            WITH filtered AS MATERIALIZED (
              SELECT request.id,request.fee_payment_id,request.amount,request.currency_code,request.request_reason,
                request.requested_at,request.requested_by_user_id,maker.display_name AS maker_name,
                payment.receipt_number,learner.legal_name AS learner_name,
                decision.id AS decision_id,decision.decision,decision.reason AS decision_reason,
                decision.decided_at,reviewer.display_name AS reviewer_name,
            """+STATE+"""
                AS state
              FROM fee_gateway_refund_requests request
              JOIN fee_payments payment ON payment.id=request.fee_payment_id AND payment.organisation_id=request.organisation_id AND payment.school_id=request.school_id
              JOIN learner_fee_accounts account ON account.id=request.learner_fee_account_id AND account.organisation_id=request.organisation_id AND account.school_id=request.school_id
              JOIN learners learner ON learner.id=account.learner_id AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
              JOIN student_enrolments enrolment ON enrolment.id=account.enrolment_id AND enrolment.organisation_id=account.organisation_id AND enrolment.school_id=account.school_id
              JOIN user_accounts maker ON maker.id=request.requested_by_user_id
              LEFT JOIN fee_gateway_refund_decisions decision ON decision.refund_request_id=request.id AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id
              LEFT JOIN user_accounts reviewer ON reviewer.id=decision.decided_by_user_id
              WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                AND (CAST(:search AS text) IS NULL OR learner.legal_name ILIKE :search ESCAPE '\\'
                  OR enrolment.admission_number ILIKE :search ESCAPE '\\' OR payment.receipt_number ILIKE :search ESCAPE '\\')
            ), matching AS MATERIALIZED (SELECT * FROM filtered WHERE :state='ALL' OR state=:state)
            SELECT totals.total,items.*,work.status AS work_status,
              CASE WHEN work.status IN ('READY','RETRY') THEN work.next_attempt_at END AS next_check_at,
              (dispatch.refund_request_id IS NOT NULL) AS dispatch_recorded,
              (success.refund_request_id IS NOT NULL) AS success_retained,
              (posting.refund_request_id IS NOT NULL) AS dues_restored,
              witness.observed_at,witness.outcome AS read_outcome,witness.refund_status AS read_status
            FROM (SELECT count(*) AS total FROM matching) totals
            LEFT JOIN LATERAL(SELECT * FROM matching ORDER BY requested_at DESC,id LIMIT :limit OFFSET :offset) items ON true
            LEFT JOIN fee_gateway_refund_work work ON work.refund_request_id=items.id AND work.organisation_id=:organisationId AND work.school_id=:schoolId
            LEFT JOIN fee_gateway_refund_dispatches dispatch ON dispatch.refund_request_id=items.id AND dispatch.organisation_id=:organisationId AND dispatch.school_id=:schoolId
            LEFT JOIN fee_gateway_refund_successes success ON success.refund_request_id=items.id AND success.organisation_id=:organisationId AND success.school_id=:schoolId
            LEFT JOIN fee_gateway_refund_postings posting ON posting.refund_request_id=items.id AND posting.organisation_id=:organisationId AND posting.school_id=:schoolId
            LEFT JOIN LATERAL(SELECT observed_at,outcome,refund_status FROM fee_gateway_refund_reads
              WHERE refund_request_id=items.id AND organisation_id=:organisationId AND school_id=:schoolId
              ORDER BY observed_at DESC,id DESC LIMIT 1) witness ON true
            ORDER BY items.requested_at DESC,items.id
            """;
        var snapshot=jdbc.query(sql,p,(ResultSetExtractor<QueueRows>) result->{
            if(!result.next())throw new IllegalStateException("Refund aggregate unavailable");
            long total=result.getLong("total");var rows=new ArrayList<QueueRow>();
            do{if(result.getObject("id")!=null)rows.add(queueRow(result));}while(result.next());
            return new QueueRows(total,rows);
        });
        if(snapshot==null)throw new IllegalStateException("Refund aggregate unavailable");
        var allocations=allocations(scope,snapshot.rows().stream().map(QueueRow::id).toList());
        var items=snapshot.rows().stream().map(row->{
            boolean maker=row.maker().equals(user.userId()),pending=row.state().equals("SUBMITTED");
            return new GatewayRefundModels.Request(row.id(),row.paymentId(),row.receiptNumber(),row.learnerName(),row.amount(),row.currency(),row.state(),row.reason(),row.requestedAt(),row.makerName(),
                    maker,pending&&scope.canReverse()&&!maker,pending&&scope.canReverse()&&maker,allocations.getOrDefault(row.id(),List.of()),row.decision(),row.processing());
        }).toList();
        return new GatewayRefundModels.Queue(schoolId,status,q,page,pageSize,snapshot.total(),(long)page*pageSize+items.size()<snapshot.total(),newRefundsConfigured(scope),items);
    }

    @Transactional
    public GatewayRefundModels.Result submit(AuthenticatedUser user,UUID paymentId,GatewayRefundModels.Submit command){
        requireContext(user,true);
        if(command==null)throw filters();
        var scope=finance.requireRefundWriteScope(user,command.schoolId());
        var terms=GatewayRefundTerms.prepare(paymentId,command.clientRequestId(),command.allocations()==null?null:command.allocations().stream()
                .map(item->item==null?null:new GatewayRefundTerms.Selection(item.chargeId(),item.amount())).toList(),command.reason());
        var p=scope.params().addValue("clientId",terms.clientRequestId()).addValue("userId",user.userId());
        var replay=jdbc.query("""
            SELECT request.id,request.fee_payment_id,request.request_fingerprint,
            """+STATE+"""
              AS state FROM fee_gateway_refund_requests request
            LEFT JOIN fee_gateway_refund_decisions decision ON decision.refund_request_id=request.id
              AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id
            WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
              AND request.requested_by_user_id=:userId AND request.client_request_id=:clientId
            """,p,row->row.next()?new Replay(row.getObject("id",UUID.class),row.getObject("fee_payment_id",UUID.class),row.getString("request_fingerprint"),row.getString("state")):null);
        if(replay!=null){
            if(!replay.paymentId().equals(paymentId)||!replay.fingerprint().equals(terms.fingerprint()))throw conflict("GATEWAY_REFUND_REQUEST_REUSED","This request identity belongs to different refund terms. Review the original request.");
            return new GatewayRefundModels.Result(replay.id(),paymentId,replay.state());
        }
        var source=source(scope,paymentId);
        if(source.credentials()==null||!source.credentials().matches("[A-Za-z][A-Za-z0-9_./:-]{2,239}"))
            throw conflict("GATEWAY_REFUND_SOURCE_UNAVAILABLE","The original provider binding needs financial review.");
        var current=projection(scope,source);
        if(terms.amount().compareTo(current.available())>0)throw budget();
        Map<UUID,GatewayRefundModels.SourceAllocation> original=new HashMap<>();current.allocations().forEach(item->original.put(item.chargeId(),item));
        for(var item:terms.allocations()){
            var paid=original.get(item.chargeId());
            if(paid==null||!paid.refundable()||item.amount().compareTo(paid.available())>0)throw budget();
        }
        UUID id=UUID.randomUUID();Instant now=Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        p=scope.params().addValue("id",id).addValue("paymentId",paymentId).addValue("accountId",source.accountId()).addValue("captureId",source.captureId())
                .addValue("providerId",source.providerId()).addValue("providerCode",source.providerCode()).addValue("environment",source.environment())
                .addValue("merchant",source.merchant()).addValue("credentials",source.credentials()).addValue("orderRef",source.orderRef()).addValue("paymentRef",source.paymentRef())
                .addValue("currency",source.currency()).addValue("zone",source.zone()).addValue("amount",terms.amount()).addValue("clientId",terms.clientRequestId())
                .addValue("fingerprint",terms.fingerprint()).addValue("reason",terms.reason()).addValue("userId",user.userId()).addValue("now",now);
        jdbc.update("""
            INSERT INTO fee_gateway_refund_requests(id,organisation_id,school_id,fee_payment_id,learner_fee_account_id,capture_id,
              payment_provider_config_id,provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
              order_reference,payment_reference,currency_code,school_time_zone,amount,client_request_id,request_fingerprint,request_reason,
              requested_by_user_id,requested_by_membership_id,requested_at)
            VALUES(:id,:organisationId,:schoolId,:paymentId,:accountId,:captureId,:providerId,:providerCode,:environment,:merchant,:credentials,
              :orderRef,:paymentRef,:currency,:zone,:amount,:clientId,:fingerprint,:reason,:userId,:actorMembershipId,:now)
            """,p);
        for(var selected:terms.allocations()){
            int inserted=jdbc.update("""
                INSERT INTO fee_gateway_refund_allocations(id,organisation_id,school_id,refund_request_id,fee_payment_id,learner_fee_account_id,
                  original_payment_allocation_id,learner_fee_charge_id,fee_head_id,amount,policy_refundable)
                SELECT :allocationId,:organisationId,:schoolId,:id,:paymentId,:accountId,paid.id,charge.id,item.fee_head_id,:selectedAmount,true
                FROM fee_payment_allocations paid JOIN learner_fee_charges charge ON charge.id=paid.learner_fee_charge_id
                  AND charge.organisation_id=paid.organisation_id AND charge.school_id=paid.school_id
                JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id AND item.organisation_id=charge.organisation_id AND item.school_id=charge.school_id
                WHERE paid.fee_payment_id=:paymentId AND paid.organisation_id=:organisationId AND paid.school_id=:schoolId AND charge.id=:chargeId
                """,p.addValue("allocationId",UUID.randomUUID()).addValue("selectedAmount",selected.amount()).addValue("chargeId",selected.chargeId()));
            if(inserted!=1)throw budget();
        }
        audit.recordScoped(user,scope.actorMembershipId(),scope.schoolId(),"finance.gateway.refund.requested","GATEWAY_REFUND_REQUEST",id.toString(),AuditOutcome.SUCCEEDED,terms.reason(),Map.of("paymentId",paymentId,"amount",terms.amount(),"currency",source.currency()));
        return new GatewayRefundModels.Result(id,paymentId,"SUBMITTED");
    }

    @Transactional
    public GatewayRefundModels.Result decide(AuthenticatedUser user,UUID refundId,GatewayRefundModels.DecisionCommand command){
        requireContext(user,true);
        if(command==null||refundId==null||!Set.of("APPROVE","REJECT","CANCEL").contains(command.decision()==null?"":command.decision()))throw filters();
        var scope=finance.requireRefundWriteScope(user,command.schoolId());String why=GatewayRefundTerms.reason(command.reason());
        var p=scope.params().addValue("id",refundId);
        var request=jdbc.query("""
            SELECT id,fee_payment_id,requested_by_user_id,payment_provider_config_id,provider_code,provider_environment,
              merchant_account_reference,credentials_secret_reference
            FROM fee_gateway_refund_requests WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
            """,p,row->row.next()?new DecisionSource(row.getObject("fee_payment_id",UUID.class),row.getObject("requested_by_user_id",UUID.class),
                    row.getObject("payment_provider_config_id",UUID.class),row.getString("provider_code"),row.getString("provider_environment"),row.getString("merchant_account_reference"),row.getString("credentials_secret_reference")):null);
        if(request==null)throw missing();
        var old=jdbc.query("SELECT decision,reason,decided_by_user_id FROM fee_gateway_refund_decisions WHERE refund_request_id=:id AND organisation_id=:organisationId AND school_id=:schoolId",p,
                row->row.next()?new OldDecision(row.getString("decision"),row.getString("reason"),row.getObject("decided_by_user_id",UUID.class)):null);
        if(old!=null){
            if(!old.actor().equals(user.userId())||!old.action().equals(command.decision())||!old.reason().equals(why))throw conflict("GATEWAY_REFUND_ALREADY_REVIEWED","This refund request already has an immutable decision. Refresh its history.");
            return new GatewayRefundModels.Result(refundId,request.paymentId(),status(old.action()));
        }
        boolean maker=request.maker().equals(user.userId());
        if(command.decision().equals("CANCEL")?!maker:maker)throw new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_REFUND_INDEPENDENT_REVIEW_REQUIRED","A different authorized person must approve or reject. Only the maker may withdraw an unreviewed request.");
        if(command.decision().equals("APPROVE")){
            Boolean eligible=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM payment_provider_configs provider WHERE provider.id=:providerId
                  AND provider.organisation_id=:organisationId AND provider.school_id=:schoolId
                  AND provider.provider_code=:providerCode AND provider.environment=:environment
                  AND provider.merchant_account_reference=:merchant AND provider.credentials_secret_reference=:credentials)
                AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_allocations allocation JOIN fee_heads head ON head.id=allocation.fee_head_id
                  AND head.organisation_id=allocation.organisation_id AND head.school_id=allocation.school_id
                  WHERE allocation.refund_request_id=:id AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND NOT head.refundable)
                """,p.addValue("providerId",request.providerId()).addValue("providerCode",request.providerCode()).addValue("environment",request.environment())
                    .addValue("merchant",request.merchant()).addValue("credentials",request.credentials()),Boolean.class);
            if(!Boolean.TRUE.equals(eligible))throw conflict("GATEWAY_REFUND_APPROVAL_UNAVAILABLE","The configured refund policy or original provider binding changed. Reject or withdraw and prepare reviewed terms again.");
        }
        jdbc.update("""
            INSERT INTO fee_gateway_refund_decisions(id,organisation_id,school_id,refund_request_id,decision,reason,decided_by_user_id,decided_by_membership_id,decided_at)
            VALUES(:decisionId,:organisationId,:schoolId,:id,:decision,:reason,:userId,:actorMembershipId,:now)
            """,p.addValue("decisionId",UUID.randomUUID()).addValue("decision",command.decision()).addValue("reason",why).addValue("userId",user.userId()).addValue("now",Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
        audit.recordScoped(user,scope.actorMembershipId(),scope.schoolId(),"finance.gateway.refund."+status(command.decision()).toLowerCase(Locale.ROOT),"GATEWAY_REFUND_REQUEST",refundId.toString(),AuditOutcome.SUCCEEDED,why,Map.of("paymentId",request.paymentId()));
        return new GatewayRefundModels.Result(refundId,request.paymentId(),status(command.decision()));
    }

    private Source source(FinanceService.Scope scope,UUID paymentId){
        if(paymentId==null)throw missing();
        var rows=jdbc.query("""
            SELECT payment.id,payment.receipt_number,payment.amount,payment.learner_fee_account_id,learner.legal_name,
              capture.id AS capture_id,capture.payment_provider_config_id,capture.provider_code,capture.provider_environment,
              capture.merchant_account_reference,provider.credentials_secret_reference,capture.order_reference,capture.payment_reference,
              capture.currency_code,attempt.school_time_zone
            FROM fee_payments payment JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
              AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
            JOIN learners learner ON learner.id=account.learner_id AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
            JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
            JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
            JOIN payment_provider_configs provider ON provider.id=capture.payment_provider_config_id AND provider.organisation_id=capture.organisation_id AND provider.school_id=capture.school_id
            WHERE payment.id=:paymentId AND payment.organisation_id=:organisationId AND payment.school_id=:schoolId
              AND payment.status='POSTED' AND payment.payment_method='ONLINE_PROVIDER' AND payment.amount=capture.amount
              AND account.currency_code='INR' AND capture.currency_code=account.currency_code
              AND attempt.learner_fee_account_id=account.id
              AND capture.provider_code=provider.provider_code AND capture.provider_environment=provider.environment
              AND capture.merchant_account_reference=provider.merchant_account_reference
              AND EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions resolution WHERE resolution.capture_id=capture.id
                AND resolution.organisation_id=payment.organisation_id AND resolution.school_id=payment.school_id
                AND resolution.resolution_type='POSTED' AND resolution.fee_payment_id=payment.id AND resolution.open_date=payment.received_on)
            """,scope.params().addValue("paymentId",paymentId),(row,index)->new Source(paymentId,row.getString("receipt_number"),row.getBigDecimal("amount"),row.getObject("learner_fee_account_id",UUID.class),row.getString("legal_name"),
                    row.getObject("capture_id",UUID.class),row.getObject("payment_provider_config_id",UUID.class),row.getString("provider_code"),row.getString("provider_environment"),row.getString("merchant_account_reference"),
                    row.getString("credentials_secret_reference"),row.getString("order_reference"),row.getString("payment_reference"),row.getString("currency_code"),row.getString("school_time_zone")));
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }

    private GatewayRefundModels.Payment projection(FinanceService.Scope scope,Source source){
        var rows=jdbc.query("""
            SELECT charge.id,charge.label_snapshot,head.refundable,paid.amount,
              COALESCE((SELECT sum(allocation.amount) FROM fee_gateway_refund_allocations allocation
                JOIN fee_gateway_refund_requests request ON request.id=allocation.refund_request_id
                  AND request.organisation_id=allocation.organisation_id AND request.school_id=allocation.school_id
                WHERE allocation.original_payment_allocation_id=paid.id AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_decisions decision WHERE decision.refund_request_id=request.id
                    AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id AND decision.decision IN ('REJECT','CANCEL'))),0) AS reserved
            FROM fee_payment_allocations paid JOIN learner_fee_charges charge ON charge.id=paid.learner_fee_charge_id
              AND charge.organisation_id=paid.organisation_id AND charge.school_id=paid.school_id
            JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id AND item.organisation_id=charge.organisation_id AND item.school_id=charge.school_id
            JOIN fee_heads head ON head.id=item.fee_head_id AND head.organisation_id=item.organisation_id AND head.school_id=item.school_id
            WHERE paid.fee_payment_id=:paymentId AND paid.organisation_id=:organisationId AND paid.school_id=:schoolId
              AND charge.learner_fee_account_id=:accountId ORDER BY charge.id
            """,scope.params().addValue("paymentId",source.id()).addValue("accountId",source.accountId()),(row,index)->{
                BigDecimal paid=row.getBigDecimal("amount"),reserved=row.getBigDecimal("reserved");boolean refundable=row.getBoolean("refundable");
                if(reserved.signum()<0||reserved.compareTo(paid)>0)throw conflict("GATEWAY_REFUND_SOURCE_UNAVAILABLE","The original refund budget needs financial review.");
                return new GatewayRefundModels.SourceAllocation(row.getObject("id",UUID.class),row.getString("label_snapshot"),refundable,paid,reserved,refundable?paid.subtract(reserved):BigDecimal.ZERO.setScale(2));
            });
        if(rows.isEmpty()||rows.size()>100||rows.stream().map(GatewayRefundModels.SourceAllocation::originalPaid).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(source.amount())!=0)
            throw conflict("GATEWAY_REFUND_SOURCE_UNAVAILABLE","The original receipt allocations need financial review.");
        BigDecimal reserved=rows.stream().map(GatewayRefundModels.SourceAllocation::reserved).reduce(BigDecimal.ZERO,BigDecimal::add),available=rows.stream().map(GatewayRefundModels.SourceAllocation::available).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new GatewayRefundModels.Payment(scope.schoolId(),source.id(),source.receiptNumber(),source.accountId(),source.learnerName(),source.currency(),source.amount(),reserved,available,scope.canReverse()&&available.signum()>0,newRefundsConfigured(scope),rows);
    }

    private Map<UUID,List<GatewayRefundModels.Allocation>> allocations(FinanceService.Scope scope,List<UUID> ids){
        var result=new HashMap<UUID,List<GatewayRefundModels.Allocation>>();if(ids.isEmpty())return result;
        jdbc.query("""
            SELECT allocation.refund_request_id,allocation.learner_fee_charge_id,charge.label_snapshot,allocation.amount
            FROM fee_gateway_refund_allocations allocation JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
              AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
            WHERE allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.refund_request_id IN(:ids)
            ORDER BY allocation.refund_request_id,allocation.learner_fee_charge_id
            """,scope.params().addValue("ids",ids),row->{result.computeIfAbsent(row.getObject("refund_request_id",UUID.class),ignored->new ArrayList<>())
                .add(new GatewayRefundModels.Allocation(row.getObject("learner_fee_charge_id",UUID.class),row.getString("label_snapshot"),row.getBigDecimal("amount")));});
        return result;
    }

    private void requireContext(AuthenticatedUser user,boolean write){
        var context=TenantRequestContext.current().orElse(null);
        if(user==null||user.userId()==null||user.activeOrganisationId()==null||context==null
            ||!user.userId().equals(context.userId())||!user.activeOrganisationId().equals(context.organisationId())
            ||!TransactionSynchronizationManager.isActualTransactionActive()||(write&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()))throw denied();
        Boolean bound=jdbc.queryForObject("SELECT current_app_organisation_id()=:org AND current_app_user_id()=:actor",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("org",user.activeOrganisationId()).addValue("actor",user.userId()),Boolean.class);
        if(!Boolean.TRUE.equals(bound))throw denied();
    }
    private static String search(String value){if(value!=null&&(value.length()>100||value.codePoints().anyMatch(Character::isISOControl)))throw filters();return value==null||value.isBlank()?null:value.strip();}
    private static String status(String decision){return switch(decision){case "APPROVE"->"APPROVED";case "REJECT"->"REJECTED";case "CANCEL"->"CANCELLED";default->throw new IllegalStateException("Invalid retained refund decision");};}
    private static ApiException filters(){return new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_REFUND_FILTER_INVALID","Choose a school, valid refund state, literal search and bounded page.");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"GATEWAY_REFUND_NOT_FOUND","The original gateway receipt or refund request is unavailable for this school.");}
    private static ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_REFUND_SCOPE_DENIED","Refund access is unavailable for this school and session.");}
    private static ApiException budget(){return conflict("GATEWAY_REFUND_BUDGET_UNAVAILABLE","Choose configured refundable original allocations within their remaining refund amounts.");}
    private static ApiException conflict(String code,String copy){return new ApiException(HttpStatus.CONFLICT,code,copy);}
    private static QueueRow queueRow(ResultSet row)throws SQLException{
        UUID decisionId=row.getObject("decision_id",UUID.class);
        var decision=decisionId==null?null:new GatewayRefundModels.Decision(decisionId,row.getString("decision"),row.getString("decision_reason"),row.getTimestamp("decided_at").toInstant(),row.getString("reviewer_name"));
        return new QueueRow(row.getObject("id",UUID.class),row.getObject("fee_payment_id",UUID.class),row.getString("receipt_number"),row.getString("learner_name"),row.getBigDecimal("amount"),row.getString("currency_code"),row.getString("state"),row.getString("request_reason"),row.getTimestamp("requested_at").toInstant(),row.getObject("requested_by_user_id",UUID.class),row.getString("maker_name"),decision,processing(row));
    }
    /** Evidence summaries are independent of approval state and never grant a write. */
    static GatewayRefundModels.Processing processing(ResultSet row)throws SQLException{
        if(!"APPROVED".equals(row.getString("state")))return null;
        String work=row.getString("work_status"),outcome=row.getString("read_outcome"),status=row.getString("read_status");
        boolean review="REVIEW_REQUIRED".equals(work)||"REVIEW_REQUIRED".equals(outcome);
        Instant checked=nullableInstant(row,"observed_at"),next=nullableInstant(row,"next_check_at");
        if(row.getBoolean("dues_restored"))return new GatewayRefundModels.Processing("DUES_RESTORED",review,checked,null);
        if(row.getBoolean("success_retained"))return new GatewayRefundModels.Processing("VERIFIED_AWAITING_POSTING",review,checked,null);
        if("REVIEW_REQUIRED".equals(outcome))return new GatewayRefundModels.Processing("REVIEW_REQUIRED",true,checked,null);
        if("FAILED".equals(status)||"CANCELLED".equals(status))return new GatewayRefundModels.Processing("PROVIDER_FAILED",true,checked,null);
        if(review)return new GatewayRefundModels.Processing("REVIEW_REQUIRED",true,checked,null);
        if(row.getBoolean("dispatch_recorded")||checked!=null)return new GatewayRefundModels.Processing("PENDING_CONFIRMATION",false,checked,next);
        if(work==null)return new GatewayRefundModels.Processing("NOT_QUEUED",true,checked,null);
        return new GatewayRefundModels.Processing("AWAITING_PROCESSING",false,checked,next);
    }
    private static Instant nullableInstant(ResultSet row,String column)throws SQLException{var value=row.getTimestamp(column);return value==null?null:value.toInstant();}

    /** School configuration hint only, not permission or a promise to execute any particular request. */
    private boolean newRefundsConfigured(FinanceService.Scope scope){
        if(registry==null||!registry.workerEnabled()||!registry.refundsEnabled())return false;
        var routes=registry.configuredRoutes().stream().filter(route->route.organisationId().equals(scope.organisationId())&&route.schoolId().equals(scope.schoolId())
                &&(route.environment()!=CashfreePaymentProviderAdapter.Environment.LIVE||registry.liveEnabled())).toList();
        if(routes.isEmpty())return false;
        var p=scope.params();var matches=new ArrayList<String>();int index=0;
        for(var route:routes){String key="r"+index++;matches.add("(provider.id=:"+key+"Id AND provider.provider_code=:"+key+"Code AND provider.environment=:"+key+"Environment AND provider.merchant_account_reference=:"+key+"Merchant AND provider.credentials_secret_reference=:"+key+"Credentials)");
            p.addValue(key+"Id",route.providerConfigId()).addValue(key+"Code",route.providerCode()).addValue(key+"Environment",route.environment().name()).addValue(key+"Merchant",route.merchantAccountReference()).addValue(key+"Credentials",route.credentialsSecretReference());}
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_provider_configs provider WHERE provider.organisation_id=:organisationId AND provider.school_id=:schoolId AND provider.status='ACTIVE' AND ("+String.join(" OR ",matches)+"))",p,Boolean.class));
    }
    private record QueueRows(long total,List<QueueRow> rows){}
    private record QueueRow(UUID id,UUID paymentId,String receiptNumber,String learnerName,BigDecimal amount,String currency,String state,String reason,Instant requestedAt,UUID maker,String makerName,GatewayRefundModels.Decision decision,GatewayRefundModels.Processing processing){}
    private record Replay(UUID id,UUID paymentId,String fingerprint,String state){}
    private record OldDecision(String action,String reason,UUID actor){}
    private record DecisionSource(UUID paymentId,UUID maker,UUID providerId,String providerCode,String environment,String merchant,String credentials){@Override public String toString(){return "DecisionSource[private frozen refund binding]";}}
    private record Source(UUID id,String receiptNumber,BigDecimal amount,UUID accountId,String learnerName,UUID captureId,UUID providerId,String providerCode,String environment,String merchant,String credentials,String orderRef,String paymentRef,String currency,String zone){@Override public String toString(){return "Source[private original gateway receipt binding]";}}
}
