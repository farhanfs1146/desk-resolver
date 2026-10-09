package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.UserSession;
import com.forward.desk_resolver.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Opens, ends and prunes sessions.
 *
 * <p>A session is the server-side half of an access token, and the reason a token can now be revoked -
 * something docs/DECISIONS.md previously recorded as an accepted limitation of stateless JWTs. See
 * {@link UserSession} for what that changes and what it costs.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /**
     * Why a session was ended. Diagnostic only - the request path checks <em>whether</em>
     * {@code revoked_at} is set and never reads this. Two values, because two things revoke a session;
     * a third gets added when a third thing does.
     */
    public enum RevocationReason {

        /** The user signed out of this session. */
        LOGOUT,

        /**
         * The user changed their password, which ends every session they hold.
         *
         * <p>Including the one making the request. A password change that left the existing session
         * alive would only half-answer the case it exists for - someone who believes their session has
         * been stolen - and the client has the new password, so re-authenticating costs them one step.
         */
        PASSWORD_CHANGED
    }

    private static final int CLIENT_IP_MAX = 64;
    private static final int USER_AGENT_MAX = 255;

    private final UserSessionRepository sessionRepository;
    private final SecurityProperties properties;

    public SessionService(UserSessionRepository sessionRepository, SecurityProperties properties) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    /**
     * Records a newly issued token.
     *
     * <p>{@code issuedAt} and {@code expiresAt} are passed in rather than computed here, so that the
     * row and the token's {@code iat}/{@code exp} claims come from one reading of the clock. Deriving
     * them twice would let the session and the token it describes expire at marginally different
     * moments, which is the sort of discrepancy that produces a bug report nobody can reproduce.
     *
     * @return the session, whose id goes into the token's {@code sid} claim
     */
    @Transactional
    public UserSession open(Long userId, Instant issuedAt, Instant expiresAt,
                            String clientIp, String userAgent) {
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setIssuedAt(issuedAt);
        session.setExpiresAt(expiresAt);
        session.setClientIp(truncate(clientIp, CLIENT_IP_MAX));
        session.setUserAgent(truncate(userAgent, USER_AGENT_MAX));
        return sessionRepository.save(session);
    }

    /** Ends one session. A second call for the same session is a no-op, not an error. */
    @Transactional
    public void revoke(UUID sessionId, RevocationReason reason) {
        int ended = sessionRepository.revoke(sessionId, Instant.now(), reason.name());
        if (ended > 0) {
            log.info("Session {} revoked ({})", sessionId, reason);
        }
    }

    /**
     * Ends every live session a user holds.
     *
     * @return how many were ended
     */
    @Transactional
    public int revokeAllForUser(Long userId, RevocationReason reason) {
        int ended = sessionRepository.revokeAllForUser(userId, Instant.now(), reason.name());
        if (ended > 0) {
            log.info("Revoked {} session(s) for user {} ({})", ended, userId, reason);
        }
        return ended;
    }

    /**
     * Deletes sessions whose tokens expired longer ago than the retention window.
     *
     * <p>Necessary, not housekeeping: the table gains a row per login and nothing else removes one, so
     * without this it grows without bound and the partial index on live sessions is the only thing
     * keeping the hot path fast. Expired rows are kept for the retention window rather than deleted on
     * expiry, so there is something to look at when asking which sessions existed around an incident.
     *
     * <p>Fully expired rows only - a live session is never touched here, whatever the window is set to,
     * because {@code expires_at} is in the future for all of them.
     */
    @Scheduled(fixedDelayString = "${app.security.session.purge-interval:PT1H}",
            initialDelayString = "${app.security.session.purge-interval:PT1H}")
    @Transactional
    public void purgeExpiredSessions() {
        Instant cutoff = Instant.now().minus(properties.session().retention());
        int deleted = sessionRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} session(s) that expired before {}", deleted, cutoff);
        }
    }

    /**
     * Keeps a client-supplied value inside its column.
     *
     * <p>{@code User-Agent} has no length limit a client is obliged to respect, and neither does an
     * {@code X-Forwarded-For} entry. Truncating loses a little diagnostic detail; not truncating would
     * mean a login failing with a database error because somebody sent a long header, so this is a
     * record-keeping field refusing to break authentication.
     */
    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
