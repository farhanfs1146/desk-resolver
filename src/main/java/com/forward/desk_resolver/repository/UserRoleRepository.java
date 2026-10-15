package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.Key> {

    /**
     * Role codes for a set of users, as {@code [userId, code]} pairs.
     *
     * <p><strong>One query for a whole page.</strong> A page of twenty users needs twenty users' roles,
     * and asking per user is the textbook N+1 - which is precisely what the ticket list was fixed for
     * in Phase 5. The caller groups the pairs by id in memory; twenty users have at most a few dozen
     * grants between them.
     *
     * <p>Ordered, so a user's roles come back the same way every time and an identical request does not
     * reshuffle the array.
     *
     * <p>Includes grants of deactivated roles, unlike {@link #findActivePermissionCodesByUserId}. An
     * administrative view should show what a user has actually been granted; hiding a grant because the
     * role is currently switched off would make the screen disagree with the table it is editing.
     */
    @Query("""
            select ur.userId, ur.role.code
            from UserRole ur
            where ur.userId in :userIds
            order by ur.userId, ur.role.code
            """)
    List<Object[]> findRoleCodesByUserIds(@Param("userIds") Collection<Long> userIds);

    /** As above for a single user. Same inclusion rule: every grant, active role or not. */
    @Query("select ur.role.code from UserRole ur where ur.userId = :userId order by ur.role.code")
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * The role codes a user can currently <em>use</em>.
     *
     * <p>Active roles only, because this answers the login response's "what are you", and reporting a
     * role that grants nothing would be telling the client something untrue about what it may render.
     */
    @Query("""
            select ur.role.code
            from UserRole ur
            where ur.userId = :userId
              and ur.role.active = true
            order by ur.role.code
            """)
    List<String> findActiveRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * The union of the permissions a user's active roles grant.
     *
     * <p>Used once per login, to tell the client what to render - never on the request path, where
     * {@code AuthContextLoader} resolves the same thing from the session in a single query. The two are
     * separate on purpose: this one keys off the user, that one off the session, and sharing an
     * implementation would mean one of them doing a lookup it does not need.
     *
     * <p>{@code distinct} matters: two roles granting the same permission is one permission.
     */
    @Query(value = """
            select distinct p.code
            from auth.user_roles ur
                     join auth.roles r on r.id = ur.role_id and r.active = true
                     join auth.role_permissions rp on rp.role_id = r.id
                     join auth.permissions p on p.id = rp.permission_id
            where ur.user_id = :userId
            order by p.code
            """, nativeQuery = true)
    List<String> findActivePermissionCodesByUserId(@Param("userId") Long userId);

    /**
     * How many <em>other</em> active users hold a role that grants this permission - "would anybody
     * still hold it if this user did not?"
     *
     * <p>The lockout guard. Before the last {@code USER_MANAGE} holder can have that capability taken
     * away, the attempt has to fail: otherwise one careless role change leaves a deployment with nobody
     * able to create a user or undo the mistake, and the bootstrap administrator cannot help because it
     * only runs when there is no administrator at all.
     *
     * <p>Native, because it walks four tables in the {@code auth} schema and reads more clearly as the
     * join it is. Three details are load-bearing:
     *
     * <ul>
     *   <li>{@code count(DISTINCT user_id)} - a user holding two roles that both grant the permission
     *       is one holder, not two.
     *   <li>{@code u.active} and {@code r.active} - an inactive user, or a grant through a deactivated
     *       role, cannot exercise the permission, so counting it would be counting a holder who is not
     *       one.
     *   <li>{@code <> :excludedUserId} - the question is about everyone except the user being edited.
     * </ul>
     */
    /**
     * Whether one user can exercise one permission right now.
     *
     * <p>Asked about a <em>third party</em>, which is why it exists alongside
     * {@code CurrentUserProvider.hasPermission}: that one reads the caller's own authorities out of the
     * security context and cannot answer anything about somebody else. This is the question
     * {@code TicketServiceImpl.requireAssignable} needs - "may the person I am about to hand this
     * ticket to actually progress it" - and the only way to answer it is the database.
     *
     * <p>Same three conditions as {@link #countOtherActiveHoldersOfPermission}: the grant has to come
     * through an active role, and the holder has to be an active account. A permission reachable only
     * through a deactivated role is not held. {@code exists} rather than a count, so the query stops at
     * the first matching row instead of fanning out over every role that grants it.
     */
    @Query(value = """
            select exists (
                select 1
                from auth.user_roles ur
                         join auth.users u on u.id = ur.user_id and u.active = true
                         join auth.roles r on r.id = ur.role_id and r.active = true
                         join auth.role_permissions rp on rp.role_id = r.id
                         join auth.permissions p on p.id = rp.permission_id
                where ur.user_id = :userId
                  and p.code = :permissionCode
            )
            """, nativeQuery = true)
    boolean userHoldsPermission(@Param("userId") Long userId,
                                @Param("permissionCode") String permissionCode);

    @Query(value = """
            select count(distinct ur.user_id)
            from auth.user_roles ur
                     join auth.users u on u.id = ur.user_id and u.active = true
                     join auth.roles r on r.id = ur.role_id and r.active = true
                     join auth.role_permissions rp on rp.role_id = r.id
                     join auth.permissions p on p.id = rp.permission_id
            where p.code = :permissionCode
              and ur.user_id <> :excludedUserId
            """, nativeQuery = true)
    long countOtherActiveHoldersOfPermission(@Param("permissionCode") String permissionCode,
                                             @Param("excludedUserId") Long excludedUserId);

    /**
     * Everybody who can exercise one permission right now.
     *
     * <p>The set, where {@link #countOtherActiveHoldersOfPermission} is the size and
     * {@link #userHoldsPermission} is a membership test. It exists for notification fan-out, which
     * has one event with no individual to address: a ticket is raised and nobody owns it, so the
     * people to tell are whoever holds {@code TICKET_ASSIGN}. Every other notification's recipients
     * come from the ticket itself.
     *
     * <p>Same three conditions as its two siblings - the grant has to come through an active role and
     * the holder has to be an active account - because a notification sent to somebody who cannot act
     * on it is worse than none: it sits unread in an inbox nobody opens while the queue looks
     * attended to.
     *
     * <p>Ordered, so a fan-out writes its rows in the same order every time and a test can assert on
     * them without sorting first.
     */
    @Query(value = """
            select distinct ur.user_id
            from auth.user_roles ur
                     join auth.users u on u.id = ur.user_id and u.active = true
                     join auth.roles r on r.id = ur.role_id and r.active = true
                     join auth.role_permissions rp on rp.role_id = r.id
                     join auth.permissions p on p.id = rp.permission_id
            where p.code = :permissionCode
            order by ur.user_id
            """, nativeQuery = true)
    List<Long> findActiveHolderIdsOfPermission(@Param("permissionCode") String permissionCode);

    /** Used by the bootstrap administrator check; counts rather than loads, so it stays cheap. */
    @Query("select count(ur) from UserRole ur where ur.role.code = :roleCode")
    long countByRoleCode(@Param("roleCode") String roleCode);

    /**
     * Removes every grant a user holds.
     *
     * <p>Half of the replace-set operation. A bulk delete rather than loading the grants first: the
     * rows carry no state worth reading on the way out, and {@code PUT /api/users/{id}/roles} is
     * defined as "these are the roles afterwards", so the previous set is not consulted.
     */
    @Modifying
    @Query("delete from UserRole ur where ur.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
