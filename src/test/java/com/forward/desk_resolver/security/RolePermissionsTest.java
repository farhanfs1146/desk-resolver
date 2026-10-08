package com.forward.desk_resolver.security;

import com.forward.desk_resolver.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The role to permission mapping - the whole authorization model in one table.
 *
 * <p>Tested directly because every {@code @PreAuthorize} expression in the application resolves through
 * it, so a mistake here is not a failing endpoint but a silently over-privileged role.
 */
class RolePermissionsTest {

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("every declared role is mapped")
    void everyRoleIsMapped(Role role) {
        // An unmapped role grants nothing, which fails safe - but it also means an endpoint quietly
        // becomes unreachable for those users, so the mapping must be complete on purpose.
        assertThat(RolePermissions.of(role))
                .describedAs("role %s has no permissions; was it added to the enum but not mapped?", role)
                .isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"EMPLOYEE", "MANAGER", "HOD", "DIRECTOR"})
    @DisplayName("requester roles can raise and read their own tickets, and nothing more")
    void requesterRoles(Role role) {
        assertThat(RolePermissions.of(role)).containsExactlyInAnyOrder(
                Permission.TICKET_CREATE,
                Permission.TICKET_READ_OWN,
                Permission.APPLICATION_READ);
    }

    /**
     * MANAGER, HOD and DIRECTOR deliberately get exactly what EMPLOYEE gets. Assuming a manager should
     * see their department's tickets would mean inventing a rule the schema cannot express:
     * {@code users.department_id} points at a table that does not exist.
     */
    @Test
    @DisplayName("seniority alone grants no extra visibility")
    void seniorRequestersAreNotPrivileged() {
        Set<Permission> employee = RolePermissions.of(Role.EMPLOYEE);

        assertThat(RolePermissions.of(Role.MANAGER)).isEqualTo(employee);
        assertThat(RolePermissions.of(Role.HOD)).isEqualTo(employee);
        assertThat(RolePermissions.of(Role.DIRECTOR)).isEqualTo(employee);

        assertThat(employee).doesNotContain(
                Permission.TICKET_READ_ALL,
                Permission.TICKET_ASSIGN,
                Permission.TICKET_STATUS_CHANGE,
                Permission.USER_READ,
                Permission.USER_MANAGE,
                Permission.APPLICATION_MANAGE);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"IT_SUPPORT", "DEVELOPER"})
    @DisplayName("support staff triage every ticket but administer nothing")
    void supportStaff(Role role) {
        assertThat(RolePermissions.of(role)).containsExactlyInAnyOrder(
                Permission.TICKET_CREATE,
                Permission.TICKET_READ_OWN,
                Permission.TICKET_READ_ALL,
                Permission.TICKET_STATUS_CHANGE,
                Permission.TICKET_ASSIGN,
                Permission.USER_READ,
                Permission.APPLICATION_READ);

        assertThat(RolePermissions.of(role))
                .doesNotContain(Permission.USER_MANAGE, Permission.APPLICATION_MANAGE);
    }

    @Test
    @DisplayName("ADMIN is support staff plus user and application management")
    void administrator() {
        assertThat(RolePermissions.of(Role.ADMIN))
                .containsAll(RolePermissions.of(Role.IT_SUPPORT))
                .contains(Permission.USER_MANAGE, Permission.APPLICATION_MANAGE);
    }

    @Test
    @DisplayName("only ADMIN holds the management permissions")
    void managementIsAdminOnly() {
        for (Role role : Role.values()) {
            if (role == Role.ADMIN) {
                continue;
            }
            assertThat(RolePermissions.of(role))
                    .describedAs("role %s must not manage users or applications", role)
                    .doesNotContain(Permission.USER_MANAGE, Permission.APPLICATION_MANAGE);
        }
    }

    @Test
    @DisplayName("every permission the enum declares is reachable by some role")
    void noOrphanPermissions() {
        // An unused permission is an untested permission: either a role should hold it or it should not
        // exist.
        for (Permission permission : Permission.values()) {
            boolean held = false;
            for (Role role : Role.values()) {
                if (RolePermissions.of(role).contains(permission)) {
                    held = true;
                    break;
                }
            }
            assertThat(held)
                    .describedAs("permission %s is granted to no role", permission)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a null role grants nothing rather than everything")
    void nullRoleGrantsNothing() {
        assertThat(RolePermissions.of(null)).isEmpty();
    }

    /**
     * The three permission sets were previously mutable {@code EnumSet}s in {@code static final}
     * fields, with the administrator set assembled by a second static block that mutated it after
     * assignment. Anything holding a reference could have edited the authorization model at runtime.
     */
    @Test
    @DisplayName("the returned set cannot be modified")
    void returnedSetIsImmutable() {
        Set<Permission> employee = RolePermissions.of(Role.EMPLOYEE);

        assertThatThrownBy(() -> employee.add(Permission.USER_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> employee.remove(Permission.TICKET_CREATE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(employee::clear)
                .isInstanceOf(UnsupportedOperationException.class);

        // And the stored set was not changed by the attempts above.
        assertThat(RolePermissions.of(Role.EMPLOYEE)).contains(Permission.TICKET_CREATE);
    }
}
