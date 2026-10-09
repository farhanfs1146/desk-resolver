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
 * An employee record and the identity they authenticate with.
 *
 * <p><strong>It lives in the {@code auth} schema</strong> as of {@code V17}, along with roles,
 * permissions, grants and sessions. The four foreign keys that point at it from the ticketing tables
 * ({@code tickets.raised_by}, {@code tickets.assigned_to}, {@code ticket_history_tracking.changed_by},
 * {@code ticket_comments.commented_by}) were not disturbed: PostgreSQL moves a table's constraints and
 * indexes with it, and cross-schema references within one database are ordinary foreign keys.
 *
 * <p><strong>There is no {@code role} field any more.</strong> A user holds zero or more roles through
 * {@link UserRole}, and the mapping is deliberately <em>not</em> exposed here as a collection:
 *
 * <ul>
 *   <li>The request path never needs it. Authorities are resolved by one projection query in
 *       {@code AuthContextLoader}, which reads codes and not entities.
 *   <li>A mapped collection on an entity this widely loaded - every ticket has two {@code User}
 *       associations - is an invitation to an accidental N+1 or an unwanted eager fetch. Reads that do
 *       want role codes ask {@code UserRoleRepository} for exactly those, in one query for a whole
 *       page of users.
 * </ul>
 */
@Entity
@Table(name = "users", schema = "auth")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_code", nullable = false, unique = true)
    private Long employeeCode;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    // unique = true mirrors users_email_key from V1. The constraint was already in the database, but
    // the mapping did not say so - and email is the login identifier, so findByEmail returning
    // Optional<User> silently assumed a uniqueness the entity never declared.
    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "designation_id")
    private Long designationId;

    /**
     * Whether the account may be used.
     *
     * <p>Checked at login, and - since {@code V17} - on <em>every</em> authenticated request, because
     * the session lookup joins this column. Deactivating a user now ends their live sessions
     * immediately instead of leaving them usable until the token expired.
     */
    @Column(name = "active")
    private Boolean active = true;

    /**
     * BCrypt hash of the user's password, or null when no password has been set.
     *
     * <p>Null is the default and means the account cannot authenticate - enabling authentication
     * must not hand every pre-existing user a usable login. Never returned through any DTO:
     * UserResponse has no corresponding field, so there is no path by which a hash reaches a client.
     */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;
}
