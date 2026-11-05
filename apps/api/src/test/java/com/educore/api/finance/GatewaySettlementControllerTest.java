package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.educore.api.identity.AuthenticatedUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class GatewaySettlementControllerTest {
    @Test void allEntrypointsArePrivateAndUseBothReadAuthoritiesWithSeparateSyncAuthority()throws Exception{
        assertThat(GatewaySettlementController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/v1/finance/gateway/settlements");
        for(var method:GatewaySettlementController.class.getDeclaredMethods())if(Set.of("queue","submit","report").contains(method.getName())){
            String expression=method.getAnnotation(PreAuthorize.class).value();
            assertThat(expression).contains("CAPABILITY_fees.read","CAPABILITY_accounting.read");
            if(method.getName().equals("submit"))assertThat(expression).contains("CAPABILITY_fees.close.propose");
            else assertThat(expression).doesNotContain("fees.close.propose");
        }
    }
    @Test void queueResponseRetainsPrivateNoStoreHeadersAndRealActor(){
        var service=mock(GatewaySettlementService.class);var controller=new GatewaySettlementController(service);
        UUID school=UUID.randomUUID();var user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic",false,UUID.randomUUID(),Set.of(UUID.randomUUID()),Set.of(),List.of());
        var result=new GatewaySettlementModels.Queue(school,"Asia/Kolkata",false,false,"ALL","ALL","",0,25,0,false,List.of());
        when(service.queue(user,school,"ALL","ALL",null,0,25)).thenReturn(result);
        var response=controller.queue(user,school,"ALL","ALL",null,0,25);
        assertThat(response.getBody()).isSameAs(result);assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getHeaders().getFirst("Vary")).isEqualTo("Cookie");assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        verify(service).queue(user,school,"ALL","ALL",null,0,25);verifyNoMoreInteractions(service);
    }
}
