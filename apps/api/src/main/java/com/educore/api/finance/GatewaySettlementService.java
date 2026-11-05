package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Human-authorized report requests/projections only. Never adopts SYSTEM authority or calls a provider. */
@Service
public class GatewaySettlementService {
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayProviderRegistry registry;
    private final SecurityAuditService audit;
    public GatewaySettlementService(NamedParameterJdbcTemplate jdbc,GatewayProviderRegistry registry,SecurityAuditService audit){
        this.jdbc=Objects.requireNonNull(jdbc);this.registry=Objects.requireNonNull(registry);this.audit=Objects.requireNonNull(audit);
    }

    @Transactional(readOnly=true)
    public GatewaySettlementModels.Queue queue(AuthenticatedUser user,UUID schoolId,String status,String kind,String query,int page,int size){
        GatewaySettlementTerms.page(page,size);String q=GatewaySettlementTerms.search(query);
        if(!GatewaySettlementTerms.STATUSES.contains(status==null?"":status)||!GatewaySettlementTerms.KINDS.contains(kind==null?"":kind))throw GatewaySettlementTerms.filters();
        Scope scope=scope(user,schoolId,false);
        var p=scope.params().addValue("status",status).addValue("kind",kind).addValue("emptySearch",q.isEmpty())
                .addValue("search",GatewaySettlementTerms.like(q)).addValue("limit",size).addValue("offset",(long)page*size);
        long total=Objects.requireNonNull(jdbc.queryForObject(REQUEST_STATE+"SELECT count(*) FROM request_state WHERE "+REQUEST_FILTER,p,Long.class));
        var items=jdbc.query(REQUEST_STATE+"SELECT * FROM request_state WHERE "+REQUEST_FILTER+" ORDER BY requested_at DESC,id DESC LIMIT :limit OFFSET :offset",p,(row,n)->request(row));
        boolean enabled=allowedRoutes(scope).size()==1;
        return new GatewaySettlementModels.Queue(schoolId,scope.timeZone(),enabled,scope.canRequest()&&enabled,status,kind,q,page,size,total,(long)page*size+items.size()<total,items);
    }

    @Transactional
    public GatewaySettlementModels.Result submit(AuthenticatedUser user,GatewaySettlementModels.Submit command){
        var terms=GatewaySettlementTerms.prepare(command);Scope scope=scope(user,terms.schoolId(),true);
        var p=scope.params().addValue("client",terms.clientRequestId());
        Replay previous=replay(p);if(previous!=null)return exact(previous,terms);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",
                p.addValue("lockKey",scope.schoolId()+":gateway-settlement-requests"),Integer.class);
        // Re-resolve live authority after the school-request lock, not cached claims.
        scope=scope(user,terms.schoolId(),true);p=scope.params().addValue("client",terms.clientRequestId());
        previous=replay(p);if(previous!=null)return exact(previous,terms);
        if(!scope.timeZone().equals(terms.schoolTimeZone()))throw conflict("GATEWAY_SETTLEMENT_TIME_ZONE_CHANGED","The school timezone changed. Refresh before requesting this report.");
        var routes=allowedRoutes(scope);if(routes.size()!=1)throw unavailable();
        var route=routes.getFirst();var binding=binding(route);
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",p,(row,n)->row.getObject(1,OffsetDateTime.class).toInstant());
        if(now==null)throw unavailable();
        if("SUMMARY".equals(terms.kind())&&terms.until().isAfter(now))throw new ApiException(HttpStatus.BAD_REQUEST,"GATEWAY_SETTLEMENT_FUTURE_INTERVAL","The settlement report interval must end in the past.");
        if("EVENTS".equals(terms.kind())){
            var source=selectedEvidence(scope,terms.sourceSummaryReadId());
            if(source.prepared().kind()!=GatewaySettlementReadStore.Kind.SUMMARY||!binding.equals(source.prepared().binding())
                    ||source.summaries().stream().noneMatch(item->terms.settlementReference().equals(item.settlementReference())))throw missing();
        }
        UUID id=UUID.randomUUID();p.addValue("id",id).addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode())
                .addValue("environment",binding.environment()).addValue("merchant",binding.merchantReference()).addValue("credentials",binding.credentialsReference())
                .addValue("kind",terms.kind()).addValue("zone",terms.schoolTimeZone()).addValue("from",text(terms.from())).addValue("until",text(terms.until()))
                .addValue("summaryRead",terms.sourceSummaryReadId()).addValue("settlement",terms.settlementReference()).addValue("fingerprint",terms.fingerprint());
        int inserted;
        try{
            inserted=jdbc.update("""
                    INSERT INTO fee_gateway_settlement_requests(id,organisation_id,school_id,payment_provider_config_id,provider_code,
                      provider_environment,merchant_account_reference,credentials_secret_reference,query_kind,school_time_zone,
                      from_exact,until_exact,source_summary_read_id,settlement_reference,client_request_id,request_fingerprint,
                      requested_by_user_id,requested_by_membership_id)
                    VALUES(:id,:organisationId,:schoolId,:providerId,:providerCode,:environment,:merchant,:credentials,:kind,:zone,
                      :from,:until,:summaryRead,:settlement,:client,:fingerprint,:userId,:membershipId)
                    ON CONFLICT(school_id,requested_by_user_id,client_request_id) DO NOTHING
                    """,p);
        }catch(DataAccessException failure){if(requestConflict(failure))throw conflict("GATEWAY_SETTLEMENT_REQUEST_CONFLICT","A report is already active or the request limit was reached. Review existing requests before retrying.");throw failure;}
        if(inserted==0){var retained=replay(p);if(retained==null)throw unavailable();return exact(retained,terms);}
        if(inserted!=1)throw unavailable();
        audit.recordScoped(user,scope.membershipId(),scope.schoolId(),"finance.gateway.settlement.requested","GATEWAY_SETTLEMENT_REQUEST",id.toString(),
                AuditOutcome.SUCCEEDED,"Requested a read-only gateway report; not bank confirmation or financial posting",Map.of("kind",terms.kind(),"schoolTimeZone",terms.schoolTimeZone()));
        return new GatewaySettlementModels.Result(id,scope.schoolId(),terms.kind());
    }

    @Transactional(readOnly=true)
    public GatewaySettlementModels.Report report(AuthenticatedUser user,UUID schoolId,UUID requestId,String query,int page,int size){
        GatewaySettlementTerms.page(page,size);String q=GatewaySettlementTerms.search(query);Scope scope=scope(user,schoolId,false);
        if(requestId==null)throw missing();var p=scope.params().addValue("requestId",requestId).addValue("emptySearch",q.isEmpty())
                .addValue("search",GatewaySettlementTerms.like(q)).addValue("limit",size).addValue("offset",(long)page*size);
        var requests=jdbc.query(REQUEST_STATE+"SELECT * FROM request_state WHERE id=:requestId",p,(row,n)->request(row));
        if(requests.size()!=1)throw missing();
        long total=Objects.requireNonNull(jdbc.queryForObject(REPORT_ROWS+"SELECT count(*) FROM report_rows WHERE "+ROW_FILTER,p,Long.class));
        Map<UUID,Evidence> decoded=new HashMap<>();
        var rows=jdbc.query(REPORT_ROWS+"SELECT * FROM report_rows WHERE "+ROW_FILTER+" ORDER BY page_number,row_index LIMIT :limit OFFSET :offset",p,(row,n)->{
            UUID readId=row.getObject("read_id",UUID.class);var evidence=decoded.get(readId);
            if(evidence==null){evidence=evidence(row);decoded.put(readId,evidence);}
            int index=row.getInt("row_index");if(index<1||index>10)throw invalidEvidence();
            GatewaySettlementModels.Summary summary=null;GatewaySettlementModels.Event event=null;
            if(evidence.prepared().kind()==GatewaySettlementReadStore.Kind.SUMMARY){if(index>evidence.summaries().size())throw invalidEvidence();summary=summary(evidence.summaries().get(index-1));}
            else{if(index>evidence.events().size())throw invalidEvidence();event=event(evidence.events().get(index-1));}
            return new GatewaySettlementModels.Row(readId,index-1,evidence.observedAt(),summary,event);
        });
        return new GatewaySettlementModels.Report(schoolId,requests.getFirst(),q,page,size,total,(long)page*size+rows.size()<total,rows);
    }

    /** Internal human-only accessor: validate every selected page before source matching or response pagination. */
    GatewaySettlementSourceReviewEvidence.Chain completeEvents(AuthenticatedUser user,UUID schoolId,UUID requestId){
        Scope scope=scope(user,schoolId,false);if(requestId==null)throw missing();
        var p=scope.params().addValue("requestId",requestId);
        var requests=jdbc.query(REQUEST_STATE+"SELECT * FROM request_state WHERE id=:requestId",p,(row,n)->request(row));
        if(requests.size()!=1)throw missing();var request=requests.getFirst();
        if(!"EVENTS".equals(request.kind())||!"COMPLETE".equals(request.status())||request.pagesCompleted()<1
                ||request.pagesCompleted()>1000||request.pagesPending()!=0||request.pagesReview()!=0)throw invalidEvidence();
        var pages=jdbc.query(SELECTED_PAGES+"SELECT * FROM selected_pages WHERE request_id=:requestId ORDER BY page_number,query_id LIMIT 1001",p,(row,n)->{
            var value=evidence(row);
            return new GatewaySettlementSourceReviewEvidence.Page(row.getObject("read_id",UUID.class),value.prepared(),
                    row.getString("cursor_sha256"),row.getString("next_cursor_sha256"),value.observedAt(),value.events());
        });
        if(pages.isEmpty())throw invalidEvidence();
        return new GatewaySettlementSourceReviewEvidence.Chain(scope.organisationId(),scope.schoolId(),request,pages.getFirst().query().binding(),pages);
    }

    /** Same human authority and full retained chain; never uses current provider flags or credentials. */
    GatewaySettlementBankEvidenceService.Source bankEvidenceSource(AuthenticatedUser user,UUID schoolId,UUID requestId){
        var chain=completeEvents(user,schoolId,requestId);var scope=scope(user,schoolId,false);
        var source=selectedEvidence(scope,chain.request().sourceSummaryReadId());
        if(source.prepared().kind()!=GatewaySettlementReadStore.Kind.SUMMARY||!chain.binding().equals(source.prepared().binding()))throw invalidEvidence();
        int selected=-1;
        for(int index=0;index<source.summaries().size();index++)if(chain.request().settlementReference().equals(source.summaries().get(index).settlementReference())){
            if(selected!=-1)throw invalidEvidence();selected=index;
        }
        if(selected<0)throw invalidEvidence();
        return new GatewaySettlementBankEvidenceService.Source(scope.organisationId(),scope.schoolId(),requestId,chain.request().schoolTimeZone(),
                chain.request().settlementReference(),chain.request().sourceSummaryReadId(),selected,source.observedAt(),source.summaries().get(selected));
    }

    /** Re-resolve the actual eligible membership for each action, including a distinct approver membership. */
    GatewaySettlementBankEvidenceService.Authority bankEvidenceAuthority(AuthenticatedUser user,UUID schoolId,String required){
        var scope=scope(user,schoolId,false);
        if(required!=null&&(!List.of("fees.close.propose","fees.close.approve").contains(required)
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()))throw denied();
        var rows=jdbc.query("""
                /* human-settlement-bank-authority */
                SELECT member.id membership_id,school.time_zone,
                  EXISTS(SELECT 1 FROM membership_roles assigned JOIN roles role ON role.id=assigned.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id WHERE assigned.membership_id=member.id
                    AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    AND capability.capability_code='fees.close.propose') can_record,
                  EXISTS(SELECT 1 FROM membership_roles assigned JOIN roles role ON role.id=assigned.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id WHERE assigned.membership_id=member.id
                    AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    AND capability.capability_code='fees.close.approve') can_reject
                FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
                JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
                WHERE member.user_id=:userId AND member.organisation_id=:organisationId AND member.school_id=:schoolId
                  AND member.id IN(:memberships) AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
                  AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                  AND (SELECT count(DISTINCT capability.capability_code) FROM membership_roles assigned JOIN roles role ON role.id=assigned.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id WHERE assigned.membership_id=member.id
                    AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    AND capability.capability_code IN('fees.read','accounting.read'))=2
                ORDER BY member.created_at,member.id LIMIT 1001
                """,scope.params().addValue("memberships",user.membershipIds()),(row,n)->new GatewaySettlementBankEvidenceService.Authority(
                        scope.organisationId(),scope.schoolId(),scope.userId(),row.getObject("membership_id",UUID.class),row.getString("time_zone"),row.getBoolean("can_record"),row.getBoolean("can_reject")));
        if(rows.isEmpty()||rows.size()>1000)throw denied();
        if(required!=null)return rows.stream().filter(row->"fees.close.propose".equals(required)?row.canRecord():row.canReject()).findFirst().orElseThrow(GatewaySettlementService::denied);
        var first=rows.getFirst();return new GatewaySettlementBankEvidenceService.Authority(first.organisationId(),first.schoolId(),first.userId(),first.membershipId(),first.timeZone(),
                rows.stream().anyMatch(GatewaySettlementBankEvidenceService.Authority::canRecord),rows.stream().anyMatch(GatewaySettlementBankEvidenceService.Authority::canReject));
    }

    private Scope scope(AuthenticatedUser user,UUID schoolId,boolean write){
        var context=TenantRequestContext.current().orElse(null);
        if(user==null||user.userId()==null||user.activeOrganisationId()==null||user.membershipIds().isEmpty()||schoolId==null||context==null
                ||!user.userId().equals(context.userId())||!user.activeOrganisationId().equals(context.organisationId())
                ||!TransactionSynchronizationManager.isActualTransactionActive()||write&&TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw denied();
        var p=new MapSqlParameterSource().addValue("organisationId",user.activeOrganisationId()).addValue("schoolId",schoolId)
                .addValue("userId",user.userId()).addValue("memberships",user.membershipIds());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId",p,Boolean.class)))throw denied();
        var rows=jdbc.query("""
                /* human-settlement-live-scope */
                SELECT member.id AS membership_id,school.time_zone,
                  EXISTS(SELECT 1 FROM membership_roles grant_role JOIN roles role ON role.id=grant_role.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id
                    WHERE grant_role.membership_id=member.id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                      AND capability.capability_code='fees.close.propose') can_request
                FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
                JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
                WHERE member.user_id=:userId AND member.organisation_id=:organisationId AND member.school_id=:schoolId
                  AND member.id IN(:memberships) AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
                  AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                  AND (SELECT count(DISTINCT capability.capability_code) FROM membership_roles grant_role JOIN roles role ON role.id=grant_role.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id
                    WHERE grant_role.membership_id=member.id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                      AND capability.capability_code IN('fees.read','accounting.read'))=2
                ORDER BY can_request DESC,member.created_at,member.id LIMIT 1
                """,p,(row,n)->new Scope(user.activeOrganisationId(),schoolId,user.userId(),row.getObject("membership_id",UUID.class),row.getString("time_zone"),row.getBoolean("can_request")));
        if(rows.size()!=1||write&&!rows.getFirst().canRequest())throw denied();
        var scope=rows.getFirst();try{if(!ZoneId.of(scope.timeZone()).getId().equals(scope.timeZone()))throw unavailable();}catch(RuntimeException invalid){throw unavailable();}return scope;
    }
    private List<GatewayProviderRegistry.Route> allowedRoutes(Scope scope){
        var routes=new ArrayList<GatewayProviderRegistry.Route>();
        for(var route:registry.configuredRoutes()){
            if(!scope.organisationId().equals(route.organisationId())||!scope.schoolId().equals(route.schoolId())||!registry.settlementsAllowed(route))continue;
            var p=scope.params().addValue("providerId",route.providerConfigId()).addValue("providerCode",route.providerCode()).addValue("environment",route.environment().name())
                    .addValue("merchant",route.merchantAccountReference()).addValue("credentials",route.credentialsSecretReference());
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM payment_provider_configs provider WHERE id=:providerId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND provider_code=:providerCode AND environment=:environment AND merchant_account_reference=:merchant
                      AND credentials_secret_reference=:credentials AND status='ACTIVE')
                    """,p,Boolean.class)))routes.add(route);
        }
        return List.copyOf(routes);
    }
    private Replay replay(MapSqlParameterSource p){return jdbc.query("""
            SELECT id,query_kind,school_time_zone,request_fingerprint FROM fee_gateway_settlement_requests
            WHERE organisation_id=:organisationId AND school_id=:schoolId AND requested_by_user_id=:userId AND client_request_id=:client
            """,p,row->{if(!row.next())return null;var value=new Replay(row.getObject("id",UUID.class),row.getString("query_kind"),row.getString("school_time_zone"),row.getString("request_fingerprint"));if(row.next())throw invalidEvidence();return value;});}
    private static GatewaySettlementModels.Result exact(Replay previous,GatewaySettlementTerms.Terms terms){
        if(!previous.fingerprint().equals(terms.fingerprint())||!previous.kind().equals(terms.kind())||!previous.timeZone().equals(terms.schoolTimeZone()))throw conflict("GATEWAY_SETTLEMENT_REQUEST_REUSED","This request identity already belongs to different report terms.");
        return new GatewaySettlementModels.Result(previous.id(),terms.schoolId(),terms.kind());
    }
    private Evidence selectedEvidence(Scope scope,UUID readId){
        var rows=jdbc.query(SELECTED_PAGES+"SELECT * FROM selected_pages WHERE read_id=:readId",scope.params().addValue("readId",readId),(row,n)->evidence(row));
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }
    private static Evidence evidence(ResultSet row)throws SQLException{
        try{return decodeEvidence(row);}catch(IllegalArgumentException|IllegalStateException invalid){throw invalidEvidence();}
    }
    private static Evidence decodeEvidence(ResultSet row)throws SQLException{
        UUID readId=row.getObject("read_id",UUID.class),queryId=row.getObject("query_id",UUID.class);
        var binding=new GatewayWorkQueue.ProviderBinding(row.getObject("organisation_id",UUID.class),row.getObject("school_id",UUID.class),row.getObject("payment_provider_config_id",UUID.class),
                row.getString("provider_code"),row.getString("provider_environment"),row.getString("merchant_account_reference"),row.getString("credentials_secret_reference"));
        var query=new GatewaySettlementReadStore.Prepared(queryId,binding,GatewaySettlementReadStore.Kind.valueOf(row.getString("query_kind")),row.getObject("root_query_id",UUID.class),row.getInt("page_number"),
                row.getObject("parent_read_id",UUID.class),row.getObject("summary_read_id",UUID.class),instant(row.getString("from_exact")),instant(row.getString("until_exact")),
                row.getString("settlement_reference"),null,time(row,"prepared_at"));
        String canonical=row.getString("facts_canonical"),cursorHash=row.getString("cursor_sha256"),nextHash=row.getString("next_cursor_sha256");
        if(canonical==null||!validHash(cursorHash)||!validHash(nextHash)||!"COMPLETE".equals(row.getString("read_outcome"))
                ||!GatewaySettlementReadStore.fingerprintFromHashes(query,readId,canonical,cursorHash,nextHash).equals(row.getString("evidence_sha256")))throw invalidEvidence();
        Instant observed=time(row,"observed_at");if(observed==null||query.preparedAt()==null||observed.isBefore(query.preparedAt()))throw invalidEvidence();
        var summaries=query.kind()==GatewaySettlementReadStore.Kind.SUMMARY?GatewaySettlementFacts.readSummaries(canonical):List.<GatewaySettlementProvider.SettlementSummary>of();
        var events=query.kind()==GatewaySettlementReadStore.Kind.EVENTS?GatewaySettlementFacts.readEvents(canonical):List.<GatewaySettlementProvider.SettlementEvent>of();
        if(events.stream().anyMatch(item->!query.settlementReference().equals(item.settlementReference())||item.occurredAt().isAfter(observed))
                ||summaries.stream().anyMatch(item->item.initiatedAt()!=null&&item.initiatedAt().isAfter(observed)||item.processedAt()!=null&&item.processedAt().isAfter(observed)))throw invalidEvidence();
        return new Evidence(query,observed,summaries,events);
    }
    private static GatewaySettlementModels.Request request(ResultSet row)throws SQLException{return new GatewaySettlementModels.Request(row.getObject("id",UUID.class),row.getString("query_kind"),row.getString("school_time_zone"),row.getString("derived_status"),
            instant(row.getString("from_exact")),instant(row.getString("until_exact")),row.getObject("source_summary_read_id",UUID.class),row.getString("settlement_reference"),time(row,"requested_at"),row.getString("requested_by"),
            row.getInt("pages_completed"),row.getInt("pages_pending"),row.getInt("pages_review"),time(row,"last_checked_at"),time(row,"next_check_at"));}
    private static GatewaySettlementModels.Summary summary(GatewaySettlementProvider.SettlementSummary s){return new GatewaySettlementModels.Summary(s.settlementReference(),s.reportedStatus(),s.transferReference(),s.paymentsFrom(),s.paymentsThrough(),s.initiatedAt(),s.processedAt(),
            money(s.reportedPaymentAmount()),money(s.reportedServiceCharge()),money(s.reportedServiceTax()),money(s.reportedAdjustment()),money(s.reportedSettlementCharge()),money(s.reportedSettlementTax()),money(s.reportedSplitCharge()),money(s.reportedSplitTax()),money(s.reportedVendorCommission()),money(s.reportedAmountSettled()),s.reportedChargeType(),s.reportedSettlementType());}
    private static GatewaySettlementModels.Event event(GatewaySettlementProvider.SettlementEvent e){return new GatewaySettlementModels.Event(e.settlementReference(),e.eventReference(),e.reportedType(),e.reportedDirection(),e.reportedStatus(),e.currencyCode(),money(e.reportedAmount()),money(e.reportedServiceCharge()),money(e.reportedServiceTax()),money(e.reportedSettlementAmount()),e.occurredAt(),e.orderReference(),e.paymentReference(),e.refundReferences(),e.chargesCurrencyCode());}
    private static String money(BigDecimal value){return value==null?null:value.toPlainString();}
    private static String text(Instant value){return value==null?null:value.toString();}
    private static Instant instant(String value){return value==null?null:GatewayExactInstant.parseCanonical(value);}
    private static Instant time(ResultSet row,String name)throws SQLException{var value=row.getObject(name,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static boolean validHash(String value){return value==null||value.matches("[a-f0-9]{64}");}
    private static GatewayWorkQueue.ProviderBinding binding(GatewayProviderRegistry.Route route){return new GatewayWorkQueue.ProviderBinding(route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),route.environment().name(),route.merchantAccountReference(),route.credentialsSecretReference());}
    private static boolean requestConflict(DataAccessException failure){for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&"P0001".equals(sql.getSQLState())){
        String m=sql.getMessage();if(m!=null&&(m.startsWith("settlement request active overlap")||m.startsWith("ERROR: settlement request active overlap")||m.startsWith("settlement request rate limit")||m.startsWith("ERROR: settlement request rate limit")))return true;
    }return false;}
    private static ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_SCOPE_DENIED","Settlement reports require current school-scoped finance and accounting authority.");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"GATEWAY_SETTLEMENT_NOT_FOUND","The selected report or statement is unavailable for this school.");}
    private static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"GATEWAY_SETTLEMENT_SYNC_UNAVAILABLE","Read-only settlement sync requires exactly one active approved school provider route.");}
    private static ApiException invalidEvidence(){return new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE","The retained report requires evidence review; no bank receipt or financial posting is implied.");}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
    private record Scope(UUID organisationId,UUID schoolId,UUID userId,UUID membershipId,String timeZone,boolean canRequest){MapSqlParameterSource params(){return new MapSqlParameterSource().addValue("organisationId",organisationId).addValue("schoolId",schoolId).addValue("userId",userId).addValue("membershipId",membershipId);}}
    private record Replay(UUID id,String kind,String timeZone,String fingerprint){}
    private record Evidence(GatewaySettlementReadStore.Prepared prepared,Instant observedAt,List<GatewaySettlementProvider.SettlementSummary> summaries,List<GatewaySettlementProvider.SettlementEvent> events){}

    private static final String REQUEST_FILTER="(:status='ALL' OR derived_status=:status) AND (:kind='ALL' OR query_kind=:kind) AND (:emptySearch OR concat_ws(' ',id::text,settlement_reference,requested_by) ILIKE :search ESCAPE '\\')";
    private static final String ROW_FILTER="(:emptySearch OR concat_ws(' ',item->>'settlementReference',item->>'eventReference',item->>'orderReference',item->>'paymentReference',item->>'reportedStatus',item->>'reportedType',item->>'transferReference') ILIKE :search ESCAPE '\\')";
    private static final String REQUEST_STATE="""
            WITH request_state AS (
              SELECT request.*,actor.display_name requested_by,stats.pages_completed,stats.pages_review,
                (stats.pages_total-stats.pages_completed-stats.pages_review)::integer pages_pending,stats.last_checked_at,stats.next_check_at,
                CASE WHEN stats.pages_review>0 OR stats.invalid_complete>0 THEN 'REVIEW_REQUIRED'
                  WHEN stats.pages_total>0 AND stats.pages_completed=stats.pages_total AND stats.terminal_count=1 THEN 'COMPLETE'
                  WHEN stats.leased_count>0 THEN 'SYNCING' WHEN stats.retry_count>0 THEN 'RETRYING'
                  WHEN stats.pages_completed>0 THEN 'SYNCING' ELSE 'REQUESTED' END derived_status
              FROM fee_gateway_settlement_requests request JOIN user_accounts actor ON actor.id=request.requested_by_user_id
              CROSS JOIN LATERAL (
                SELECT count(*)::integer pages_total,
                  count(*) FILTER(WHERE work.status='COMPLETE' AND selection.outcome='COMPLETE' AND witness.outcome='COMPLETE')::integer pages_completed,
                  count(*) FILTER(WHERE work.status='REVIEW_REQUIRED' OR selection.outcome='REVIEW_REQUIRED')::integer pages_review,
                  count(*) FILTER(WHERE work.status='COMPLETE' AND (selection.query_id IS NULL OR witness.id IS NULL OR selection.outcome<>'COMPLETE' OR witness.outcome<>'COMPLETE')) invalid_complete,
                  count(*) FILTER(WHERE work.status='COMPLETE' AND selection.outcome='COMPLETE' AND witness.outcome='COMPLETE' AND witness.next_cursor_sha256 IS NULL) terminal_count,
                  count(*) FILTER(WHERE work.status='LEASED') leased_count,count(*) FILTER(WHERE work.status='RETRY') retry_count,
                  max(latest_attempt.observed_at) last_checked_at,min(work.next_attempt_at) FILTER(WHERE work.status IN('READY','RETRY')) next_check_at
                FROM fee_gateway_settlement_work work LEFT JOIN fee_gateway_settlement_queries query
                  ON query.id=work.id AND query.organisation_id=work.organisation_id AND query.school_id=work.school_id AND query.root_query_id=request.id
                  AND query.payment_provider_config_id=request.payment_provider_config_id AND query.provider_code=request.provider_code
                  AND query.provider_environment=request.provider_environment AND query.merchant_account_reference=request.merchant_account_reference
                  AND query.credentials_secret_reference=request.credentials_secret_reference AND query.query_kind=request.query_kind
                  AND query.from_exact IS NOT DISTINCT FROM request.from_exact AND query.until_exact IS NOT DISTINCT FROM request.until_exact
                  AND query.summary_read_id IS NOT DISTINCT FROM request.source_summary_read_id AND query.settlement_reference IS NOT DISTINCT FROM request.settlement_reference
                LEFT JOIN fee_gateway_settlement_selections selection ON selection.query_id=query.id AND selection.request_id=request.id
                  AND selection.organisation_id=request.organisation_id AND selection.school_id=request.school_id
                LEFT JOIN fee_gateway_settlement_reads witness ON witness.id=selection.read_id AND witness.query_id=query.id
                  AND witness.organisation_id=request.organisation_id AND witness.school_id=request.school_id
                LEFT JOIN LATERAL (
                  SELECT attempt.observed_at FROM fee_gateway_settlement_reads attempt
                  WHERE attempt.query_id=query.id AND attempt.organisation_id=request.organisation_id AND attempt.school_id=request.school_id
                    AND attempt.outcome IN('UNKNOWN','COMPLETE')
                  ORDER BY attempt.observed_at DESC,attempt.id LIMIT 1
                ) latest_attempt ON true
                WHERE work.request_id=request.id AND work.organisation_id=request.organisation_id AND work.school_id=request.school_id
              ) stats WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
            )
            """;
    private static final String SELECTED_PAGES="""
            WITH selected_pages AS (
              SELECT query.id query_id,query.organisation_id,query.school_id,query.payment_provider_config_id,query.provider_code,
                query.provider_environment,query.merchant_account_reference,query.credentials_secret_reference,query.query_kind,
                query.root_query_id,query.page_number,query.parent_read_id,query.summary_read_id,query.from_exact,query.until_exact,
                query.settlement_reference,query.cursor_sha256,query.prepared_at,witness.id read_id,witness.outcome read_outcome,witness.facts_canonical,
                witness.evidence_sha256,witness.next_cursor_sha256,witness.observed_at,request.id request_id
              FROM fee_gateway_settlement_requests request JOIN fee_gateway_settlement_work work
                ON work.request_id=request.id AND work.organisation_id=request.organisation_id AND work.school_id=request.school_id AND work.status IN('COMPLETE','REVIEW_REQUIRED')
              JOIN fee_gateway_settlement_queries query ON query.id=work.id AND query.organisation_id=work.organisation_id AND query.school_id=work.school_id
                AND query.root_query_id=request.id AND query.query_kind=request.query_kind AND query.payment_provider_config_id=request.payment_provider_config_id
                AND query.provider_code=request.provider_code AND query.provider_environment=request.provider_environment
                AND query.merchant_account_reference=request.merchant_account_reference AND query.credentials_secret_reference=request.credentials_secret_reference
                AND query.from_exact IS NOT DISTINCT FROM request.from_exact AND query.until_exact IS NOT DISTINCT FROM request.until_exact
                AND query.summary_read_id IS NOT DISTINCT FROM request.source_summary_read_id AND query.settlement_reference IS NOT DISTINCT FROM request.settlement_reference
              JOIN fee_gateway_settlement_selections selection ON selection.query_id=query.id AND selection.request_id=request.id
                AND selection.organisation_id=request.organisation_id AND selection.school_id=request.school_id
              JOIN fee_gateway_settlement_reads witness ON witness.id=selection.read_id AND witness.query_id=query.id
                AND witness.organisation_id=request.organisation_id AND witness.school_id=request.school_id AND witness.outcome='COMPLETE'
              WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
            )
            """;
    private static final String REPORT_ROWS=SELECTED_PAGES.substring(0,SELECTED_PAGES.lastIndexOf(')'))+"""
            ), report_rows AS (
              SELECT selected_pages.*,element.item,element.ordinality::integer row_index FROM selected_pages
              CROSS JOIN LATERAL jsonb_array_elements(facts_canonical::jsonb) WITH ORDINALITY AS element(item,ordinality)
              WHERE request_id=:requestId
            )
            """;
}
