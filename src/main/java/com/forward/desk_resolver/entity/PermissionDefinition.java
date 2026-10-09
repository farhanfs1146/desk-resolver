package com.forward.desk_resolver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The {@code auth.permissions} row for one capability.
 *
 * <p><strong>Why this is not the authority on which permissions exist.</strong> That remains
 * {@link com.forward.desk_resolver.security.Permission}, the enum, and this table is seeded from it by
 * {@code V17}. The split is deliberate and it is the one design decision in this schema most worth
 * understanding:
 *
 * <ul>
 *   <li>Code is what <em>enforces</em> a permission -
 *       {@code @PreAuthorize("hasAuthority('TICKET_ASSIGN'))"} names it as a string literal. If the row
 *       were ordinary editable data, renaming or deleting it would disable that check with nothing
 *       failing anywhere, which is the worst possible shape for an authorization bug.
 *   <li>Data is the right home for <em>assignment</em> - which role holds which permission, and which
 *       user holds which role. That is what an administrator needs to change without a redeploy, and
 *       it lives in {@code auth.role_permissions} and {@code auth.user_roles}.
 * </ul>
 *
 * <p>{@code PermissionCatalogueValidator} fails startup if the enum and this table drift apart in
 * either direction, so the seed cannot quietly rot.
 *
 * <p>Named {@code PermissionDefinition} rather than {@code Permission} because the enum already owns
 * that name, and the two appear together in the same files. The longer name says what this is: the
 * database's record <em>of</em> a permission, not the permission itself.
 */
@Entity
@Table(name = "permissions", schema = "auth")
@Getter
@Setter
public class PermissionDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Matches a {@link com.forward.desk_resolver.security.Permission} constant name exactly. */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "description", length = 500)
    private String description;
}
