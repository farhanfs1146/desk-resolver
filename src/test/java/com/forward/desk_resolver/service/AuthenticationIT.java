package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication, throttling and password change.
 *
 * <p>The central property under test is <strong>uniformity</strong>: an unknown email, a wrong password,
 * an account with no password set and a deactivated account must be indistinguishable to the caller.
 * Any difference between them turns the login endpoint into an account-enumeration oracle, and the
 * difference would not look like a bug in any single response - only in the set of them, which is why it
 * is asserted as a set.
 */
class AuthenticationIT extends AbstractPostgresIT {

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andReturn();
    }

    // ---------------------------------------------------------------- success

    @Test
    @DisplayName("valid credentials return a usable token and the caller's own details")
    void successfulLogin() throws Exception {
        User user = givenUser("IT_SUPPORT");

        MvcResult result = login(user.getEmail(), PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD).doesNotContain("passwordHash").doesNotContain("$2a$");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.fullName").value(user.getFullName()))
                .andExpect(jsonPath("$.roles").value("IT_SUPPORT"))
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.hasItem("TICKET_ASSIGN")))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @DisplayName("the issued token actually authenticates subsequent requests")
    void tokenWorks() throws Exception {
        String token = tokenFor(givenUser("IT_SUPPORT"));

        mockMvc.perform(authenticated(get("/api/tickets"), token))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- uniform failure

    @Test
    @DisplayName("every credential failure produces the identical 401 body")
    void failuresAreIndistinguishable() throws Exception {
        User known = givenUser("EMPLOYEE");
        User passwordless = givenUserWithoutPassword("EMPLOYEE");
        User inactive = givenInactiveUser("EMPLOYEE");

        String unknownEmail = login("nobody@example.test", PASSWORD).getResponse().getContentAsString();
        String wrongPassword = login(known.getEmail(), "definitely-wrong-pass").getResponse()
                .getContentAsString();
        String noPasswordSet = login(passwordless.getEmail(), PASSWORD).getResponse().getContentAsString();
        String deactivated = login(inactive.getEmail(), PASSWORD).getResponse().getContentAsString();

        assertThat(login("nobody@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword).isEqualTo(unknownEmail);
        assertThat(noPasswordSet).isEqualTo(unknownEmail);
        assertThat(deactivated).isEqualTo(unknownEmail);

        assertThat(unknownEmail).contains("Invalid email or password");
        // Nothing that would distinguish the cases.
        assertThat(unknownEmail)
                .doesNotContain("deactivated", "inactive", "no password", "not found", "unknown");
    }

    @Test
    @DisplayName("a deactivated account cannot authenticate even with the right password")
    void inactiveUserCannotLogIn() throws Exception {
        User inactive = givenInactiveUser("ADMIN");

        assertThat(login(inactive.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * A null hash means "cannot authenticate" (V13). Treating it as "no password required" would have
     * turned every pre-existing passwordless account into one anybody could claim.
     */
    @Test
    @DisplayName("an account with no password set cannot authenticate with any input")
    void passwordlessAccountCannotLogIn() throws Exception {
        User passwordless = givenUserWithoutPassword("ADMIN");

        assertThat(login(passwordless.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(passwordless.getEmail(), "").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("login validates its input")
    void loginValidatesInput() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"something"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.email").exists());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","password":""}"""))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- throttling

    @Test
    @DisplayName("repeated failures trip a 429 carrying Retry-After in seconds")
    void throttleTripsAfterThreshold() throws Exception {
        User user = givenUser("EMPLOYEE");

        // The configured account threshold is 5.
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(login(user.getEmail(), "wrong-password-here").getResponse().getStatus())
                    .describedAs("attempt %d should still be a plain 401", attempt)
                    .isEqualTo(401);
        }

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-password-here"}""".formatted(user.getEmail())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.title").value("Too many requests"));
    }

    /**
     * The 429 body must not reveal a counter, a threshold, or which dimension tripped: a 429 that
     * appeared only for real accounts would be the enumeration oracle the uniform 401 removes.
     */
    @Test
    @DisplayName("the throttle reveals nothing about the account or the limiter's state")
    void throttleLeaksNothing() throws Exception {
        User user = givenUser("EMPLOYEE");
        for (int i = 0; i < 6; i++) {
            login(user.getEmail(), "wrong-password-here");
        }

        String body = login(user.getEmail(), "wrong-password-here").getResponse().getContentAsString();

        assertThat(body).doesNotContain("5", "account", "address", "attempts remaining", user.getEmail());
    }

    @Test
    @DisplayName("a blocked account stays blocked even with the correct password")
    void throttleAppliesBeforePasswordCheck() throws Exception {
        User user = givenUser("EMPLOYEE");
        for (int i = 0; i < 5; i++) {
            login(user.getEmail(), "wrong-password-here");
        }

        // Checked before the account is looked up, so a refusal costs no query and no BCrypt work.
        assertThat(login(user.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("an unknown email is throttled too, so 429 cannot confirm an account exists")
    void unknownEmailsAreThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("ghost@example.test", "wrong-password-here");
        }

        assertThat(login("ghost@example.test", "wrong-password-here").getResponse().getStatus())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("a successful login clears earlier failures")
    void successResetsTheCounter() throws Exception {
        User user = givenUser("EMPLOYEE");
        for (int i = 0; i < 4; i++) {
            login(user.getEmail(), "wrong-password-here");
        }

        assertThat(login(user.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);

        for (int i = 0; i < 4; i++) {
            assertThat(login(user.getEmail(), "wrong-password-here").getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- password change

    @Test
    @DisplayName("a user replaces their own password and the new one works")
    void passwordChangeSucceeds() throws Exception {
        User user = givenUser("EMPLOYEE");
        String token = tokenFor(user);

        mockMvc.perform(authenticated(patch("/api/users/me/password"), token)
                        .content("""
                                {"currentPassword":"%s","newPassword":"a-brand-new-secret"}"""
                                .formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(login(user.getEmail(), "a-brand-new-secret").getResponse().getStatus()).isEqualTo(200);
        assertThat(login(user.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * Without verifying the current password, a stolen access token would be enough to take an account
     * over permanently - the token expires, a changed password does not.
     */
    @Test
    @DisplayName("the current password is verified, so a token alone cannot seize an account")
    void passwordChangeRequiresCurrentPassword() throws Exception {
        User user = givenUser("EMPLOYEE");

        mockMvc.perform(authenticated(patch("/api/users/me/password"), tokenFor(user))
                        .content("""
                                {"currentPassword":"not-the-right-one","newPassword":"a-brand-new-secret"}"""))
                .andExpect(status().isUnauthorized());

        // Unchanged.
        assertThat(login(user.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a new password must meet the same length bar as an administrator-set one")
    void newPasswordIsValidated() throws Exception {
        User user = givenUser("EMPLOYEE");

        mockMvc.perform(authenticated(patch("/api/users/me/password"), tokenFor(user))
                        .content("""
                                {"currentPassword":"%s","newPassword":"short"}""".formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.newPassword").exists());
    }

    @Test
    @DisplayName("changing a password requires authentication")
    void passwordChangeRequiresAuthentication() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"x","newPassword":"a-brand-new-secret"}"""))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- user creation

    @Test
    @DisplayName("a created user can log in with the password the administrator set")
    void createdUserCanLogIn() throws Exception {
        String adminToken = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/users"), adminToken)
                        .content("""
                                {"employeeCode":987654,"fullName":"Nadia Hussain",
                                 "email":"nadia@example.test","roles":["DEVELOPER"],"active":true,
                                 "password":"initial-password-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        assertThat(login("nadia@example.test", "initial-password-1").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("duplicate email and employee code are 409, not 500")
    void duplicatesAreConflicts() throws Exception {
        User existing = givenUser("EMPLOYEE");
        String adminToken = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/users"), adminToken)
                        .content("""
                                {"employeeCode":111222,"fullName":"Clash","email":"%s",
                                 "roles":["EMPLOYEE"],"active":true,"password":"initial-password-1"}"""
                                .formatted(existing.getEmail())))
                .andExpect(status().isConflict());

        mockMvc.perform(authenticated(post("/api/users"), adminToken)
                        .content("""
                                {"employeeCode":%d,"fullName":"Clash","email":"fresh@example.test",
                                 "roles":["EMPLOYEE"],"active":true,"password":"initial-password-1"}"""
                                .formatted(existing.getEmployeeCode())))
                .andExpect(status().isConflict());
    }
}
