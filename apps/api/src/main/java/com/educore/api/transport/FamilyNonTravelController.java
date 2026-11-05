package com.educore.api.transport;

import static com.educore.api.transport.TransportNonTravelModels.*;
import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/family/children/{learnerId}/transport/non-travel")
public class FamilyNonTravelController {
    private final TransportNonTravelService service;
    public FamilyNonTravelController(TransportNonTravelService service){this.service=service;}
    @GetMapping @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyDay day(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID learnerId){
        return service.familyDay(user,learnerId);
    }
    @PostMapping @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    Change change(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID learnerId,
            @Valid @RequestBody ChangeRequest request){return service.change(user,learnerId,request);}
}
