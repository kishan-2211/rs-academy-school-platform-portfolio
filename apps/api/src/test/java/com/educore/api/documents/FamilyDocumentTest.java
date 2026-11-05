package com.educore.api.documents;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;

class FamilyDocumentTest {
    @Test
    void rejectsMissingAndMismatchedContextBeforeDatabaseOrStorageAccess() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var store=mock(OfficialDocumentObjectStore.class);
        var audit=mock(SecurityAuditService.class);
        var service=new FamilyDocumentService(jdbc,store,audit,Clock.systemUTC());
        var user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,
                "Synthetic",true,UUID.randomUUID(),Set.of(UUID.randomUUID()),Set.of("portal.guardian"),List.of());
        UUID learner=UUID.randomUUID();
        assertThatThrownBy(()->service.list(user,learner,0)).hasMessageContaining("not available");
        try(var ignored=TenantRequestContext.open(user.userId(),UUID.randomUUID())) {
            assertThatThrownBy(()->service.download(user,learner,UUID.randomUUID())).hasMessageContaining("not available");
        }
        assertThatThrownBy(()->service.list(user,learner,Integer.MAX_VALUE)).hasMessageContaining("valid document page");
        verifyNoInteractions(jdbc,store,audit);
    }

    @Test
    void controllerRequiresFamilyCapabilityAndReturnsPrivateAttachmentWithoutStorageAddress() throws Exception {
        assertThat(FamilyDocumentService.class.getMethod("download",AuthenticatedUser.class,UUID.class,UUID.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isFalse();
        assertThat(OfficialDocumentService.class.getMethod("download",AuthenticatedUser.class,UUID.class,UUID.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isFalse();
        assertThat(FamilyDocumentController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('CAPABILITY_portal.guardian')");
        var service=mock(FamilyDocumentService.class);
        UUID learner=UUID.randomUUID(),document=UUID.randomUUID();
        when(service.download(null,learner,document)).thenReturn(new OfficialDocumentDownload("school-document.pdf",3,
                output->{try { output.write(new byte[]{1,2,3}); } catch(java.io.IOException e) {throw new RuntimeException(e);}}));
        var response=new FamilyDocumentController(service).download(null,learner,document);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store","private");
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("attachment");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getVary()).contains("Cookie");
        var output=new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).containsExactly(1,2,3);
        var list=new FamilyDocumentController(service).list(null,learner,0,null);
        assertThat(list.getHeaders().getCacheControl()).contains("no-store","private");
    }

    @Test
    void controllerPassesOptionalExactPaymentFilterWithoutChangingFamilyResponseShape() {
        var service=mock(FamilyDocumentService.class);
        UUID learner=UUID.randomUUID(),payment=UUID.randomUUID();
        var page=new FamilyDocumentService.Page(learner,2,25,false,List.of());
        when(service.list(null,learner,2,payment)).thenReturn(page);
        var response=new FamilyDocumentController(service).list(null,learner,2,payment);
        assertThat(response.getBody()).isSameAs(page);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store","private");
        assertThat(response.getHeaders().getVary()).contains("Cookie");
        assertThat(new tools.jackson.databind.ObjectMapper().valueToTree(page).propertyNames())
                .containsExactlyInAnyOrder("learnerId","page","pageSize","hasMore","documents");
        verify(service).list(null,learner,2,payment);
        verifyNoMoreInteractions(service);
    }
}
