package com.educore.api.finance;

import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal immutable report provenance only. Commit preparation before provider I/O,
 * allocate a new read ID before each independent HTTP read, and retain its facts in
 * a separate transaction even if later job completion fails. No endpoint, credentials,
 * provider calls, scheduler activation, bank matching or financial posting lives here.
 */
@Service
public class GatewaySettlementReadStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final SensitiveValueCipher cipher;

    public GatewaySettlementReadStore(NamedParameterJdbcTemplate jdbc,SensitiveValueCipher cipher){
        this.jdbc=Objects.requireNonNull(jdbc);this.cipher=Objects.requireNonNull(cipher);
    }

    public enum Kind { SUMMARY, EVENTS }
    public record Prepared(UUID id,GatewayWorkQueue.ProviderBinding binding,Kind kind,UUID rootQueryId,int pageNumber,
            UUID parentReadId,UUID summaryReadId,Instant from,Instant until,String settlementReference,String cursor,Instant preparedAt){
        public Prepared {Objects.requireNonNull(id);Objects.requireNonNull(binding);Objects.requireNonNull(kind);Objects.requireNonNull(rootQueryId);Objects.requireNonNull(preparedAt);}
        GatewaySettlementProvider.SettlementQuery summaryQuery(UUID readId){
            if(kind!=Kind.SUMMARY)throw unavailable();return new GatewaySettlementProvider.SettlementQuery(readId,from,until,cursor);}
        GatewaySettlementProvider.SettlementEventsQuery eventsQuery(UUID readId){
            if(kind!=Kind.EVENTS)throw unavailable();return new GatewaySettlementProvider.SettlementEventsQuery(readId,settlementReference,cursor);}
        @Override public String toString(){return "Prepared[private immutable bounded settlement query]";}
    }
    public record Read(UUID id,Prepared query,Instant observedAt,boolean complete,
            GatewaySettlementProvider.SettlementPage summary,GatewaySettlementProvider.SettlementEventsPage events,String evidenceSha256){
        public Read {Objects.requireNonNull(id);Objects.requireNonNull(query);Objects.requireNonNull(observedAt);Objects.requireNonNull(evidenceSha256);}
        String nextCursor(){return summary!=null?summary.nextCursor():events!=null?events.nextCursor():null;}
        @Override public String toString(){return "Read[private immutable reported page, not bank receipt or posting authority]";}
    }

    /** Server-only root scan. Actual provider sync additionally requires exclusive merchant confirmation. */
    @Transactional
    public Prepared prepareSummaries(GatewayWorkQueue.ProviderBinding binding,UUID queryId,Instant from,Instant until){
        Objects.requireNonNull(queryId);new GatewaySettlementProvider.SettlementQuery(queryId,from,until,null);
        var p=scope(binding).addValue("queryId",queryId).addValue("rootId",queryId).addValue("kind",Kind.SUMMARY.name())
                .addValue("page",1).addValue("parentId",null).addValue("summaryId",null)
                .addValue("from",from.toString()).addValue("until",until.toString()).addValue("settlement",null)
                .addValue("cursorHash",null).addValue("cursorCipher",null).addValue("cursorNonce",null);
        return insertQuery(p,binding);
    }

    /** An event query must name a statement actually observed in this exact school's summary. */
    @Transactional
    public Prepared prepareEvents(GatewayWorkQueue.ProviderBinding binding,UUID queryId,UUID summaryReadId,String reference){
        Objects.requireNonNull(queryId);new GatewaySettlementProvider.SettlementEventsQuery(queryId,reference,null);
        var source=read(binding,summaryReadId);
        if(!source.complete()||source.summary()==null||source.summary().items().stream().noneMatch(row->reference.equals(row.settlementReference())))throw unavailable();
        var p=scope(binding).addValue("queryId",queryId).addValue("rootId",queryId).addValue("kind",Kind.EVENTS.name())
                .addValue("page",1).addValue("parentId",null).addValue("summaryId",summaryReadId)
                .addValue("from",null).addValue("until",null).addValue("settlement",reference)
                .addValue("cursorHash",null).addValue("cursorCipher",null).addValue("cursorNonce",null);
        return insertQuery(p,binding);
    }

    /** Continue only retained complete facts. A cycle/page cap never becomes an invented terminal page. */
    @Transactional
    public Prepared prepareNext(GatewayWorkQueue.ProviderBinding binding,UUID queryId,UUID previousReadId){
        Objects.requireNonNull(queryId);var previous=read(binding,previousReadId);var query=previous.query();
        if(!previous.complete()||previous.nextCursor()==null||query.pageNumber()>=1000)throw unavailable();
        var p=scope(binding).addValue("queryId",queryId).addValue("rootId",query.rootQueryId()).addValue("kind",query.kind().name())
                .addValue("page",query.pageNumber()+1).addValue("parentId",previousReadId).addValue("summaryId",query.summaryReadId())
                .addValue("from",query.from()==null?null:query.from().toString()).addValue("until",query.until()==null?null:query.until().toString())
                .addValue("settlement",query.settlementReference());
        var cursor=jdbc.query("""
                SELECT next_cursor_sha256,next_cursor_ciphertext,next_cursor_nonce FROM fee_gateway_settlement_reads
                WHERE id=:parentId AND organisation_id=:organisationId AND school_id=:schoolId AND query_id=:previousQuery
                """,p.addValue("previousQuery",query.id()),r->{if(!r.next())throw unavailable();return new Cursor(r.getString(1),r.getString(2),r.getString(3));});
        if(cursor==null||!hash(previous.nextCursor()).equals(cursor.hash()))throw unavailable();
        p.addValue("cursorHash",cursor.hash()).addValue("cursorCipher",cursor.ciphertext()).addValue("cursorNonce",cursor.nonce());
        return insertQuery(p,binding);
    }

    /** Historical prepared identity remains readable after disabling or rotating its current provider. */
    @Transactional
    public Prepared load(GatewayWorkQueue.ProviderBinding binding,UUID queryId){return load(scope(binding),binding,queryId);}

    @Transactional
    public Read retainSummaries(GatewayWorkQueue.ProviderBinding binding,UUID queryId,UUID readId,GatewaySettlementProvider.SettlementPage page){
        Objects.requireNonNull(page);return retain(binding,queryId,readId,Kind.SUMMARY,GatewaySettlementFacts.summaries(page.items()),page.nextCursor());
    }
    @Transactional
    public Read retainEvents(GatewayWorkQueue.ProviderBinding binding,UUID queryId,UUID readId,GatewaySettlementProvider.SettlementEventsPage page){
        Objects.requireNonNull(page);return retain(binding,queryId,readId,Kind.EVENTS,GatewaySettlementFacts.events(page.items()),page.nextCursor());
    }
    @Transactional
    public Read retainUnknown(GatewayWorkQueue.ProviderBinding binding,UUID queryId,UUID readId){return retain(binding,queryId,readId,null,null,null);}

    @Transactional
    public Read read(GatewayWorkQueue.ProviderBinding binding,UUID readId){
        Objects.requireNonNull(readId);var p=scope(binding).addValue("readId",readId);
        UUID queryId=jdbc.query("""
                SELECT witness.query_id FROM fee_gateway_settlement_reads witness JOIN fee_gateway_settlement_queries query
                  ON query.id=witness.query_id AND query.organisation_id=witness.organisation_id AND query.school_id=witness.school_id
                WHERE witness.id=:readId AND query.organisation_id=:organisationId AND query.school_id=:schoolId
                  AND query.payment_provider_config_id=:providerId AND query.provider_code=:providerCode AND query.provider_environment=:environment
                  AND query.merchant_account_reference=:merchant AND query.credentials_secret_reference=:credentials
                """,p,r->r.next()?r.getObject(1,UUID.class):null);
        if(queryId==null)throw unavailable();return existing(p,load(p,binding,queryId),readId);
    }

    private Prepared insertQuery(MapSqlParameterSource p,GatewayWorkQueue.ProviderBinding binding){
        // A retry recovers the original immutable preparation even if the current
        // provider was subsequently disabled; this is metadata, not fresh I/O authority.
        boolean retained=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_settlement_queries WHERE id=:queryId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentials)
                """,p,Boolean.class));
        int inserted=retained?0:jdbc.update("""
                INSERT INTO fee_gateway_settlement_queries(id,organisation_id,school_id,payment_provider_config_id,provider_code,
                  provider_environment,merchant_account_reference,credentials_secret_reference,query_kind,root_query_id,page_number,
                  parent_read_id,summary_read_id,from_exact,until_exact,settlement_reference,cursor_sha256,cursor_ciphertext,cursor_nonce)
                VALUES(:queryId,:organisationId,:schoolId,:providerId,:providerCode,:environment,:merchant,:credentials,:kind,:rootId,:page,
                  :parentId,:summaryId,:from,:until,:settlement,:cursorHash,:cursorCipher,:cursorNonce)
                ON CONFLICT(id) DO NOTHING
                """,p);
        var prepared=load(p,binding,(UUID)p.getValue("queryId"));
        if(!Objects.equals(prepared.rootQueryId(),p.getValue("rootId"))||!prepared.kind().name().equals(p.getValue("kind"))
                ||prepared.pageNumber()!=(int)p.getValue("page")||!Objects.equals(prepared.parentReadId(),p.getValue("parentId"))
                ||!Objects.equals(prepared.summaryReadId(),p.getValue("summaryId"))||!Objects.equals(prepared.settlementReference(),p.getValue("settlement"))
                ||!Objects.equals(prepared.from()==null?null:prepared.from().toString(),p.getValue("from"))
                ||!Objects.equals(prepared.until()==null?null:prepared.until().toString(),p.getValue("until"))
                ||!Objects.equals(prepared.cursor()==null?null:hash(prepared.cursor()),p.getValue("cursorHash")))throw unavailable();
        if(inserted!=0&&inserted!=1)throw unavailable();return prepared;
    }

    private Prepared load(MapSqlParameterSource p,GatewayWorkQueue.ProviderBinding binding,UUID queryId){
        Objects.requireNonNull(queryId);
        var result=jdbc.query("""
                SELECT * FROM fee_gateway_settlement_queries WHERE id=:queryId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentials
                """,p.addValue("queryId",queryId),r->{
            if(!r.next())throw unavailable();UUID parent=r.getObject("parent_read_id",UUID.class);
            String cursor=decrypt(binding,parent,r.getString("cursor_sha256"),r.getString("cursor_ciphertext"),r.getString("cursor_nonce"));
            return new Prepared(queryId,binding,Kind.valueOf(r.getString("query_kind")),r.getObject("root_query_id",UUID.class),r.getInt("page_number"),
                    parent,r.getObject("summary_read_id",UUID.class),instant(r.getString("from_exact")),instant(r.getString("until_exact")),
                    r.getString("settlement_reference"),cursor,r.getObject("prepared_at",OffsetDateTime.class).toInstant());
        });
        if(result==null)throw unavailable();return result;
    }

    private Read retain(GatewayWorkQueue.ProviderBinding binding,UUID queryId,UUID readId,Kind expected,String canonical,String next){
        Objects.requireNonNull(readId);var p=scope(binding).addValue("readId",readId);var prepared=load(p,binding,queryId);
        if(expected!=null&&expected!=prepared.kind())throw unavailable();
        validateFacts(prepared,canonical);
        String fingerprint=fingerprint(prepared,readId,canonical,next);
        var prior=existingOrNull(p,prepared,readId);if(prior!=null){if(!prior.evidenceSha256().equals(fingerprint))throw unavailable();return prior;}
        var sealed=next==null?null:cipher.encrypt(next.getBytes(StandardCharsets.UTF_8),aad(binding,readId));
        jdbc.update("""
                INSERT INTO fee_gateway_settlement_reads(id,organisation_id,school_id,query_id,outcome,facts_canonical,evidence_sha256,
                  next_cursor_sha256,next_cursor_ciphertext,next_cursor_nonce)
                VALUES(:readId,:organisationId,:schoolId,:queryId,:outcome,:facts,:hash,:nextHash,:nextCipher,:nextNonce)
                ON CONFLICT(id) DO NOTHING
                """,p.addValue("outcome",canonical==null?"UNKNOWN":"COMPLETE").addValue("facts",canonical).addValue("hash",fingerprint)
                .addValue("nextHash",next==null?null:hash(next)).addValue("nextCipher",sealed==null?null:sealed.ciphertext()).addValue("nextNonce",sealed==null?null:sealed.nonce()));
        var saved=existing(p,prepared,readId);if(!saved.evidenceSha256().equals(fingerprint))throw unavailable();return saved;
    }

    private Read existing(MapSqlParameterSource p,Prepared prepared,UUID readId){var value=existingOrNull(p,prepared,readId);if(value==null)throw unavailable();return value;}
    private Read existingOrNull(MapSqlParameterSource p,Prepared prepared,UUID readId){
        return jdbc.query("""
                SELECT * FROM fee_gateway_settlement_reads WHERE id=:readId AND query_id=:queryId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """,p.addValue("readId",readId).addValue("queryId",prepared.id()),r->{
            if(!r.next())return null;String canonical=r.getString("facts_canonical");validateFacts(prepared,canonical);
            String next=decrypt(prepared.binding(),readId,r.getString("next_cursor_sha256"),r.getString("next_cursor_ciphertext"),r.getString("next_cursor_nonce"));
            String fingerprint=fingerprint(prepared,readId,canonical,next);
            if(!fingerprint.equals(r.getString("evidence_sha256"))||!(canonical==null?"UNKNOWN":"COMPLETE").equals(r.getString("outcome")))throw unavailable();
            return new Read(readId,prepared,r.getObject("observed_at",OffsetDateTime.class).toInstant(),canonical!=null,
                    canonical!=null&&prepared.kind()==Kind.SUMMARY?new GatewaySettlementProvider.SettlementPage(next,GatewaySettlementFacts.readSummaries(canonical)):null,
                    canonical!=null&&prepared.kind()==Kind.EVENTS?new GatewaySettlementProvider.SettlementEventsPage(next,GatewaySettlementFacts.readEvents(canonical)):null,fingerprint);
        });
    }
    private static void validateFacts(Prepared prepared,String canonical){
        if(canonical==null)return;
        if(prepared.kind()==Kind.SUMMARY)GatewaySettlementFacts.readSummaries(canonical);
        else if(GatewaySettlementFacts.readEvents(canonical).stream().anyMatch(row->!prepared.settlementReference().equals(row.settlementReference())))throw unavailable();
    }
    private String decrypt(GatewayWorkQueue.ProviderBinding binding,UUID owner,String fingerprint,String ciphertext,String nonce){
        if(fingerprint==null){if(ciphertext!=null||nonce!=null)throw unavailable();return null;}
        if(owner==null||ciphertext==null||nonce==null)throw unavailable();
        try{String value=new String(cipher.decrypt(new SensitiveValueCipher.EncryptedValue(ciphertext,nonce),aad(binding,owner)),StandardCharsets.UTF_8);
            if(!value.matches("[A-Za-z0-9+/_=.\\-]{1,4096}")||!hash(value).equals(fingerprint))throw unavailable();return value;
        }catch(RuntimeException failed){throw unavailable();}
    }
    private MapSqlParameterSource scope(GatewayWorkQueue.ProviderBinding binding){
        Objects.requireNonNull(binding);var context=TenantRequestContext.current().orElseThrow(GatewaySettlementReadStore::unavailable);
        if(context.userId()!=null||!binding.organisationId().equals(context.organisationId())
                ||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();
        var p=new MapSqlParameterSource().addValue("organisationId",binding.organisationId()).addValue("schoolId",binding.schoolId())
                .addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode()).addValue("environment",binding.environment())
                .addValue("merchant",binding.merchantReference()).addValue("credentials",binding.credentialsReference());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL",p,Boolean.class)))throw unavailable();return p;
    }
    static String fingerprint(Prepared query,UUID readId,String canonical,String next){
        return fingerprintFromHashes(query,readId,canonical,query.cursor()==null?null:hash(query.cursor()),next==null?null:hash(next));
    }
    /** Pure source verification for an already authorized human projection; no cursor decryption or scope switching. */
    static String fingerprintFromHashes(Prepared query,UUID readId,String canonical,String requestCursorSha,String nextCursorSha){
        if(requestCursorSha!=null&&!requestCursorSha.matches("[a-f0-9]{64}")||nextCursorSha!=null&&!nextCursorSha.matches("[a-f0-9]{64}"))throw unavailable();
        return hash(String.join("|","SETTLEMENT_PAGE_V1",query.id().toString(),readId.toString(),bindingFrame(query.binding()),query.kind().name(),
                query.rootQueryId().toString(),String.valueOf(query.pageNumber()),String.valueOf(query.parentReadId()),String.valueOf(query.summaryReadId()),
                String.valueOf(query.from()),String.valueOf(query.until()),String.valueOf(query.settlementReference()),requestCursorSha==null?"-":requestCursorSha,
                canonical==null?"UNKNOWN":canonical,nextCursorSha==null?"-":nextCursorSha));
    }
    private static String aad(GatewayWorkQueue.ProviderBinding binding,UUID owner){return "SETTLEMENT_CURSOR_V1|"+bindingFrame(binding)+"|"+owner;}
    private static String bindingFrame(GatewayWorkQueue.ProviderBinding b){return String.join("|",b.organisationId().toString(),b.schoolId().toString(),b.providerConfigId().toString(),b.providerCode(),b.environment(),b.merchantReference(),b.credentialsReference());}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException failed){throw new IllegalStateException("SHA-256 required");}}
    private static Instant instant(String value){return value==null?null:Instant.parse(value);}
    private record Cursor(String hash,String ciphertext,String nonce){}
    private static IllegalStateException unavailable(){return new IllegalStateException("Settlement evidence requires exact immutable source and writable matching background scope");}
}
