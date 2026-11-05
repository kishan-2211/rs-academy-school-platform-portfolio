package com.educore.api.people;

import com.educore.api.common.api.ApiException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No HTTP entry point and no account/role provisioning: identity owns those decisions. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StaffAccountLinkService implements PeopleStaffAccountBridge {
    private static final String ACCEPTANCE_REASON = "Staff invitation accepted";
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final Validator validator;

    public StaffAccountLinkService(NamedParameterJdbcTemplate jdbc, Clock clock, Validator validator) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.validator = validator;
    }

    @Override
    public StaffAccountCandidate lockCandidate(UUID organisationId, UUID schoolId, UUID staffId) {
        requireScope(organisationId);
        if (schoolId == null || staffId == null) throw unavailable();
        List<StaffAccountCandidate> candidates = jdbc.query("""
                SELECT staff.id,staff.organisation_id,staff.school_id,school.display_name AS school_name,
                  staff.display_name,staff.work_email_normalized,staff.primary_language,staff.row_version,
                  staff.joined_on,staff.ended_on,staff.status,staff.user_account_id
                FROM staff_profiles staff
                JOIN schools school ON school.id=staff.school_id AND school.organisation_id=staff.organisation_id
                JOIN organisations organisation ON organisation.id=staff.organisation_id
                WHERE staff.id=:staffId AND staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                  AND organisation.status='ACTIVE' AND school.status='ACTIVE'
                FOR UPDATE OF staff
                """, scope(organisationId, schoolId).addValue("staffId", staffId), (row, index) ->
                new StaffAccountCandidate(row.getObject("id", UUID.class), row.getObject("organisation_id", UUID.class),
                        row.getObject("school_id", UUID.class), row.getString("school_name"), row.getString("display_name"),
                        row.getString("work_email_normalized"), language(row.getString("primary_language")),
                        row.getLong("row_version"), row.getObject("joined_on", LocalDate.class),
                        row.getObject("ended_on", LocalDate.class), row.getString("status"),
                        row.getObject("user_account_id", UUID.class)));
        if (candidates.size() != 1) throw unavailable();
        StaffAccountCandidate candidate = candidates.getFirst();
        requireEligible(candidate);
        return candidate;
    }

    @Override
    public void linkAcceptedAccount(StaffAccountCandidate expected, UUID userId,
            UUID actorMembershipId, String reason) {
        if (expected == null || userId == null || actorMembershipId == null || !ACCEPTANCE_REASON.equals(reason)) {
            throw new IllegalArgumentException("An accepted staff account, its own membership and canonical acceptance reason are required");
        }
        requireEligible(expected);
        StaffAccountCandidate current = lockCandidate(expected.organisationId(), expected.schoolId(), expected.id());
        // Includes source names/contact, language, scope, employment dates/status and exact row version.
        if (!current.equals(expected)) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_ACCOUNT_CANDIDATE_CHANGED",
                    "The staff profile changed. Request a new invitation.");
        }
        // A membership may have been created earlier in this same transaction.
        // PostgreSQL CURRENT_TIMESTAMP is the transaction start, before that grant.
        Timestamp now = Timestamp.from(clock.instant());
        MapSqlParameterSource parameters = scope(current.organisationId(), current.schoolId())
                .addValue("staffId", current.id()).addValue("userId", userId)
                .addValue("actorMembershipId", actorMembershipId).addValue("email", current.email())
                .addValue("version", current.version()).addValue("today", LocalDate.now(clock))
                .addValue("now", now);
        Boolean matchingAccount = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM memberships membership
                  JOIN user_accounts account ON account.id=membership.user_id
                  WHERE membership.id=:actorMembershipId AND membership.user_id=:userId
                    AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                    AND membership.status='ACTIVE' AND membership.valid_from<=:now
                    AND (membership.valid_until IS NULL OR membership.valid_until>:now)
                    AND account.status='ACTIVE' AND account.email_normalized=:email
                    AND EXISTS(SELECT 1 FROM membership_roles mapping JOIN roles role ON role.id=mapping.role_id
                      WHERE mapping.membership_id=membership.id AND role.code<>'parent_guardian'
                        AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)))
                """, parameters, Boolean.class);
        if (!Boolean.TRUE.equals(matchingAccount)) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_ACCOUNT_LINK_INVALID",
                    "The accepted account must have its own active same-school staff membership.");
        }
        int changed = jdbc.update("""
                UPDATE staff_profiles SET user_account_id=:userId,updated_at=:now,row_version=row_version+1
                WHERE id=:staffId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version AND work_email_normalized=:email AND status='ACTIVE'
                  AND joined_on<=:today AND ended_on IS NULL AND user_account_id IS NULL
                """, parameters.addValue("now", now));
        if (changed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_ACCOUNT_CANDIDATE_CHANGED",
                    "The staff profile changed or already has an account. Request a new invitation.");
        }
        // Preserve V018's account/membership guard and append-only HR history; no actor impersonation.
        jdbc.update("""
                INSERT INTO staff_profile_history(id,organisation_id,school_id,staff_profile_id,event_type,
                  subject_id,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:historyId,:organisationId,:schoolId,:staffId,'PROFILE_UPDATED',:userId,
                  'ACTIVE','ACTIVE',:reason,:actorMembershipId,:now)
                """, parameters.addValue("historyId", UUID.randomUUID()).addValue("reason", ACCEPTANCE_REASON));
    }

    private void requireScope(UUID organisationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("The staff account bridge requires an existing writable transaction");
        }
        if (organisationId == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT current_app_organisation_id()=:organisationId",
                new MapSqlParameterSource("organisationId", organisationId), Boolean.class))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STAFF_ACCOUNT_SCOPE_DENIED",
                    "Staff account access is unavailable in this organisation context.");
        }
    }

    private void requireEligible(StaffAccountCandidate candidate) {
        if (candidate.id() == null || candidate.organisationId() == null || candidate.schoolId() == null
                || candidate.version() < 0 || !"ACTIVE".equals(candidate.status()) || candidate.userAccountId() != null
                || candidate.joinedOn() == null || candidate.joinedOn().isAfter(LocalDate.now(clock)) || candidate.endedOn() != null
                || !validator.validate(new CandidateContact(candidate.email(), candidate.displayName(), candidate.language())).isEmpty()
                || candidate.email().chars().anyMatch(Character::isISOControl)
                || candidate.displayName().chars().anyMatch(Character::isISOControl)
                || !candidate.email().equals(candidate.email().strip().toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_ACCOUNT_CANDIDATE_UNAVAILABLE",
                    "An active, joined, unlinked staff profile with a valid work email is required.");
        }
    }

    private static MapSqlParameterSource scope(UUID organisationId, UUID schoolId) {
        return new MapSqlParameterSource("organisationId", organisationId).addValue("schoolId", schoolId);
    }

    private static String language(String language) {
        return "EN".equals(language) ? "en" : "HI".equals(language) ? "hi" : language;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "STAFF_ACCOUNT_CANDIDATE_NOT_FOUND",
                "The staff profile is unavailable for account setup.");
    }

    private record CandidateContact(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String displayName, @NotBlank @Pattern(regexp = "en|hi") String language) {}
}
