package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.PermissionDefinition;
import com.forward.desk_resolver.entity.Role;
import com.forward.desk_resolver.repository.PermissionDefinitionRepository;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role-to-permission table, which {@code V17} moved out of Java and into
 * {@code auth.role_permissions}.
 *
 * <p>This replaces {@code RolePermissionsTest}, a unit test over a static map. The guarantees it
 * asserted are unchanged and are asserted here instead - including the important one, that no role is
 * accidentally privileged. What changed is that the thing under test is now seed data in a migration,
 * so the test needs a database: a unit test could only have re-stated the expectation, not checked what
 * a deployment would actually get.
 *
 * <p>It is also the test that would fail if {@code V17}'s transcription of the old map were wrong. The
 * migration claims no role gained or lost a capability; these assertions are that claim, written out.
 */
class RolePermissionSeedIT extends AbstractPostgresIT {

    /**
     * Granted to every role by {@code V19}, so they belong in all three sets below rather than
     * distinguishing any of them.
     *
     * <p>Named for what the wide grant means: participating in a ticket's thread is not a privilege
     * level. Which tickets a caller can participate in is decided per row by
     * {@code TicketVisibilityGuard}, not by holding these - see {@code TicketCommentIT} and
     * {@code TicketAttachmentIT}, which are what stop this from being merely asserted here.
     */
    private static final Set<String> PARTICIPATION = Set.of(
            "TICKET_COMMENT_READ", "TICKET_COMMENT_CREATE",
            "TICKET_ATTACHMENT_READ", "TICKET_ATTACHMENT_UPLOAD");

    private static final Set<String> REQUESTER = union(
            Set.of("TICKET_CREATE", "TICKET_READ_OWN", "APPLICATION_READ"), PARTICIPATION);

    private static final Set<String> SUPPORT_STAFF = union(REQUESTER, Set.of(
            "TICKET_READ_ALL", "TICKET_STATUS_CHANGE", "TICKET_ASSIGN", "USER_READ"));

    private static final Set<String> ADMINISTRATOR = union(SUPPORT_STAFF, Set.of(
            "USER_MANAGE", "APPLICATION_MANAGE"));

    /**
     * Builds each set from the one below it.
     *
     * <p>The three used to be written out longhand, which repeated the requester permissions three
     * times and the support ones twice. That was readable until a permission was granted to all seven
     * roles: adding it meant editing three literals identically, and getting one wrong would have
     * produced a failure pointing at the seed rather than at the test. Composing them states the
     * relationship the seed actually has - each tier is the one below plus something - and
     * {@code administrator()} still asserts {@code containsAll} independently, so the composition is
     * not the only thing claiming it.
     */
    private static Set<String> union(Set<String> base, Set<String> additional) {
        Set<String> combined = new java.util.HashSet<>(base);
        combined.addAll(additional);
        return Set.copyOf(combined);
    }

    @Autowired
    private PermissionDefinitionRepository permissionDefinitionRepository;

    @Test
    @DisplayName("the seven roles the Role enum used to define are all seeded, and no others")
    void sevenRolesAreSeeded() {
        assertThat(grantsByRole().keySet()).containsExactlyInAnyOrder(
                "EMPLOYEE", "MANAGER", "HOD", "DIRECTOR", "IT_SUPPORT", "DEVELOPER", "ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "MANAGER", "HOD", "DIRECTOR", "IT_SUPPORT", "DEVELOPER", "ADMIN"})
    @DisplayName("every role grants at least one permission, so none is a role in name only")
    void everyRoleGrantsSomething(String code) {
        assertThat(grantsByRole().get(code)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "MANAGER", "HOD", "DIRECTOR"})
    @DisplayName("requester roles grant exactly create, read-own and catalogue-read")
    void requesterRoles(String code) {
        assertThat(grantsByRole().get(code)).isEqualTo(REQUESTER);
    }

    @Test
    @DisplayName("MANAGER, HOD and DIRECTOR are identical to EMPLOYEE - no visibility by job title")
    void seniorRequesterRolesAreNotPrivileged() {
        Map<String, Set<String>> grants = grantsByRole();

        assertThat(grants.get("MANAGER")).isEqualTo(grants.get("EMPLOYEE"));
        assertThat(grants.get("HOD")).isEqualTo(grants.get("EMPLOYEE"));
        assertThat(grants.get("DIRECTOR")).isEqualTo(grants.get("EMPLOYEE"));

        // The specific thing that must not have been granted on the strength of a role's name.
        assertThat(grants.get("MANAGER")).doesNotContain("TICKET_READ_ALL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IT_SUPPORT", "DEVELOPER"})
    @DisplayName("support staff additionally read every ticket, change status, assign and read users")
    void supportStaff(String code) {
        assertThat(grantsByRole().get(code)).isEqualTo(SUPPORT_STAFF);
    }

    @Test
    @DisplayName("ADMIN is support staff plus user and application management, and nothing beyond")
    void administrator() {
        Map<String, Set<String>> grants = grantsByRole();

        assertThat(grants.get("ADMIN")).isEqualTo(ADMINISTRATOR);
        assertThat(grants.get("ADMIN")).containsAll(grants.get("IT_SUPPORT"));
    }

    /**
     * The one thing {@code V19} grants that no tier distinguishes.
     *
     * <p>Asserted separately from the per-tier sets because it is a different claim: those say what
     * each role holds, this says these four are deliberately uniform. A later seed that gave
     * {@code TICKET_COMMENT_CREATE} only to support staff would still satisfy a tier comparison
     * somebody had updated to match it, and would quietly leave requesters unable to answer a question
     * on their own ticket - which is the feature.
     */
    @ParameterizedTest
    @ValueSource(strings = {"TICKET_COMMENT_READ", "TICKET_COMMENT_CREATE",
            "TICKET_ATTACHMENT_READ", "TICKET_ATTACHMENT_UPLOAD"})
    @DisplayName("every role can comment and attach, because participation is not a privilege level")
    void participationIsGrantedToEveryRole(String permission) {
        assertThat(grantsByRole()).allSatisfy((role, granted) ->
                assertThat(granted)
                        .as("role %s must hold %s", role, permission)
                        .contains(permission));
    }

    @Test
    @DisplayName("the management permissions belong to ADMIN alone")
    void noRoleIsAccidentallyPrivileged() {
        Map<String, Set<String>> grants = grantsByRole();

        for (Map.Entry<String, Set<String>> entry : grants.entrySet()) {
            if (entry.getKey().equals("ADMIN")) {
                continue;
            }
            assertThat(entry.getValue())
                    .as("role %s must not hold management permissions", entry.getKey())
                    .doesNotContain("USER_MANAGE", "APPLICATION_MANAGE");
        }
    }

    @Test
    @DisplayName("every granted permission is one the code actually checks")
    void everyGrantNamesAKnownPermission() {
        Set<String> granted = grantsByRole().values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        assertThat(Permission.names()).containsAll(granted);
    }

    /**
     * The catalogue and the enum agree, in both directions.
     *
     * <p>{@code PermissionCatalogueValidator} enforces this at startup, which means this test would
     * never see a mismatch - the application context would have failed to build and every test in the
     * suite would fail instead. That is the point of asserting it anyway: this is the test that says
     * what the failure <em>means</em>, so whoever adds a {@code Permission} constant without a seed
     * migration reads "the catalogue does not match the enum" rather than hunting through a context
     * initialisation error.
     */
    @Test
    @DisplayName("auth.permissions holds exactly the Permission enum's constants")
    void catalogueMatchesTheEnum() {
        Set<String> stored = permissionDefinitionRepository.findAll().stream()
                .map(PermissionDefinition::getCode)
                .collect(Collectors.toSet());

        assertThat(stored).isEqualTo(Permission.names());
    }

    @Test
    @DisplayName("every seeded role is active, so the seed grants what it claims to")
    void seededRolesAreActive() {
        assertThat(roleRepository.findAll())
                .allSatisfy(role -> assertThat(role.getActive()).isTrue());
    }

    private Map<String, Set<String>> grantsByRole() {
        return roleRepository.findAllWithPermissions().stream()
                .collect(Collectors.toMap(Role::getCode, RolePermissionSeedIT::codesOf));
    }

    private static Set<String> codesOf(Role role) {
        return role.getPermissions().stream()
                .map(PermissionDefinition::getCode)
                .collect(Collectors.toSet());
    }
}
