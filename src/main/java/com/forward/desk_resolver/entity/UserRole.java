package com.forward.desk_resolver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One grant: this user holds this role.
 *
 * <p><strong>Why an entity rather than a {@code @ManyToMany} join table on {@link User}.</strong> A
 * plain {@code @ManyToMany} would have been less code, and it was the first thing tried. It cannot
 * write {@code granted_by}: Hibernate inserts only the two key columns into a join table, so every
 * grant would have recorded a null grantor. "Who gave this person administrator rights, and when" is
 * the first question asked after a privilege incident and the one piece of information that cannot be
 * reconstructed later, so the audit columns decided the mapping.
 *
 * <p>{@link #role} is mapped read-only over the same {@code role_id} column the composite key uses, so
 * a grant can be read with its role code in one join without the key becoming an association.
 */
@Entity
@Table(name = "user_roles", schema = "auth")
@IdClass(UserRole.Key.class)
@Getter
@Setter
public class UserRole {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    /** Read-only view of {@link #roleId}; see the class comment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /**
     * The user who made this grant, or null when there was no human grantor - the {@code V17} backfill
     * and the bootstrap administrator. Every grant made through the API carries one.
     */
    @Column(name = "granted_by")
    private Long grantedBy;

    public static UserRole of(Long userId, Long roleId, Long grantedBy) {
        UserRole grant = new UserRole();
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        grant.setGrantedBy(grantedBy);
        grant.setGrantedAt(Instant.now());
        return grant;
    }

    /** Composite key for {@code (user_id, role_id)}. */
    @Getter
    @Setter
    public static class Key implements Serializable {

        private Long userId;
        private Long roleId;

        public Key() {
        }

        public Key(Long userId, Long roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(roleId, key.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }
}
