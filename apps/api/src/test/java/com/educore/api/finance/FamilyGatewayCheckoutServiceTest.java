package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SensitiveValueCipher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked disclosure orchestration; real scope, AES, finance waits and RLS run in PostgreSQL. */
class FamilyGatewayCheckoutServiceTest {
    private final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),learner=UUID.randomUUID(),member=UUID.randomUUID(),attempt=UUID.randomUUID(),account=UUID.randomUUID(),provider=UUID.randomUUID();
    private final AuthenticatedUser parent=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic-family",null,"Synthetic Family",false,org,Set.of(member),Set.of("portal.guardian"),List.of());
    private final FamilyGatewayAccess.Scope scope=new FamilyGatewayAccess.Scope(org,school,learner,member);
    private final FamilyGatewayAccess access=mock(FamilyGatewayAccess.class);
    private final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    private final GatewayProviderRegistry registry=mock(GatewayProviderRegistry.class);
    private final SensitiveValueCipher cipher=mock(SensitiveValueCipher.class);
    private final SecurityAuditService audit=mock(SecurityAuditService.class);
    private final FamilyGatewayCheckoutService service=new FamilyGatewayCheckoutService(jdbc,access,registry,cipher,audit);
    private final Instant expires=Instant.parse("2030-01-01T00:00:00Z");
    private final URI url=URI.create("https://payments-test.cashfree.com/links/synthetic_private_link");
    private final GatewayProviderRegistry.Route route=new GatewayProviderRegistry.Route("d".repeat(64),org,school,provider,"CASHFREE",
            CashfreePaymentProviderAdapter.Environment.TEST,"synthetic_merchant","synthetic:credentials",
            "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
    private final GatewayWorkQueue.ProviderBinding binding=new GatewayWorkQueue.ProviderBinding(org,school,provider,"CASHFREE","TEST","synthetic_merchant","synthetic:credentials");
    private final List<String> events=new ArrayList<>();
    private byte[] decoded;
    private String storedHash;
    private int eligibleCalls, privateRows=1, denyEligibleAfter=Integer.MAX_VALUE, childCalls, denyChildAt=Integer.MAX_VALUE;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(access.requireChild(parent,learner)).thenAnswer(call->{
            events.add("scope"); if(++childCalls==denyChildAt) throw FamilyGatewayAccess.missing(); return scope;
        });
        when(access.requireAccount(parent,learner,account)).thenReturn(new FamilyGatewayAccess.AccountScope(scope,account,"ACTIVE","INR","Synthetic Session"));
        when(registry.creationEnabled()).thenReturn(true); when(registry.workerEnabled()).thenReturn(true);
        when(registry.configuredRoutes()).thenReturn(List.of(route));
        decoded=url.toASCIIString().getBytes(StandardCharsets.UTF_8);
        storedHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (GatewayHostedCheckoutStore.associatedData(binding,attempt)+"\n"+url.toASCIIString()).getBytes(StandardCharsets.UTF_8)));
        when(cipher.decrypt(any(),anyString())).thenAnswer(call->{events.add("decrypt");
            assertThat(call.getArgument(1,String.class)).isEqualTo(GatewayHostedCheckoutStore.associatedData(binding,attempt)); return decoded;
        });
        doAnswer(call->{ events.add("audit"); return null; }).when(audit).recordScoped(any(),any(),any(),anyString(),anyString(),anyString(),any(),anyString(),any());
        doAnswer(call->{
            String sql=call.getArgument(0); SqlParameterSource p=call.getArgument(1); RowMapper<Object> mapper=call.getArgument(2);
            assertThat(p.getValue("organisationId")).isEqualTo(org); assertThat(p.getValue("schoolId")).isEqualTo(school);
            assertThat(p.getValue("attemptId")).isEqualTo(attempt); assertThat(p.getValue("userId")).isEqualTo(parent.userId());
            var row=mock(ResultSet.class);
            if(sql.contains("SELECT attempt.learner_fee_account_id")) {
                events.add("eligibility"); if(++eligibleCalls>denyEligibleAfter) return List.of();
                assertThat(sql).contains("attempt.initiated_by_user_id=:userId","current_app_user_id()=:userId","work.create_dispatched_at IS NOT NULL",
                        "attempt.expires_at>clock_timestamp()","account.status='ACTIVE'","fee_gateway_captures","fee_gateway_payment_observations",
                        "fee_gateway_webhook_inbox","fee_day_closes","reserved.status='ACTIVE'","later.discovery_complete","later.order_references @> uncertain.order_references");
                when(row.getObject("learner_fee_account_id",UUID.class)).thenReturn(account); when(row.getObject("expires_at",OffsetDateTime.class)).thenReturn(expires.atOffset(java.time.ZoneOffset.UTC));
                when(row.getObject("provider_id",UUID.class)).thenReturn(provider); when(row.getString("provider_code")).thenReturn("CASHFREE");
                when(row.getString("environment")).thenReturn("TEST"); when(row.getString("merchant_account_reference")).thenReturn("synthetic_merchant");
                when(row.getString("credentials_secret_reference")).thenReturn("synthetic:credentials");
                return List.of(mapper.mapRow(row,0));
            }
            assertThat(sql).contains("SELECT url_ciphertext,url_nonce,url_sha256","provider_checkout_reference=:attemptId").doesNotContain("FOR SHARE","FOR UPDATE");
            events.add("private-read"); when(row.getString("url_ciphertext")).thenReturn("synthetic_ciphertext"); when(row.getString("url_nonce")).thenReturn("synthetic_nonce"); when(row.getString("url_sha256")).thenAnswer(ignored->storedHash);
            var values=new ArrayList<>(); for(int index=0;index<privateRows;index++) values.add(mapper.mapRow(row,index)); return values;
        }).when(jdbc).query(anyString(),any(SqlParameterSource.class),any(RowMapper.class));
        doAnswer(call->{ events.add("fence"); return true; }).when(jdbc).queryForObject(anyString(),any(SqlParameterSource.class),any(RowMapper.class));
    }
    @AfterEach void cleanup(){ TransactionSynchronizationManager.clear(); }
    @Test void advisoryHintNeverDecryptsAuditsOrLocks() {
        assertThat(service.canContinue(parent,learner,attempt)).isTrue(); verifyNoInteractions(cipher,audit);
        verify(jdbc,never()).queryForObject(anyString(),any(SqlParameterSource.class),any(RowMapper.class));
    }
    @Test void explicitContinuationRechecksScopeAndEvidenceBeforeAndAfterAuditAndClearsRawBytes() {
        var result=service.continueCheckout(parent,learner,attempt);
        assertThat(result.attemptId()).isEqualTo(attempt); assertThat(result.checkoutUrl()).isEqualTo(url); assertThat(result.expiresAt()).isEqualTo(expires);
        assertThat(events).containsSubsequence("scope","fence","scope","eligibility","private-read","decrypt","scope","eligibility","audit","scope","eligibility");
        assertThat(decoded).containsOnly((byte)0); verify(jdbc,never()).update(anyString(),any(SqlParameterSource.class));
    }
    @Test void disabledCreationCannotDisclosePreviouslyStoredUrl() {
        when(registry.creationEnabled()).thenReturn(false); assertThat(service.canContinue(parent,learner,attempt)).isFalse(); conflict(); verifyNoInteractions(cipher,audit);
    }
    @Test void disabledWorkerCannotDisclosePreviouslyStoredUrl() {
        when(registry.workerEnabled()).thenReturn(false); assertThat(service.canContinue(parent,learner,attempt)).isFalse(); conflict(); verifyNoInteractions(cipher,audit);
    }
    @Test void missingCurrentServerRouteCannotFallBackToDatabaseCredentials() {
        when(registry.configuredRoutes()).thenReturn(List.of()); assertThat(service.canContinue(parent,learner,attempt)).isFalse(); conflict(); verifyNoInteractions(cipher,audit);
    }
    @Test void changedCredentialBindingCannotChooseAnotherMerchantRoute() {
        when(registry.configuredRoutes()).thenReturn(List.of(new GatewayProviderRegistry.Route(route.routeKey(),org,school,provider,"CASHFREE",route.environment(),route.merchantAccountReference(),"synthetic:rotated",route.clientIdEnvironmentReference(),route.clientSecretEnvironmentReference())));
        assertThat(service.canContinue(parent,learner,attempt)).isFalse(); conflict(); verifyNoInteractions(cipher,audit);
    }
    @Test void ineligibleMoneyLeaseExpiryOrFinancialStateCannotReadPrivateLink() {
        denyEligibleAfter=0; assertThat(service.canContinue(parent,learner,attempt)).isFalse(); conflict(); verifyNoInteractions(cipher,audit);
    }
    @Test void missingOrConflictingPrivateLinksCannotBeChosenArbitrarily() {
        for(int count:List.of(0,2)){ privateRows=count; conflict(); } verifyNoInteractions(cipher,audit);
    }
    @Test void wrongUrlHashCannotDiscloseOrAppendAnAuthorizationAudit() {
        storedHash="0".repeat(64); conflict(); assertThat(decoded).containsOnly((byte)0); verifyNoInteractions(audit);
    }
    @Test void wrongKeyOrAadErrorsAreCauseFreeAndCannotLeakSensitiveDiagnostics() {
        doThrow(new IllegalStateException("synthetic-private-key-and-ciphertext")).when(cipher).decrypt(any(),anyString()); conflict(); verifyNoInteractions(audit);
    }
    @Test void unsafeDecryptedHostedUriCannotBeRedirectedToAnotherHost() {
        decoded="https://attacker.invalid/links/synthetic".getBytes(StandardCharsets.UTF_8); conflict(); assertThat(decoded).containsOnly((byte)0); verifyNoInteractions(audit);
    }
    @Test void malformedUtf8CannotBeReplacedIntoALegalUrl() {
        decoded=new byte[]{(byte)0xc0,(byte)0xaf}; conflict(); assertThat(decoded).containsOnly((byte)0); verifyNoInteractions(audit);
    }
    @Test void readOnlyCallerCannotCreateAnAuthorizationAudit() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true); conflict(); verifyNoInteractions(cipher,audit);
    }
    @Test void revokedGrantAfterRealFenceCannotDiscloseUrl() {
        denyChildAt=2; assertThatThrownBy(()->service.continueCheckout(parent,learner,attempt)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND)); verifyNoInteractions(cipher,audit);
    }
    @Test void changedEligibilityDuringDecryptionCannotUseAnOldHint() {
        denyEligibleAfter=1; conflict(); assertThat(decoded).containsOnly((byte)0); verifyNoInteractions(audit);
    }
    @Test void expiryOrMoneyAfterAuditWaitRollsBackInsteadOfReturningAUrl() {
        denyEligibleAfter=2; conflict(); assertThat(decoded).containsOnly((byte)0); assertThat(events).contains("audit");
    }
    @Test void continuationDiagnosticsNeverPrintTheBearerUrl() {
        assertThat(new FamilyGatewayCheckoutService.Continuation(attempt,url,expires).toString()).doesNotContain(url.toASCIIString(),attempt.toString());
    }
    private void conflict() {
        assertThatThrownBy(()->service.continueCheckout(parent,learner,attempt)).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT); assertThat(error.code()).isEqualTo("FAMILY_CHECKOUT_UNAVAILABLE");
            assertThat(error.getMessage()).doesNotContain(url.toASCIIString(),"synthetic-private-key","ciphertext"); assertThat(error).hasNoCause();
        });
    }
}
