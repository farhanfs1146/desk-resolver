package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.Role;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserRole;
import com.forward.desk_resolver.repository.RoleRepository;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates one administrator on first start, so a freshly migrated database is actually usable.
 *
 * <p>Without this there is a deadlock: creating a user requires {@code USER_MANAGE}, which requires an
 * account, which requires someone to create it. Enabling authentication on a database whose users all
 * have a {@code NULL} password hash would otherwise lock everyone out permanently.
 *
 * <p>Three guards keep this from becoming a backdoor:
 *
 * <ul>
 *   <li><strong>Opt-in.</strong> Nothing happens unless both email and password are configured. There
 *       is no built-in default account and no default password.
 *   <li><strong>Once only.</strong> It runs only when no user holds the {@code ADMIN} role. It will not
 *       recreate, reset or re-enable an administrator that already exists, so it cannot be used to
 *       overwrite a password by restarting with different configuration.
 *   <li><strong>Never logs the password.</strong> Only the email and id are logged.
 * </ul>
 *
 * <p><strong>Phase 7.</strong> "Holds the {@code ADMIN} role" is now a row in {@code auth.user_roles}
 * rather than a column on the user, so the existence check counts grants and the account is created in
 * two steps - the user, then the grant. The role itself is looked up by code rather than assumed:
 * roles are data now, and a deployment whose {@code auth.roles} is missing {@code ADMIN} should say so
 * rather than create an administrator with no capabilities at all.
 *
 * <p>The grant it writes carries no {@code granted_by}. Nobody granted it; the deployment's
 * configuration did, and inventing a grantor id would make the audit column lie.
 *
 * <p>Configure it through the environment, not a committed file - see the README.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;

    public BootstrapAdminInitializer(UserRepository userRepository,
                                     RoleRepository roleRepository,
                                     UserRoleRepository userRoleRepository,
                                     PasswordEncoder passwordEncoder,
                                     SecurityProperties properties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SecurityProperties.BootstrapAdmin config = properties.bootstrapAdmin();

        if (!config.isConfigured()) {
            if (administratorCount() == 0) {
                log.warn("""
                        No administrator account exists and app.security.bootstrap-admin is not \
                        configured, so none was created. Nobody can manage users or applications until \
                        an administrator exists. Set app.security.bootstrap-admin.email and \
                        .password (for example from the environment) and restart.""");
            }
            return;
        }

        if (administratorCount() > 0) {
            log.info("An administrator already exists; bootstrap administrator not created");
            return;
        }

        if (userRepository.existsByEmail(config.email())) {
            log.warn("Bootstrap administrator not created: a user already exists with email {}",
                    config.email());
            return;
        }

        // Looked up rather than assumed. If V17's seed is absent this is a misconfigured database, and
        // creating a roleless "administrator" would be worse than refusing: it would look like success.
        Role adminRole = roleRepository.findByCode(SystemRoles.ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot create the bootstrap administrator: auth.roles has no row with code '"
                                + SystemRoles.ADMIN + "'. The auth schema seed (V17) has not been applied."));

        User admin = new User();
        admin.setEmail(config.email());
        admin.setFullName(config.fullNameOrDefault());
        admin.setActive(true);
        // employee_code is NOT NULL and UNIQUE but carries no meaning for a bootstrap account.
        admin.setEmployeeCode(nextFreeEmployeeCode());
        admin.setPasswordHash(passwordEncoder.encode(config.password()));

        User saved = userRepository.save(admin);
        userRoleRepository.save(UserRole.of(saved.getId(), adminRole.getId(), null));

        log.info("Created bootstrap administrator id={} email={} with role {}. Change this password "
                        + "after first sign-in via PATCH /api/users/me/password, then remove the "
                        + "configured value.",
                saved.getId(), saved.getEmail(), SystemRoles.ADMIN);
    }

    private long administratorCount() {
        return userRoleRepository.countByRoleCode(SystemRoles.ADMIN);
    }

    private long nextFreeEmployeeCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            long candidate = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
            if (!userRepository.existsByEmployeeCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate an employee code for the bootstrap admin");
    }
}
