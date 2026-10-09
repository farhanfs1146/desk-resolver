package com.forward.desk_resolver.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves a session id into the caller's identity, roles and permissions - in one query.
 *
 * <h2>Why one query and not three</h2>
 *
 * This runs on every authenticated request, so its shape is the cost of the whole authorization
 * model. The obvious implementation is three round trips: validate the session, load the user's
 * roles, load those roles' permissions. This does it in one, by joining from the session through to
 * the permission codes and letting the caller fold the rows.
 *
 * <p>The join fans out - a user with two roles of seven permissions each produces fourteen rows - but
 * every row is two short strings, every table on the path is small, and the entry point is a primary
 * key lookup on {@code auth.sessions}. Compare that with what it replaced: a static map lookup and no
 * query at all. The query is the price of two properties that were not available before, and it buys
 * both at once rather than one each:
 *
 * <ul>
 *   <li><strong>Revocation.</strong> Logout, and revoke-all-on-password-change, now end a session
 *       immediately instead of leaving a token usable for the rest of its 30 minutes.
 *   <li><strong>Database-driven authorization.</strong> A role or permission change takes effect on
 *       the next request, which was already true of this application and had to stay true.
 * </ul>
 *
 * <h2>Why LEFT JOINs</h2>
 *
 * The joins from {@code user_roles} outward are left joins so that a valid session for a user with no
 * roles still returns a row. That distinction carries the difference between two outcomes that must
 * not be confused: <em>no rows</em> means the session is not usable and the request is a 401, while
 * <em>rows with null codes</em> means an authenticated caller who holds no permissions and will get a
 * 403 from whatever they try. Collapsing the two would turn "your account has no roles" into
 * "your token is invalid", and send the user to the login screen to fix something logging in cannot
 * fix.
 *
 * <h2>Why native SQL</h2>
 *
 * The query spans five tables in another schema, two of which ({@code role_permissions}) are a join
 * table this application maps as an association rather than an entity. Written as JPQL it would
 * either need entity joins or an association walk, and would read less like the index access path it
 * is. It sits next to the migration that creates those tables, and the integration suite runs it
 * against the real schema on every build.
 */
@Component
public class AuthContextLoader {

    private static final Logger log = LoggerFactory.getLogger(AuthContextLoader.class);

    /**
     * Column order: user id, account enabled, role code, permission code.
     *
     * <p>{@code r.active = true} is part of the join condition rather than the WHERE clause on
     * purpose: in the WHERE clause it would discard the whole row for a user whose only role is
     * deactivated, turning them into a 401 instead of an authenticated caller with no permissions.
     */
    private static final String AUTH_CONTEXT_QUERY = """
            select s.user_id,
                   u.active,
                   r.code as role_code,
                   p.code as permission_code
            from auth.sessions s
                     join auth.users u on u.id = s.user_id
                     left join auth.user_roles ur on ur.user_id = s.user_id
                     left join auth.roles r on r.id = ur.role_id and r.active = true
                     left join auth.role_permissions rp on rp.role_id = r.id
                     left join auth.permissions p on p.id = rp.permission_id
            where s.id = :sessionId
              and s.revoked_at is null
              and s.expires_at > :now
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return the caller's context, or empty when the session does not exist, has been revoked, or has
     *         expired - all three being indistinguishable to the client on purpose
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<AuthContext> load(UUID sessionId, Instant now) {
        List<Object[]> rows = entityManager.createNativeQuery(AUTH_CONTEXT_QUERY)
                .setParameter("sessionId", sessionId)
                .setParameter("now", now)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Long userId = ((Number) rows.getFirst()[0]).longValue();
        boolean userActive = Boolean.TRUE.equals(rows.getFirst()[1]);

        Set<String> roleCodes = new LinkedHashSet<>();
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);

        for (Object[] row : rows) {
            if (row[2] != null) {
                roleCodes.add((String) row[2]);
            }
            if (row[3] != null) {
                toPermission((String) row[3]).ifPresent(permissions::add);
            }
        }

        return Optional.of(new AuthContext(userId, userActive, Set.copyOf(roleCodes), Set.copyOf(permissions)));
    }

    /**
     * Maps a stored permission code onto the enum, discarding anything the enum does not know.
     *
     * <p>Fails closed, and should never fire: {@code PermissionCatalogueValidator} refuses to let the
     * application start if the table and the enum disagree. It is here because the alternative - an
     * exception from the security filter chain - would turn a seeding mistake into a 500 on every
     * request, and because a permission the code cannot name is a permission the code cannot check,
     * so ignoring it grants nothing.
     */
    private static Optional<Permission> toPermission(String code) {
        try {
            return Optional.of(Permission.valueOf(code));
        } catch (IllegalArgumentException e) {
            log.warn("auth.permissions contains '{}', which is not a Permission constant; ignoring it. "
                    + "The catalogue and the enum have drifted apart.", code);
            return Optional.empty();
        }
    }
}
