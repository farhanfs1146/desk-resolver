package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /**
     * Revokes every live session in the family the given session belongs to.
     *
     * <p>This is what logout runs. It <strong>replaces a single-session revoke</strong>, which was
     * correct while a login was one session and became wrong when V21 made it a chain: a caller five
     * refreshes into their day would have ended only the 30-minute slice they were holding, leaving
     * the refresh token that mints the next one untouched. Logging out would not have logged them out.
     *
     * <p>The family is resolved by subquery rather than by loading the session first, so logout stays
     * one statement. A session id that matches nothing makes the subquery null, so nothing is updated
     * and the call is a no-op returning 0 - which is also what a second logout gets, because of the
     * {@code revoked_at is null} predicate. Neither overwrites an original revocation timestamp with a
     * later one.
     */
    @Modifying
    @Query("""
            update UserSession s
               set s.revokedAt = :at, s.revokedReason = :reason
             where s.familyId = (select o.familyId from UserSession o where o.id = :sessionId)
               and s.revokedAt is null
            """)
    int revokeFamilyOfSession(@Param("sessionId") UUID sessionId,
                              @Param("at") Instant at,
                              @Param("reason") String reason);

    /**
     * Revokes every live session a user holds.
     *
     * <p>What a password change and a deactivation need. One statement against
     * {@code idx_sessions_user_live}, which is partial on {@code revoked_at is null} precisely because
     * this is the query it exists for.
     *
     * @return how many sessions were ended, which is the number worth logging
     */
    @Modifying
    @Query("""
            update UserSession s
               set s.revokedAt = :at, s.revokedReason = :reason
             where s.userId = :userId
               and s.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("at") Instant at,
                         @Param("reason") String reason);

    /**
     * Finds the session a presented refresh token belongs to, by digest.
     *
     * <p>Deliberately returns the row <strong>whatever its state</strong> - revoked, expired, already
     * rotated - rather than filtering in the query. The caller has to tell those cases apart: a row
     * whose {@code refreshUsedAt} is set is not merely unusable, it is evidence that two parties hold
     * one single-use credential, and the response to it is to revoke the whole family. A query that
     * returned empty for it would make that indistinguishable from a token nobody ever issued.
     *
     * <p>One indexed probe on {@code idx_sessions_refresh_token_hash}, which is unique.
     */
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * Claims this session's refresh token, if nobody has claimed it yet.
     *
     * <p><strong>The {@code refresh_used_at is null} predicate is the concurrency control for the
     * whole refresh path</strong>, not an optimisation. Two requests presenting the same token can
     * both read a row that looks usable; this statement is what decides between them, because exactly
     * one of them can see a null and update it. The loser gets 0 back and must not be treated as
     * reuse - see {@code AuthServiceImpl.refresh} for why the two are different situations with
     * different consequences.
     *
     * @return 1 if this caller claimed the token, 0 if it was already claimed
     */
    @Modifying
    @Query("""
            update UserSession s
               set s.refreshUsedAt = :at
             where s.id = :sessionId
               and s.refreshUsedAt is null
            """)
    int markRefreshUsed(@Param("sessionId") UUID sessionId, @Param("at") Instant at);

    /**
     * Revokes every live session descended from one login.
     *
     * <p>What logout and refresh-token reuse both need. Logout ends a login rather than the 30-minute
     * slice of it the caller happens to be holding; reuse detection cannot tell the thief from the
     * owner, so it ends the login for both.
     *
     * <p>One statement against {@code idx_sessions_family_live}, partial on {@code revoked_at is null}
     * for the same reason {@code idx_sessions_user_live} is.
     *
     * @return how many sessions were ended
     */
    @Modifying
    @Query("""
            update UserSession s
               set s.revokedAt = :at, s.revokedReason = :reason
             where s.familyId = :familyId
               and s.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("at") Instant at,
                     @Param("reason") String reason);

    /**
     * Drops rows whose tokens expired long ago.
     *
     * <p>This table gains a row per login and nothing ever removes one, so without a purge it is an
     * unbounded log masquerading as operational state. Expired rows are not deleted the moment they
     * expire: keeping them for a retention window leaves something to look at when asking which
     * sessions existed around an incident.
     *
     * <p><strong>Both lifetimes have to be over, which is why the predicate has two halves.</strong>
     * Since V21 a session outlives its own access token: {@code expires_at} passes after
     * {@code jwt.ttl} while {@code refresh_expires_at} can be hours later, and the row is the only
     * place that refresh token exists. Deleting on {@code expires_at} alone would therefore log a
     * working user out mid-session - not immediately, and not at all under the shipped settings where
     * the retention window (7 days) is far longer than the refresh window (8 hours), but silently the
     * first time somebody lengthened one or shortened the other. Stating the condition costs nothing
     * and removes the coupling between two unrelated properties.
     *
     * <p>{@code refresh_expires_at is null} covers the pre-V21 rows, which have no refresh window to
     * wait for.
     */
    @Modifying
    @Query("""
            delete from UserSession s
             where s.expiresAt < :before
               and (s.refreshExpiresAt is null or s.refreshExpiresAt < :before)
            """)
    int deleteExpiredBefore(@Param("before") Instant before);
}
