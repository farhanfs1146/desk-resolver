package com.forward.desk_resolver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A role: a named bundle of permissions that users can be granted.
 *
 * <p><strong>This replaces the {@code Role} enum.</strong> The seven codes it defined - {@code
 * EMPLOYEE, MANAGER, HOD, DIRECTOR, IT_SUPPORT, DEVELOPER, ADMIN} - are seeded by {@code V17} and
 * none was added or removed. The enum is gone because an enum cannot be extended without a redeploy,
 * which was the point of moving roles into the database. Well-known codes that code still has to name
 * (the bootstrap administrator needs {@code ADMIN}) live in {@code SystemRoles} as string constants,
 * and are resolved against this table at runtime rather than assumed to exist.
 *
 * <p><strong>No hierarchy.</strong> There is no parent role and no inheritance; see {@code V17} for
 * why. A user's effective permissions are the flat union of the grants of the roles they hold.
 *
 * <p>{@code permissions} is mapped as a {@code @ManyToMany} because {@code auth.role_permissions} has
 * no columns beyond the two keys. {@code auth.user_roles} does have extra columns, and is therefore an
 * entity of its own ({@link UserRole}) rather than a second join table.
 */
@Entity
@Table(name = "roles", schema = "auth")
@Getter
@Setter
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The stable identifier: {@code ADMIN}, {@code IT_SUPPORT}, and so on.
     *
     * <p>This, not {@link #id}, is what seed data, tests and {@code SystemRoles} refer to, and what a
     * {@code ROLE_<CODE>} authority is built from. Treat it as immutable; display text belongs in
     * {@link #name}.
     */
    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * A deactivated role grants nothing.
     *
     * <p>The per-request authority query joins {@code active = true}, so clearing this flag withdraws
     * the role's permissions from everyone holding it on their next request - without deleting the
     * record of who held it, which is what a hard delete would throw away.
     */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The permissions this role grants.
     *
     * <p>Lazy, and never traversed on the request path: authorities are resolved by a single
     * projection query in {@code AuthContextLoader}, not by loading role entities. This association
     * exists for the administrative read ({@code GET /api/roles}), which fetches it explicitly with a
     * join fetch so a list of roles costs one query rather than one per role.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions", schema = "auth",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<PermissionDefinition> permissions = new LinkedHashSet<>();
}
