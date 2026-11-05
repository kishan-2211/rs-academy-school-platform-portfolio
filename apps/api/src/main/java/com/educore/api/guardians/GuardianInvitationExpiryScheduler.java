package com.educore.api.guardians;

import com.educore.api.tenancy.TenantRequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.guardian-invitations.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class GuardianInvitationExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(GuardianInvitationExpiryScheduler.class);
    private static final int MAX_INVITATIONS_PER_ORGANISATION = 25;
    private final JdbcTemplate jdbc;
    private final GuardianPortalInvitationService invitations;

    public GuardianInvitationExpiryScheduler(JdbcTemplate jdbc, GuardianPortalInvitationService invitations) {
        this.jdbc = jdbc;
        this.invitations = invitations;
    }

    @Scheduled(fixedDelayString = "${app.guardian-invitations.expiry.poll-delay-ms:60000}",
            initialDelayString = "${app.guardian-invitations.expiry.initial-delay-ms:30000}")
    public void expireDueInvitations() {
        var organisations = jdbc.query("SELECT id FROM organisations WHERE status='ACTIVE' ORDER BY id",
                (result, row) -> result.getObject("id", UUID.class));
        for (UUID organisationId : organisations) {
            try (var ignored = TenantRequestContext.open(null, organisationId)) {
                for (int index = 0; index < MAX_INVITATIONS_PER_ORGANISATION; index++) {
                    if (invitations.expireNext(organisationId) == null) break;
                }
            } catch (RuntimeException exception) {
                // Do not log exception text: driver/provider errors can contain protected values.
                // The per-invitation transaction rolls back; a later poll retries the unchanged row.
                log.warn("Guardian invitation expiry for organisation {} failed; retry on the next poll", organisationId);
            }
        }
    }
}
