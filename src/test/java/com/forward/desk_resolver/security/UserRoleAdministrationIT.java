package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.Role;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Many roles per user, and the endpoints that administer them.
 *
 * <p>The capability {@code V17} exists for. Before it, a user held exactly one role, so somebody who
 * was both a developer and an administrator needed two accounts - and changing anyone's role meant a
 * manual {@code UPDATE}.
 */
class UserRoleAdministrationIT extends AbstractPostgresIT {

    // ------------------------------------------------------------------ the union rule

    /**
     * Permissions are the union of the user's roles' grants.
     *
     * <p>Stated as a test because it is a decision, not an inevitability: the alternative - intersection,
     * or a deny that wins - is what some RBAC implementations do. Union means adding a role can only
     * widen access, which is what makes "grant someone an extra role" a safe-sounding operation that
     * must still be reviewed.
     */
    @Test
    @DisplayName("an EMPLOYEE who is also IT_SUPPORT gets the union, and so sees every ticket")
    void permissionsAreTheUnionOfEveryRole() throws Exception {
        User owner = givenUser("EMPLOYEE");
        var application = givenApplication();

        String ownerToken = tokenFor(owner);
        mockMvc.perform(authenticated(post("/api/tickets"), ownerToken)
                        .content("""
                                {"title":"Payslip missing","description":"No payslip for this month",
                                 "issueType":"BUG","priority":"HIGH","applicationId":%d,
                                 "moduleName":"Salary"}""".formatted(application.getId())))
                .andExpect(status().isCreated());

        // One account, two roles. The narrow one alone would see nothing of somebody else's ticket.
        User both = givenUser("EMPLOYEE", "IT_SUPPORT");

        mockMvc.perform(authenticated(get("/api/tickets"), tokenFor(both)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("login reports every role held and the permissions they add up to")
    void loginReportsRolesAndPermissions() throws Exception {
        User user = givenUser("EMPLOYEE", "DEVELOPER");

        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andReturn();

        assertThat(this.<java.util.List<String>>read(result, "$.roles"))
                .containsExactlyInAnyOrder("EMPLOYEE", "DEVELOPER");
        assertThat(this.<java.util.List<String>>read(result, "$.permissions"))
                .contains("TICKET_READ_ALL", "TICKET_ASSIGN", "USER_READ")
                .doesNotContain("USER_MANAGE");
    }

    // ------------------------------------------------------------------ no roles at all

    /**
     * An account with no roles authenticates and can do essentially nothing.
     *
     * <p>Worth its own test because it is the case most likely to be got wrong in the other direction:
     * a "no authorities" caller could plausibly have been treated as unauthenticated, which would send
     * them to the login screen to fix something logging in cannot fix.
     */
    @Test
    @DisplayName("a user with no roles authenticates, can change their password, and nothing else")
    void rolelessUserIsAuthenticatedButUnauthorised() throws Exception {
        User user = givenUser();
        String token = tokenFor(user);

        mockMvc.perform(authenticated(get("/api/tickets"), token))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authenticated(patch("/api/users/me/password"), token)
                        .content("""
                                {"currentPassword":"%s","newPassword":"another-long-password"}"""
                                .formatted(PASSWORD)))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ creating with roles

    @Test
    @DisplayName("a user can be created holding several roles at once")
    void createUserWithSeveralRoles() throws Exception {
        String admin = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/users"), admin)
                        .content("""
                                {"employeeCode":77001,"fullName":"Sana Iqbal","email":"sana@example.test",
                                 "roles":["IT_SUPPORT","DEVELOPER"],"active":true,
                                 "password":"initial-password-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles.length()").value(2));
    }

    @Test
    @DisplayName("an unknown role code is a 400 naming it, not an account with no roles")
    void unknownRoleCodeIsRejected() throws Exception {
        String admin = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/users"), admin)
                        .content("""
                                {"employeeCode":77002,"fullName":"Typo Case","email":"typo@example.test",
                                 "roles":["ADMNI"],"active":true,"password":"initial-password-1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("ADMNI")));

        assertThat(userRepository.existsByEmail("typo@example.test")).isFalse();
    }

    @Test
    @DisplayName("every unknown role code is reported, not just the first")
    void allUnknownRoleCodesAreReported() throws Exception {
        String admin = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/users"), admin)
                        .content("""
                                {"employeeCode":77003,"fullName":"Two Typos","email":"two@example.test",
                                 "roles":["ADMNI","EMPLOYE"],"active":true,
                                 "password":"initial-password-1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("ADMNI"),
                                org.hamcrest.Matchers.containsString("EMPLOYE"))));
    }

    @Test
    @DisplayName("creating a user with no roles is a validation failure, not a silent default")
    void rolesAreRequiredOnCreate() throws Exception {
        String admin = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/users"), admin)
                        .content("""
                                {"employeeCode":77004,"fullName":"No Roles","email":"noroles@example.test",
                                 "roles":[],"active":true,"password":"initial-password-1"}"""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ replacing roles

    @Test
    @DisplayName("PUT /api/users/{id}/roles replaces the set, and the change applies to the next request")
    void replacingRolesTakesEffectImmediately() throws Exception {
        User admin = givenUser("ADMIN");
        User target = givenUser("EMPLOYEE");

        String adminToken = tokenFor(admin);
        String targetToken = tokenFor(target);

        mockMvc.perform(authenticated(get("/api/users"), targetToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(authenticated(put("/api/users/" + target.getId() + "/roles"), adminToken)
                        .content("""
                                {"roles":["IT_SUPPORT"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("IT_SUPPORT"));

        // The same token, already issued. Authorities are resolved per request, so no re-login is
        // needed and none is forced - which is why the role change does not revoke sessions.
        mockMvc.perform(authenticated(get("/api/users"), targetToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("replacing with a role the user already holds does not collide with the primary key")
    void replacingIsIdempotent() throws Exception {
        User admin = givenUser("ADMIN");
        User target = givenUser("EMPLOYEE", "DEVELOPER");
        String adminToken = tokenFor(admin);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(authenticated(put("/api/users/" + target.getId() + "/roles"), adminToken)
                            .content("""
                                    {"roles":["EMPLOYEE","DEVELOPER"]}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles.length()").value(2));
        }

        assertThat(userRoleRepository.findRoleCodesByUserId(target.getId()))
                .containsExactly("DEVELOPER", "EMPLOYEE");
    }

    @Test
    @DisplayName("an empty array strips every role, leaving the account able only to sign in")
    void anEmptySetRemovesEveryRole() throws Exception {
        User admin = givenUser("ADMIN");
        User target = givenUser("IT_SUPPORT");
        String adminToken = tokenFor(admin);
        String targetToken = tokenFor(target);

        mockMvc.perform(authenticated(put("/api/users/" + target.getId() + "/roles"), adminToken)
                        .content("""
                                {"roles":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(0));

        mockMvc.perform(authenticated(get("/api/users"), targetToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("replacing roles on an unknown user is 404")
    void replacingRolesOnUnknownUser() throws Exception {
        String admin = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(put("/api/users/999999/roles"), admin)
                        .content("""
                                {"roles":["EMPLOYEE"]}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a caller without USER_MANAGE cannot change anybody's roles, including their own")
    void replacingRolesRequiresUserManage() throws Exception {
        User support = givenUser("IT_SUPPORT");
        String token = tokenFor(support);

        mockMvc.perform(authenticated(put("/api/users/" + support.getId() + "/roles"), token)
                        .content("""
                                {"roles":["ADMIN"]}"""))
                .andExpect(status().isForbidden());

        assertThat(userRoleRepository.findRoleCodesByUserId(support.getId()))
                .containsExactly("IT_SUPPORT");
    }

    // ------------------------------------------------------------------ the lockout guard

    @Test
    @DisplayName("the last administrator cannot remove their own ability to manage users")
    void theLastAdministratorCannotLockThemselvesOut() throws Exception {
        User onlyAdmin = givenUser("ADMIN");
        String token = tokenFor(onlyAdmin);

        mockMvc.perform(authenticated(put("/api/users/" + onlyAdmin.getId() + "/roles"), token)
                        .content("""
                                {"roles":["EMPLOYEE"]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Change refused"));

        assertThat(userRoleRepository.findRoleCodesByUserId(onlyAdmin.getId()))
                .containsExactly("ADMIN");
    }

    @Test
    @DisplayName("an administrator may step down once somebody else can manage users")
    void anAdministratorMayStepDownWhenAnotherExists() throws Exception {
        User first = givenUser("ADMIN");
        givenUser("ADMIN");
        String token = tokenFor(first);

        mockMvc.perform(authenticated(put("/api/users/" + first.getId() + "/roles"), token)
                        .content("""
                                {"roles":["EMPLOYEE"]}"""))
                .andExpect(status().isOk());
    }

    /**
     * A deactivated administrator does not count as a holder.
     *
     * <p>The guard counts <em>active</em> users, because an account that cannot sign in cannot exercise
     * the permission. Counting it would let the last usable administrator step down while the system
     * believed somebody else could step in.
     */
    @Test
    @DisplayName("a deactivated administrator does not keep the last active one from being blocked")
    void aDeactivatedAdministratorDoesNotCount() throws Exception {
        User active = givenUser("ADMIN");
        givenInactiveUser("ADMIN");
        String token = tokenFor(active);

        mockMvc.perform(authenticated(put("/api/users/" + active.getId() + "/roles"), token)
                        .content("""
                                {"roles":["EMPLOYEE"]}"""))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ deactivating a role

    /**
     * A deactivated role grants nothing, on the next request, to everyone holding it.
     *
     * <p>This is what {@code roles.active} is for, and it is enforced in one place - the
     * {@code r.active = true} join condition in {@code AuthContextLoader}. It is the alternative to
     * deleting a role, which would discard the record of who held it.
     *
     * <p>The seeded roles are shared across the whole suite and {@code resetState} does not restore
     * them, so this test puts the flag back in a {@code finally}. A test that left {@code DEVELOPER}
     * switched off would not fail here; it would fail somewhere else, later, for no visible reason.
     */
    @Test
    @DisplayName("deactivating a role withdraws its permissions from everyone holding it")
    void deactivatingARoleWithdrawsItsPermissions() throws Exception {
        User user = givenUser("DEVELOPER");
        String token = tokenFor(user);

        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isOk());

        Role developer = roleRepository.findByCode("DEVELOPER").orElseThrow();
        try {
            developer.setActive(false);
            roleRepository.saveAndFlush(developer);

            // Same token, no re-login. The grant row is untouched; only the role is switched off.
            mockMvc.perform(authenticated(get("/api/users"), token))
                    .andExpect(status().isForbidden());

            // Still authenticated, and the grant is still recorded - this is withdrawal, not deletion.
            mockMvc.perform(authenticated(patch("/api/users/me/password"), token)
                            .content("""
                                    {"currentPassword":"%s","newPassword":"yet-another-password"}"""
                                    .formatted(PASSWORD)))
                    .andExpect(status().isNoContent());
            assertThat(userRoleRepository.findRoleCodesByUserId(user.getId()))
                    .containsExactly("DEVELOPER");
        } finally {
            developer.setActive(true);
            roleRepository.saveAndFlush(developer);
        }
    }

    // ------------------------------------------------------------------ the role catalogue

    @Test
    @DisplayName("GET /api/roles lists the seeded roles with their permissions")
    void roleCatalogueIsReadable() throws Exception {
        String admin = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(get("/api/roles"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].code").value("ADMIN"))
                .andExpect(jsonPath("$[0].permissions.length()").value(9));
    }

    @Test
    @DisplayName("the role catalogue needs USER_MANAGE - it exists for whoever assigns roles")
    void roleCatalogueRequiresUserManage() throws Exception {
        mockMvc.perform(authenticated(get("/api/roles"), tokenFor(givenUser("IT_SUPPORT"))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ audit

    @Test
    @DisplayName("a grant made through the API records who made it")
    void grantsRecordTheGrantor() throws Exception {
        User admin = givenUser("ADMIN");
        User target = givenUser("EMPLOYEE");
        String adminToken = tokenFor(admin);

        mockMvc.perform(authenticated(put("/api/users/" + target.getId() + "/roles"), adminToken)
                        .content("""
                                {"roles":["DEVELOPER"]}"""))
                .andExpect(status().isOk());

        assertThat(userRoleRepository.findAll())
                .filteredOn(grant -> grant.getUserId().equals(target.getId()))
                .singleElement()
                .satisfies(grant -> {
                    assertThat(grant.getGrantedBy()).isEqualTo(admin.getId());
                    assertThat(grant.getGrantedAt()).isNotNull();
                });
    }
}
