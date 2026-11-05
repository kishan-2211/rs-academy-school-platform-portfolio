package com.educore.api.transport;

import static com.educore.api.transport.TransportNonTravelModels.*;
import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transport/non-travel")
public class TransportNonTravelController {
    private final TransportNonTravelService service;
    public TransportNonTravelController(TransportNonTravelService service){this.service=service;}
    @GetMapping("/policies") @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    PolicyWorkspace policies(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(required=false) UUID schoolId){
        return service.policies(user,schoolId);
    }
    @PutMapping("/policies") @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    Policy policy(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody PolicyRequest request){
        return service.savePolicy(user,request);
    }
}
