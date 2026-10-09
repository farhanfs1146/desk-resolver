package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first administrator: the only way into a freshly migrated database.
 *
 * <p><strong>Why this suite exists.</strong> {@code AbstractPostgresIT} blanks
 * {@code app.security.bootstrap-admin} deliberately, so that the initializer cannot insert a user the
 * other tests did not ask for - with the side effect that the one path every new deployment depends on
 * was never exercised. "Nobody can log in yet" is the first thing anyone meets, and it was the thing
 * with no test.
 *
 * <p>Rather than stand up a second application context with different properties, the initializer is
 * constructed here with a hand-built configuration and invoked directly. That keeps the shared
 * container and context, and it tests the real class - including its guards, which are what stop it
 * being a backdoor.
 */
class BootstrapAdminIT extends AbstractPostgresIT {

    private static final String EMAIL = "bootstrap.admin@example.test";
    private static final String BOOTSTRAP_PASSWORD = "bootstrap-password-1";

    @Autowired
    private PasswordEncoder encoder;

    private BootstrapAdminInitializer initializer(String email, String password) {
        SecurityProperties properties = new SecurityProperties(
                new SecurityProperties.Jwt("test-signing-key-at-least-32-bytes", "desk-resolver",
                        Duration.ofMinutes(30)),
                null,
                new SecurityProperties.BootstrapAdmin(email, password, "Bootstrap Administrator"),
                null,
                null,
                true);
        return new BootstrapAdminInitializer(
                userRepository, roleRepository, userRoleRepository, encoder, properties);
    }

    @Test
    @DisplayName("on an empty database it creates an ADMIN who can log in and manage users")
    void createsAnAdministratorThatCanActuallyBeUsed() throws Exception {
        assertThat(userRepository.count()).isZero();

        initializer(EMAIL, BOOTSTRAP_PASSWORD).run(null);

        List<User> users = userRepository.findAll();
        assertThat(users).singleElement().satisfies(admin -> {
            assertThat(admin.getEmail()).isEqualTo(EMAIL);
            assertThat(admin.getActive()).isTrue();
            // NOT NULL and UNIQUE, so it has to be populated even though it means nothing here.
            assertThat(admin.getEmployeeCode()).isNotNull();
            assertThat(userRoleRepository.findRoleCodesByUserId(admin.getId()))
                    .containsExactly("ADMIN");
        });

        // The point of the whole exercise: the account works end to end.
        var login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}"""
                                .formatted(EMAIL, BOOTSTRAP_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.permissions.length()").value(9))
                .andReturn();

        String token = read(login, "$.accessToken");

        // USER_MANAGE through the token, which is what breaks the chicken-and-egg deadlock.
        mockMvc.perform(authenticated(post("/api/users"), token)
                        .content("""
                                {"employeeCode":90001,"fullName":"First Real User",
                                 "email":"first@example.test","roles":["IT_SUPPORT"],
                                 "active":true,"password":"initial-password-1"}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(authenticated(get("/api/roles"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @DisplayName("it will not touch a database that already has an administrator")
    void doesNothingWhenAnAdministratorExists() {
        User existing = givenUser("ADMIN");

        initializer(EMAIL, BOOTSTRAP_PASSWORD).run(null);

        // No second account, and crucially no password reset on the existing one: restarting with
        // different configuration is not a way back into somebody else's administrator account.
        assertThat(userRepository.findAll()).singleElement()
                .satisfies(user -> assertThat(user.getId()).isEqualTo(existing.getId()));
    }

    @Test
    @DisplayName("unconfigured means nothing happens - there is no default account or password")
    void doesNothingWhenUnconfigured() {
        initializer(null, null).run(null);
        initializer("", "").run(null);
        initializer(EMAIL, null).run(null);

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("it refuses rather than colliding when the email is already taken")
    void refusesWhenTheEmailBelongsToSomebodyElse() {
        User existing = givenUser("EMPLOYEE");

        initializer(existing.getEmail(), BOOTSTRAP_PASSWORD).run(null);

        // Still one user, and still an EMPLOYEE: the existing account was not promoted.
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRoleRepository.findRoleCodesByUserId(existing.getId()))
                .containsExactly("EMPLOYEE");
    }

    /**
     * The grant carries no {@code granted_by}, and that is correct rather than a missing value.
     *
     * <p>No person granted it; the deployment's configuration did. Writing an id there - the new
     * account's own, say - would make the audit column assert something untrue about who handed out
     * administrator rights.
     */
    @Test
    @DisplayName("the bootstrap grant records no grantor, because there was none")
    void theBootstrapGrantHasNoGrantor() {
        initializer(EMAIL, BOOTSTRAP_PASSWORD).run(null);

        assertThat(userRoleRepository.findAll()).singleElement()
                .satisfies(grant -> {
                    assertThat(grant.getGrantedBy()).isNull();
                    assertThat(grant.getGrantedAt()).isNotNull();
                });
    }
}
