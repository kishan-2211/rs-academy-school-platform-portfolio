package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GatewayWebhookRequestPath;
import com.educore.api.common.api.GlobalExceptionHandler;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.finance.FinanceController;
import com.educore.api.finance.FinanceService;
import com.educore.api.finance.GatewayCaptureQueueResponse;
import com.educore.api.finance.GatewayWebhookController;
import com.educore.api.finance.GatewayWebhookIntake;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Actual HTTP/security/session chain with mocked committed intake, not merchant or PostgreSQL acceptance. */
class GatewayWebhookControllerTest {
    private static final int MAX_BYTES = 256 * 1024;
    private static final String ROUTE = "a".repeat(64), TIME = "1600000000000";
    private static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CALLBACK = GatewayWebhookRequestPath.PREFIX + ROUTE;
    private static final String QUEUE = "/api/v1/finance/gateway/captures";
    private static final String READER_TOKEN = "synthetic-callback-reader-session-00001";
    private static final String COLLECTOR_TOKEN = "synthetic-callback-collector-session-01";
    private final UUID organisation = UUID.randomUUID(), school = UUID.randomUUID(), capture = UUID.randomUUID(), inbox = UUID.randomUUID();
    private final AuthenticatedUser reader = actor("Synthetic callback reader", "fees.read");
    private final AuthenticatedUser collector = actor("Synthetic callback collector", "fees.collect");
    private final List<byte[]> retainedBytes = new ArrayList<>();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private GatewayWebhookIntake intake;
    private FinanceService finance;
    private SessionService sessions;
    private PrivilegedMfaPolicy mfa;
    private byte[] lastRaw;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        intake = context.getBean(GatewayWebhookIntake.class); finance = context.getBean(FinanceService.class);
        sessions = context.getBean(SessionService.class); mfa = context.getBean(PrivilegedMfaPolicy.class);
        when(sessions.authenticate(READER_TOKEN)).thenReturn(session(reader));
        when(sessions.authenticate(COLLECTOR_TOKEN)).thenReturn(session(collector));
        when(intake.accept(anyString(), any(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            var principal = SecurityContextHolder.getContext().getAuthentication();
            assertThat(principal == null || !(principal.getPrincipal() instanceof AuthenticatedUser)).isTrue();
            assertThat(call.getArgument(0, String.class)).isEqualTo(ROUTE);
            assertThat(call.getArgument(2, String.class)).isEqualTo(SIGNATURE);
            assertThat(call.getArgument(3, String.class)).isEqualTo(TIME);
            lastRaw = call.getArgument(1); retainedBytes.add(lastRaw.clone());
            // Mock only this existing public committed-intake contract. Real
            // HMAC/transaction tests remain GatewayWebhookIntakeTest/PostgresIT.
            return new GatewayWebhookIntake.IntakeReceipt(inbox);
        });
        when(finance.gatewayCaptures(any(), eq(school), nullable(String.class), anyString(), anyInt(), anyInt()))
                .thenAnswer(call -> { assertHuman(reader); return new GatewayCaptureQueueResponse(school, LocalDate.of(2026, 10, 14),
                        null, List.of(), 0, 25, 0, false); });
        when(finance.postReviewedGatewayCapture(any(), eq(capture), any())).thenAnswer(call -> {
            assertHuman(collector); return new FinanceService.GatewayPostingResult("POSTED", UUID.randomUUID(), "SYNTHETIC/000001");
        });
    }

    @AfterEach void cleanup() {
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TransactionSynchronizationManager.clear(); SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test void anonymousExactRawUtf8WhitespaceAndOrderingReachCommittedIntakeBeforeEmptyNoStoreAcknowledgement() throws Exception {
        byte[] wire = body();
        mvc.perform(callback().content(wire)).andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(retainedBytes).hasSize(1); assertThat(retainedBytes.getFirst()).isEqualTo(wire);
        assertThat(lastRaw).isEqualTo(new byte[wire.length]);
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void identicalCommittedRetriesAcknowledgeWithoutReturningInboxSchoolOrPaymentInformation() throws Exception {
        for (int retry = 0; retry < 3; retry++) {
            mvc.perform(callback().content(body())).andExpect(status().isNoContent()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertThat(retainedBytes).hasSize(3).allSatisfy(bytes -> assertThat(bytes).isEqualTo(body()));
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void incidentalValidRevokedAndForeignSchoolCookiesDoNotAuthenticateOrImposeMfaOnProviderIntake() throws Exception {
        when(mfa.isRequired(any())).thenReturn(true);
        for (String token : List.of(READER_TOKEN, COLLECTOR_TOKEN, "synthetic-revoked-session", "synthetic-foreign-school-session")) {
            mvc.perform(callback().cookie(new Cookie("educore_session", token)).content(body()))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void staleEduCorePrincipalAndSessionRequestAttributeAreClearedOnOnlyTheExactCallback() throws Exception {
        var stale = UsernamePasswordAuthenticationToken.authenticated(collector, null,
                List.of(new SimpleGrantedAuthority("CAPABILITY_fees.collect")));
        var result = mvc.perform(callback().content(body()).with(authentication(stale))
                        .requestAttr(SessionService.AuthenticatedSession.class.getName(), session(collector).orElseThrow()))
                .andExpect(status().isNoContent()).andExpect(content().string("")).andReturn();
        assertThat(result.getRequest().getAttribute(SessionService.AuthenticatedSession.class.getName())).isNull();
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void callbackCookiesDoNotReplaceAnyAlreadyPresentTenantOrTransactionAuthority() throws Exception {
        // The filter must not open/replace a human scope. Existing intake itself
        // denies ambient persistence; exercise the exact boundary contract here.
        doThrow(new IllegalStateException("synthetic-ambient-context-denial")).when(intake).accept(anyString(), any(), anyString(), anyString());
        UUID other = UUID.randomUUID();
        try (var ignored = TenantRequestContext.open(other, UUID.randomUUID())) {
            var before = TenantRequestContext.current();
            mvc.perform(callback().cookie(new Cookie("educore_session", COLLECTOR_TOKEN)).content(body()))
                    .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
            assertThat(TenantRequestContext.current()).isEqualTo(before);
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            mvc.perform(callback().content(body())).andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        } finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void rejectedAuthenticationConfigurationDatabaseAndCommitNeverBecomeSuccessOrExposeDiagnostics() throws Exception {
        for (String failure : List.of("synthetic-private-forged-signature", "synthetic-private-merchant-key",
                "synthetic-private-database-record", "synthetic-private-commit-payer")) {
            doThrow(new IllegalStateException(failure, new RuntimeException("synthetic-secret-cause"))).when(intake)
                    .accept(anyString(), any(), anyString(), anyString());
            mvc.perform(callback().content(body())).andExpect(status().isServiceUnavailable()).andExpect(content().string(""))
                    .andExpect(header().string("Retry-After", "60")).andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void exactCallbackNeverClearsOrSubstitutesAmbientSynchronizationOrBoundResources() throws Exception {
        doThrow(new IllegalStateException("synthetic-ambient-persistence-denial")).when(intake)
                .accept(anyString(), any(), anyString(), anyString());
        Object resourceKey = new Object(), resource = new Object();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(resourceKey, resource);
        try {
            mvc.perform(callback().cookie(new Cookie("educore_session", COLLECTOR_TOKEN)).content(body()))
                    .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(resourceKey)).isSameAs(resource);
            assertThat(TransactionSynchronizationManager.getResourceMap()).containsOnlyKeys(resourceKey);
        } finally {
            TransactionSynchronizationManager.unbindResource(resourceKey);
            TransactionSynchronizationManager.clear();
        }
        verifyNoInteractions(sessions, mfa, finance);
    }

    @Test void absentCommittedIntakeReceiptIsNeverAcknowledged() throws Exception {
        doReturn(null).when(intake).accept(anyString(), any(), anyString(), anyString());
        mvc.perform(callback().content(body())).andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        verifyNoInteractions(finance);
    }

    @Test void missingSignatureOrTimestampRejectsBeforeIntakeAndNoBrowserSuccessFieldCanReplaceThem() throws Exception {
        mvc.perform(post(CALLBACK).contentType(MediaTypeValue.JSON).header("x-webhook-timestamp", TIME).content(body()))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(post(CALLBACK).contentType(MediaTypeValue.JSON).header("x-webhook-signature", SIGNATURE).content(body()))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(post(CALLBACK).contentType(MediaTypeValue.JSON).content("{\"success\":true,\"paid\":true}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        verifyNoInteractions(intake, finance);
    }

    @Test void duplicateAndCommaJoinedHeadersAreRejectedEvenWhenValuesAgree() throws Exception {
        for (String name : List.of("x-webhook-signature", "x-webhook-timestamp")) {
            String value = name.endsWith("signature") ? SIGNATURE : TIME;
            mvc.perform(callback().header(name, value).content(body())).andExpect(status().isBadRequest()).andExpect(content().string(""));
            mvc.perform(post(CALLBACK).contentType(MediaTypeValue.JSON)
                            .header("x-webhook-signature", name.endsWith("signature") ? SIGNATURE + "," + value : SIGNATURE)
                            .header("x-webhook-timestamp", name.endsWith("timestamp") ? TIME + "," + value : TIME).content(body()))
                    .andExpect(status().isBadRequest()).andExpect(content().string(""));
        }
        verifyNoInteractions(intake, finance);
    }

    @Test void whitespaceBlankAndNoncanonicalDigestHeadersFailBeforeIntake() throws Exception {
        for (String invalid : List.of("", " ", " " + SIGNATURE, SIGNATURE + " ", "A".repeat(44),
                "A".repeat(42) + "B=", "A".repeat(43) + "-", SIGNATURE + "=")) {
            mvc.perform(post(CALLBACK).contentType(MediaTypeValue.JSON).header("x-webhook-signature", invalid)
                            .header("x-webhook-timestamp", TIME).content(body()))
                    .andExpect(status().isBadRequest()).andExpect(content().string(""));
        }
        verifyNoInteractions(intake, finance);
    }

    @Test void timestampMustBeOneCanonicalBoundedThirteenDigitValueWithoutAgeBasedRejection() throws Exception {
        for (String invalid : List.of("", " ", " " + TIME, TIME + " ", "0160000000000", "160000000000", "16000000000000",
                "+1600000000000", "160000000000a", "١٦٠٠٠٠٠٠٠٠٠٠٠")) {
            mvc.perform(post(CALLBACK).contentType(MediaTypeValue.JSON).header("x-webhook-signature", SIGNATURE)
                            .header("x-webhook-timestamp", invalid).content(body()))
                    .andExpect(status().isBadRequest()).andExpect(content().string(""));
        }
        verifyNoInteractions(intake, finance);
        mvc.perform(callback().content(body())).andExpect(status().isNoContent());
    }

    @Test void onlyUncompressedJsonWithAbsentOrUtf8CharsetIsAccepted() throws Exception {
        for (String invalid : List.of("text/plain", "application/octet-stream", "multipart/form-data; boundary=synthetic",
                "application/*", "application/problem+json", "application/json;charset=ISO-8859-1", "application/json;charset=synthetic-unknown",
                "application/json,text/plain")) {
            mvc.perform(post(CALLBACK).header("Content-Type", invalid).header("x-webhook-signature", SIGNATURE)
                            .header("x-webhook-timestamp", TIME).content(body()))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""));
        }
        for (String encoding : List.of("gzip", "br", "deflate", "", "identity,gzip")) {
            mvc.perform(callback().header("Content-Encoding", encoding).content(body()))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""));
        }
        mvc.perform(callback().header("Content-Encoding", "identity", "identity").content(body()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""));
        verifyNoInteractions(intake, finance);
        mvc.perform(callback().contentType("application/json;charset=UTF-8").header("Content-Encoding", "identity").content(body()))
                .andExpect(status().isNoContent());
    }

    @Test void missingOrDuplicateContentTypeNeverInvokesIntake() throws Exception {
        mvc.perform(post(CALLBACK).header("x-webhook-signature", SIGNATURE).header("x-webhook-timestamp", TIME).content(body()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""));
        for (var values : List.of(List.of("application/json", "application/json"),
                List.of("application/json", "application/json;charset=ISO-8859-1"))) {
            mvc.perform(callback().header("Content-Type", "application/json").content(body()).with(request -> {
                // MockMvc's special Content-Type handling collapses this builder
                // combination to one value. Prove that fixture behavior, then
                // expose genuine servlet-level duplicate values to the unchanged
                // production security chain and controller. Each read receives
                // a fresh enumeration; this assertion must not consume its data.
                assertThat(Collections.list(request.getHeaders("Content-Type"))).containsExactly("application/json");
                var spied = spy(request);
                doAnswer(ignored -> Collections.enumeration(values)).when(spied).getHeaders("Content-Type");
                doReturn(values.getFirst()).when(spied).getHeader("Content-Type");
                assertThat(Collections.list(spied.getHeaders("Content-Type"))).containsExactlyElementsOf(values);
                assertThat(spied.getHeader("Content-Type")).isEqualTo(values.getFirst());
                return spied;
            })).andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(intake, finance);
    }

    @Test void duplicateConflictingOrUnexpectedJsonParametersCannotChooseAnImplicitCharset() throws Exception {
        for (String invalid : List.of("application/json;charset=ISO-8859-1;charset=UTF-8",
                "application/json;charset=UTF-8;charset=UTF-8", "application/json;charset=UTF-8;profile=synthetic",
                "application/json;boundary=synthetic", "application/json;charset=UTF-8;", "application/json;charset=UTF8")) {
            mvc.perform(post(CALLBACK).header("Content-Type", invalid).header("x-webhook-signature", SIGNATURE)
                            .header("x-webhook-timestamp", TIME).content(body()))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(intake, finance);
        mvc.perform(callback().contentType("application/json; charset=\"UTF-8\"").content(body()))
                .andExpect(status().isNoContent());
    }

    @Test void malformedUtf8SurrogateOverlongAndUtf16BytesAreRejectedWithoutJsonAutodetection() throws Exception {
        for (byte[] invalid : List.of(new byte[] { 0 }, new byte[] { (byte) 0x80 }, new byte[] { (byte) 0xc0, (byte) 0xaf },
                new byte[] { (byte) 0xc2 }, new byte[] { (byte) 0xc2, 0x20 }, new byte[] { (byte) 0xe0, (byte) 0x9f, (byte) 0xbf },
                new byte[] { (byte) 0xe2, (byte) 0x82 }, new byte[] { (byte) 0xed, (byte) 0xa0, (byte) 0x80 },
                new byte[] { (byte) 0xf0, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf },
                new byte[] { (byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80 },
                new byte[] { (byte) 0xf5, (byte) 0x80, (byte) 0x80, (byte) 0x80 },
                new byte[] { (byte) 0xf0, (byte) 0x90, (byte) 0x80 },
                "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_16),
                "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_16BE),
                "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_16LE))) {
            mvc.perform(callback().content(invalid)).andExpect(status().isBadRequest()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store"));
            assertThat(lastRaw).isNull();
        }
        verifyNoInteractions(intake, finance);
    }

    @Test void allUtf8ScalarWidthsAndBoundaryValuesAreAuthenticatedWithoutByteNormalization() throws Exception {
        byte[] wire = ("{\"synthetic\":\"\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff"
                + "\ud800\udc00\udbff\udfff\",\"escapedNul\":\"\\u0000\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(callback().content(wire)).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(retainedBytes).hasSize(1); assertThat(retainedBytes.getFirst()).isEqualTo(wire);
        assertThat(lastRaw).isEqualTo(new byte[wire.length]); verifyNoInteractions(finance);
    }

    @Test void emptyAndKnownOversizeBodiesCannotReachIntake() throws Exception {
        mvc.perform(callback().content(new byte[0])).andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(callback().content(new byte[MAX_BYTES + 1])).andExpect(status().isPayloadTooLarge()).andExpect(content().string(""));
        verifyNoInteractions(intake, finance);
    }

    @Test void unknownLengthStreamReadsOnlyMaxPlusOneBytesAndCannotBufferTheRemainder() throws Exception {
        var stream = new CountingStream(new byte[MAX_BYTES * 2], false);
        mvc.perform(callback().with(request -> streaming(request, -1, stream)))
                .andExpect(status().isPayloadTooLarge()).andExpect(content().string(""));
        assertThat(stream.position).isEqualTo(MAX_BYTES + 1); verifyNoInteractions(intake, finance);
    }

    @Test void misleadingSmallDeclaredLengthDoesNotBypassTheActualStreamLimit() throws Exception {
        var stream = new CountingStream(new byte[MAX_BYTES * 2], false);
        mvc.perform(callback().with(request -> streaming(request, 1, stream)))
                .andExpect(status().isPayloadTooLarge()).andExpect(content().string(""));
        assertThat(stream.position).isEqualTo(MAX_BYTES + 1); verifyNoInteractions(intake, finance);
    }

    @Test void declaredOversizeIsRejectedWithoutReadingTheStream() throws Exception {
        var stream = new CountingStream(body(), false);
        mvc.perform(callback().with(request -> streaming(request, MAX_BYTES + 1L, stream)))
                .andExpect(status().isPayloadTooLarge()).andExpect(content().string(""));
        assertThat(stream.position).isZero(); verifyNoInteractions(intake, finance);
    }

    @Test void unknownLengthValidAndExactlyMaximumBodiesUseOriginalBytesAndClearTemporaryBuffer() throws Exception {
        byte[] maximumJson = (" ".repeat(MAX_BYTES - 2) + "{}").getBytes(StandardCharsets.UTF_8);
        for (byte[] wire : List.of(body(), maximumJson)) {
            var stream = new CountingStream(wire, false);
            mvc.perform(callback().with(request -> streaming(request, -1, stream))).andExpect(status().isNoContent()).andExpect(content().string(""));
            assertThat(retainedBytes.getLast()).isEqualTo(wire); assertThat(lastRaw).isEqualTo(new byte[wire.length]);
            assertThat(stream.position).isEqualTo(wire.length);
        }
        verifyNoInteractions(finance);
    }

    @Test void unknownLengthEmptyOrInterruptedBodyCannotBeAcknowledged() throws Exception {
        mvc.perform(callback().with(request -> streaming(request, -1, new CountingStream(new byte[0], false))))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(callback().with(request -> streaming(request, -1, new CountingStream(body(), true))))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""))
                .andExpect(header().string("Retry-After", "60"));
        verifyNoInteractions(intake, finance);
    }

    @Test void noncanonicalRoutesAreRejectedWithoutIntakeOrRouteEcho() throws Exception {
        for (String invalid : List.of("a".repeat(42), "a".repeat(65), "A".repeat(64), "A".repeat(42) + "B", "synthetic-private-route")) {
            mvc.perform(post(GatewayWebhookRequestPath.PREFIX + invalid).contentType(MediaTypeValue.JSON)
                            .header("x-webhook-signature", SIGNATURE).header("x-webhook-timestamp", TIME).content(body()))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        verifyNoInteractions(intake, finance);
    }

    @Test void narrowPathMatcherAcceptsOnlyCanonicalPostAndHandlesContextPathWithoutNormalization() {
        for (String key : List.of(ROUTE, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]))) {
            for (String prefix : List.of("", "/synthetic-context")) {
                var request = new MockHttpServletRequest("POST", prefix + GatewayWebhookRequestPath.PREFIX + key);
                request.setContextPath(prefix); assertThat(GatewayWebhookRequestPath.matches(request)).isTrue();
            }
        }
        for (String method : List.of("GET", "PUT", "PATCH", "DELETE", "OPTIONS", "post")) {
            assertThat(GatewayWebhookRequestPath.matches(new MockHttpServletRequest(method, CALLBACK))).isFalse();
        }
        for (String uri : List.of(CALLBACK + "/", CALLBACK + "/next", CALLBACK + ";synthetic", CALLBACK.replace("/public/", "/finance/"),
                CALLBACK.replace("webhooks/", "webhooks/%2F"), CALLBACK.replace("webhooks/", "webhooks//"),
                "/api/v1/auth/sessions", "/api/v1/auth/mfa/challenges/verify", QUEUE, GatewayWebhookRequestPath.PREFIX + "A".repeat(42) + "B")) {
            assertThat(GatewayWebhookRequestPath.matches(new MockHttpServletRequest("POST", uri))).isFalse();
        }
        assertThat(GatewayWebhookRequestPath.matches(null)).isFalse();
    }

    @Test void onlyExactCallbackSkipsCookieAuthWhileNearPublicPathsKeepExistingSessionBehavior() throws Exception {
        mvc.perform(post(GatewayWebhookRequestPath.PREFIX + "synthetic-noncanonical-route").cookie(new Cookie("educore_session", READER_TOKEN))
                        .contentType(MediaTypeValue.JSON).header("x-webhook-signature", SIGNATURE).header("x-webhook-timestamp", TIME).content(body()))
                .andExpect(status().isNotFound());
        verify(sessions).authenticate(READER_TOKEN); verify(mfa).isRequired(reader); verifyNoInteractions(intake, finance);
    }

    @Test void authenticatedFinanceStillRequiresItsOwnSessionAndActualCookieHeaderCsrfPair() throws Exception {
        mvc.perform(get(QUEUE).param("schoolId", school.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post(postingPath()).cookie(new Cookie("educore_session", COLLECTOR_TOKEN)).contentType(MediaTypeValue.JSON).content(postingBody()))
                .andExpect(status().isForbidden());
        Cookie csrf = mvc.perform(get(QUEUE).cookie(new Cookie("educore_session", READER_TOKEN)).param("schoolId", school.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");
        assertThat(csrf).isNotNull();
        mvc.perform(post(postingPath()).cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue()).contentType(MediaTypeValue.JSON).content(postingBody()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(postingPath()).cookie(new Cookie("educore_session", COLLECTOR_TOKEN), csrf).header("X-CSRF-TOKEN", "synthetic-wrong-csrf")
                        .contentType(MediaTypeValue.JSON).content(postingBody())).andExpect(status().isForbidden());
        mvc.perform(post(postingPath()).cookie(new Cookie("educore_session", COLLECTOR_TOKEN), csrf).header("X-CSRF-TOKEN", csrf.getValue())
                        .contentType(MediaTypeValue.JSON).content(postingBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED"));
        verify(sessions).authenticate(READER_TOKEN); verify(sessions).authenticate(COLLECTOR_TOKEN);
        verifyNoInteractions(intake);
    }

    @Test void financeMfaEnforcementAndUntrustedBrowserOriginAreNotRelaxedByCallbackExclusion() throws Exception {
        when(mfa.isRequired(reader)).thenReturn(true);
        mvc.perform(get(QUEUE).cookie(new Cookie("educore_session", READER_TOKEN)).param("schoolId", school.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_VERIFICATION_REQUIRED"));
        verify(mfa).recordDenied(reader); verifyNoInteractions(finance);
        mvc.perform(callback().header("Origin", "https://untrusted.example.invalid").content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(intake);
    }

    private MockHttpServletRequestBuilder callback() {
        return post(CALLBACK).contentType(MediaTypeValue.JSON).header("x-webhook-signature", SIGNATURE).header("x-webhook-timestamp", TIME);
    }
    private static MockHttpServletRequest streaming(MockHttpServletRequest request, long declaredLength, ServletInputStream stream) {
        var spied = spy(request); doReturn(declaredLength).when(spied).getContentLengthLong(); doReturn(stream).when(spied).getInputStream(); return spied;
    }
    private static byte[] body() {
        return "{  \"type\" : \"PAYMENT_SUCCESS_WEBHOOK\", \"synthetic\" : \"परीक्षण\" }\r\n".getBytes(StandardCharsets.UTF_8);
    }
    private String postingPath() { return QUEUE + "/" + capture + "/posting"; }
    private String postingBody() { return "{\"schoolId\":\"" + school + "\",\"openDate\":\"2026-10-14\",\"reason\":\"Synthetic authorized review\"}"; }
    private AuthenticatedUser actor(String name, String capability) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-callback", null, name,
                false, organisation, Set.of(UUID.randomUUID()), Set.of(capability), List.of());
    }
    private Optional<SessionService.AuthenticatedSession> session(AuthenticatedUser user) {
        return Optional.of(new SessionService.AuthenticatedSession(user, Instant.now().plusSeconds(60)));
    }
    private void assertHuman(AuthenticatedUser user) {
        assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(user.userId(), organisation));
    }
    private static final class MediaTypeValue { private static final String JSON = "application/json"; }
    private static final class CountingStream extends ServletInputStream {
        private final byte[] bytes; private final boolean fail; private int position;
        private CountingStream(byte[] bytes, boolean fail) { this.bytes = bytes; this.fail = fail; }
        @Override public int read() throws IOException { if (fail) throw new IOException("synthetic-private-stream-failure"); return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]); }
        @Override public int read(byte[] destination, int offset, int length) throws IOException {
            if (fail) throw new IOException("synthetic-private-stream-failure");
            if (position == bytes.length) return -1;
            int count = Math.min(length, bytes.length - position); System.arraycopy(bytes, position, destination, offset, count); position += count; return count;
        }
        @Override public boolean isFinished() { return position == bytes.length; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Test stream is synchronous"); }
    }

    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config {
        @Bean GatewayWebhookIntake intake() { return mock(GatewayWebhookIntake.class); }
        @Bean GatewayWebhookController callback(GatewayWebhookIntake intake) { return new GatewayWebhookController(intake); }
        @Bean FinanceService finance() { return mock(FinanceService.class); }
        @Bean FinanceController financeController(FinanceService finance) { return new FinanceController(finance); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean GlobalExceptionHandler advice(Clock clock) { return new GlobalExceptionHandler(clock); }
        @Bean ApiSecurityErrorWriter errors(Clock clock) { return new ApiSecurityErrorWriter(new ObjectMapper(), clock); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean PrivilegedMfaPolicy mfa() { return mock(PrivilegedMfaPolicy.class); }
        @Bean ApplicationProperties properties() {
            var properties = mock(ApplicationProperties.class); var security = mock(ApplicationProperties.Security.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000")); when(properties.security()).thenReturn(security);
            when(security.sessionCookieName()).thenReturn("educore_session"); return properties;
        }
        @Bean SessionAuthenticationFilter filter(SessionService sessions, ApplicationProperties properties, PrivilegedMfaPolicy mfa, ApiSecurityErrorWriter errors) {
            return new SessionAuthenticationFilter(sessions, properties, mfa, errors);
        }
    }
}
