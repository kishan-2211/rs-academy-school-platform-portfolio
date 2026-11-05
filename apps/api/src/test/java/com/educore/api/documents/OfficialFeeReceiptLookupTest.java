package com.educore.api.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.finance.FinanceReceiptDocumentBridge;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.ObjectMapper;

class OfficialFeeReceiptLookupTest {
    @Test
    void rejectsInvalidPageOrMissingExactObjectBeforeReadingAnyEvidence() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var service = service(jdbc);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.feeReceipts(null, id, id, -1)).hasMessageContaining("valid receipt page");
        assertThatThrownBy(() -> service.feeReceipts(null, id, id, 10001)).hasMessageContaining("valid receipt page");
        assertThatThrownBy(() -> service.feeReceipts(null, null, id, 0)).hasMessageContaining("school and payment");
        assertThatThrownBy(() -> service.feeReceipts(null, id, null, 0)).hasMessageContaining("school and payment");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsAbsentBackgroundOrMismatchedActualIdentityBeforeDatabaseAccess() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var service = service(jdbc);
        var user = user();
        UUID school = UUID.randomUUID(), payment = UUID.randomUUID();
        assertThatThrownBy(() -> service.feeReceipts(user, school, payment, 0)).hasMessageContaining("unavailable for this school");
        try (var ignored = TenantRequestContext.open(null, user.activeOrganisationId())) {
            assertThatThrownBy(() -> service.feeReceipts(user, school, payment, 0)).hasMessageContaining("unavailable for this school");
        }
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), user.activeOrganisationId())) {
            assertThatThrownBy(() -> service.feeReceipts(user, school, payment, 0)).hasMessageContaining("unavailable for this school");
        }
        try (var ignored = TenantRequestContext.open(user.userId(), UUID.randomUUID())) {
            assertThatThrownBy(() -> service.feeReceipts(user, school, payment, 0)).hasMessageContaining("unavailable for this school");
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void controllerRequiresBothReadCapabilitiesAndReturnsExactPrivatePage() throws Exception {
        var method = OfficialDocumentController.class.getDeclaredMethod("feeReceipts", AuthenticatedUser.class,
                UUID.class, UUID.class, int.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/fee-receipts/{paymentId}");
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(
                "hasAuthority('CAPABILITY_documents.read') and hasAuthority('CAPABILITY_fees.read')");
        assertThat(OfficialDocumentService.class.getMethod("feeReceipts", AuthenticatedUser.class, UUID.class,
                UUID.class, int.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
        var service = mock(OfficialDocumentService.class);
        var user = user();
        UUID school = UUID.randomUUID(), payment = UUID.randomUUID();
        var page = new OfficialDocumentSnapshot.FeeReceiptPage(school, payment, "SYN/0001", "POSTED", false,
                3, 25, false, List.of(), List.of());
        when(service.feeReceipts(user, school, payment, 3)).thenReturn(page);
        var response = new OfficialDocumentController(service).feeReceipts(user, payment, school, 3);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getHeaders().getVary()).contains("Cookie");
        assertThat(response.getBody()).isSameAs(page);
        verify(service).feeReceipts(user, school, payment, 3);
        verifyNoMoreInteractions(service);
    }

    @Test
    void exactPageIsImmutableAndHasNoSnapshotStorageOrFinancialVerificationFields() {
        var templates = new ArrayList<OfficialDocumentSnapshot.TemplateItem>();
        var documents = new ArrayList<OfficialDocumentSnapshot.DocumentItem>();
        var page = new OfficialDocumentSnapshot.FeeReceiptPage(UUID.randomUUID(), UUID.randomUUID(), "SYN/0001",
                "REVERSED", false, 0, 25, false, templates, documents);
        templates.add(mock(OfficialDocumentSnapshot.TemplateItem.class));
        documents.add(mock(OfficialDocumentSnapshot.DocumentItem.class));
        assertThat(page.templates()).isEmpty();
        assertThat(page.documents()).isEmpty();
        assertThatThrownBy(() -> page.templates().add(templates.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ObjectMapper().valueToTree(page).propertyNames()).containsExactlyInAnyOrder(
                "schoolId", "paymentId", "receiptNumber", "sourceStatus", "canIssue", "page", "pageSize", "hasMore", "templates", "documents");
    }

    private static OfficialDocumentService service(NamedParameterJdbcTemplate jdbc) {
        var properties = mock(ApplicationProperties.class);
        when(properties.publicUrl()).thenReturn("https://school.example.test");
        return new OfficialDocumentService(jdbc, mock(SecurityAuditService.class), Clock.systemUTC(), new ObjectMapper(),
                mock(OfficialDocumentRenderer.class), mock(OfficialDocumentObjectStore.class),
                mock(FinanceReceiptDocumentBridge.class), properties);
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic@example.test", null,
                "Synthetic receipt reader", true, UUID.randomUUID(), Set.of(UUID.randomUUID()),
                Set.of("documents.read", "fees.read"), List.of());
    }
}
