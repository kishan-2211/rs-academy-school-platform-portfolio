package com.educore.api.identity;

import com.educore.api.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Component
public class DevelopmentBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentBootstrap.class);
    private final ApplicationProperties properties;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final JdbcTemplate jdbc;

    private static final UUID ORGANISATION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SCHOOL_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID CAMPUS_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID SCHOOL_ADMIN_ROLE_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    public DevelopmentBootstrap(
            ApplicationProperties properties,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            Environment environment,
            JdbcTemplate jdbc) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ApplicationProperties.Bootstrap bootstrap = properties.bootstrap();
        if (!bootstrap.enabled()) return;
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("APP_BOOTSTRAP_ENABLED must be false in production");
        }
        if (bootstrap.adminEmail() == null || bootstrap.adminPassword() == null
                || bootstrap.adminPassword().length() < 12) {
            throw new IllegalStateException("Development bootstrap credentials are missing or too weak");
        }
        String email = UserAccount.normalizeEmail(bootstrap.adminEmail());
        UserAccount user = users.findByEmailNormalized(email).orElseGet(() -> {
            UserAccount created = users.saveAndFlush(UserAccount.active(
                    email,
                    "Local Administrator",
                    passwordEncoder.encode(bootstrap.adminPassword())));
            log.warn("Created the local development administrator account for {}", email);
            return created;
        });
        ensureSchoolAdministratorMembership(user);
    }

    private void ensureSchoolAdministratorMembership(UserAccount user) {
        UUID membershipId = jdbc.query(
                """
                SELECT id FROM memberships
                WHERE user_id = ? AND organisation_id = ? AND school_id = ? AND campus_id = ?
                """,
                result -> result.next() ? result.getObject("id", UUID.class) : null,
                user.getId(),
                ORGANISATION_ID,
                SCHOOL_ID,
                CAMPUS_ID);
        if (membershipId == null) {
            membershipId = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.update(
                    """
                    INSERT INTO memberships (
                        id, user_id, organisation_id, school_id, campus_id, status,
                        valid_from, created_at, updated_at, row_version
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
                    """,
                    membershipId,
                    user.getId(),
                    ORGANISATION_ID,
                    SCHOOL_ID,
                    CAMPUS_ID,
                    now,
                    now,
                    now);
        }
        jdbc.update(
                """
                INSERT INTO membership_roles (membership_id, role_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
                membershipId,
                SCHOOL_ADMIN_ROLE_ID);
    }
}
