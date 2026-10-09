package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /**
     * Revokes one session, if it is still live.
     *
     * <p>A bulk update rather than load-mutate-flush: logout does not need the entity, and the
     * {@code revoked_at is null} predicate makes a second logout a no-op returning 0 instead of
     * overwriting the original revocation timestamp with a later one.
     */
    @Modifying
    @Query("""
            update UserSession s
               set s.revokedAt = :at, s.revokedReason = :reason
             where s.id = :sessionId
               and s.revokedAt is null
            """)
    int revoke(@Param("sessionId") UUID sessionId,
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
     * Drops rows whose tokens expired long ago.
     *
     * <p>This table gains a row per login and nothing ever removes one, so without a purge it is an
     * unbounded log masquerading as operational state. Expired rows are not deleted the moment they
     * expire: keeping them for a retention window leaves something to look at when asking which
     * sessions existed around an incident.
     */
    @Modifying
    @Query("delete from UserSession s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
