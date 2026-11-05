package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PushOperationsRequestTest {
    @Test
    void onlyAcceptsScopedVersionedStateDecisionsWithReasons() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new PushOperationsController.ProviderChange(
                    UUID.randomUUID(),"ACTIVE",null,"Synthetic school approval"))).isEmpty();
            assertThat(factory.getValidator().validate(new PushOperationsController.ProviderChange(
                    UUID.randomUUID(),"SUSPENDED",2L,"Synthetic suspension"))).isEmpty();
            assertThat(factory.getValidator().validate(new PushOperationsController.ProviderChange(null,"OTHER",-1L,"")))
                    .extracting(v->v.getPropertyPath().toString()).contains("schoolId","status","expectedVersion","reason");
        }
    }

    @Test
    void summariesArePrivateNoStoreAndDoNotContainCapabilityMaterial() {
        var operations=mock(PushOperationsService.class);
        UUID school=UUID.randomUUID();
        var summary=new PushOperationsResponse(school,true,false,false,"NOT_CONFIGURED",null,true,Instant.EPOCH,List.of(),List.of(),List.of());
        when(operations.snapshot(null,school)).thenReturn(summary);
        var response=new PushOperationsController(operations).snapshot(null,school);
        assertThat(response.getHeaders().getCacheControl()).contains("private","no-store");
        assertThat(response.getBody()).isSameAs(summary);
        assertThat(java.util.Arrays.stream(PushOperationsResponse.class.getRecordComponents()).map(c->c.getName()).toList())
                .doesNotContain("endpoint","p256dh","auth","credentialSecretReference","privateKey","recipientId");
    }
}
