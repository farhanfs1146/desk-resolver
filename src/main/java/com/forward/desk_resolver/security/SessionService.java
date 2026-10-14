package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.UserSession;
import com.forward.desk_resolver.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
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
        PASSWORD_CHANGED,

        /**
         * A refresh token was presented twice, so the family it belongs to was ended.
         *
         * <p>A refresh token is usable exactly once. A second presentation means two parties hold one
         * single-use credential, and nothing on the server can tell the owner from whoever copied it -
         * so both lose the login. The legitimate user logs in again; the attacker loses a credential
         * that had hours left on it.
         *
         * <p>Unlike the other two, this reason is <em>evidence</em> rather than bookkeeping, and it is
         * the only one nobody asked for. A run of these rows against one account is the signature of a
         * token that leaked.
         */
        REFRESH_TOKEN_REUSED
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
     * <p>A login roots a new family, and the refresh token issued alongside the access token is stored
     * here as a digest - see {@link RefreshTokenService} for why a digest, and why SHA-256.
     *
     * @param refreshTokenHash digest of the refresh token handed to the client; the token itself is
     *                         never stored
     * @param refreshExpiresAt the absolute end of this chain. Copied unchanged by every subsequent
     *                         {@link #rotate}, so refreshing cannot extend it.
     * @return the session, whose id goes into the token's {@code sid} claim
     */
    @Transactional
    public UserSession open(Long userId, Instant issuedAt, Instant expiresAt,
                            String refreshTokenHash, Instant refreshExpiresAt,
                            String clientIp, String userAgent) {
        UUID id = UUID.randomUUID();
        // A login roots its own family. Generating a second UUID would allow a session whose family
        // nothing else ever belongs to - the same value under two names, and one more thing to keep
        // consistent for no gain.
        return write(id, id, userId, issuedAt, expiresAt,
                refreshTokenHash, refreshExpiresAt, clientIp, userAgent);
    }

    /**
     * Opens the session that succeeds another one, in the same family.
     *
     * <p><strong>Rotation inserts; it does not update.</strong> The previous row keeps its own
     * {@code expires_at}, so the access token a client is still holding stays valid until it expires on
     * its own - a browser with two requests in flight at the moment it refreshes does not collect 401s
     * for them. What stops immediately is the previous refresh token, which this method marks used in
     * the same transaction as the insert - so a client can never end up holding a dead token and no
     * successor.
     *
     * <p>{@code refreshExpiresAt} is <strong>copied from the predecessor, not recomputed</strong>, and
     * that one line is what makes the refresh window absolute: a chain cannot outlive
     * {@code app.security.refresh.ttl} measured from the login that started it, however many times it
     * rotates. Deriving a fresh expiry here would mean a session somebody uses every day never expires,
     * which is the property that turns one stolen refresh token into permanent access.
     *
     * @param previous         the session being rotated out; supplies the family and the absolute cap
     * @param refreshTokenHash digest of the new refresh token returned to the client
     * @return the new session, whose id becomes the new access token{@code 's} {@code sid}, or empty
     *         when a concurrent request claimed the same refresh token first
     */
    @Transactional
    public Optional<UserSession> rotate(UserSession previous, Instant issuedAt, Instant expiresAt,
                                        String refreshTokenHash, String clientIp, String userAgent) {
        // The conditional UPDATE is the concurrency control for the whole refresh path, not
        // bookkeeping. Two requests presenting the same token can both read a row that looks usable;
        // this is what decides between them, because only one can find refresh_used_at null and set it.
        //
        // Returning empty therefore means "somebody else rotated this token first", which is NOT the
        // same situation as finding the row already marked when it was read. That one is reuse and
        // revokes the family. The caller has to keep the two apart - see AuthServiceImpl.refresh.
        if (sessionRepository.markRefreshUsed(previous.getId(), Instant.now()) != 1) {
            log.info("Refresh token for session {} was claimed by a concurrent request", previous.getId());
            return Optional.empty();
        }
        return Optional.of(write(UUID.randomUUID(), previous.getFamilyId(), previous.getUserId(),
                issuedAt, expiresAt,
                refreshTokenHash, previous.getRefreshExpiresAt(),
                clientIp, userAgent));
    }

    private UserSession write(UUID id, UUID familyId, Long userId,
                              Instant issuedAt, Instant expiresAt,
                              String refreshTokenHash, Instant refreshExpiresAt,
                              String clientIp, String userAgent) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setFamilyId(familyId);
        session.setUserId(userId);
        session.setIssuedAt(issuedAt);
        session.setExpiresAt(expiresAt);
        session.setRefreshTokenHash(refreshTokenHash);
        session.setRefreshExpiresAt(refreshExpiresAt);
        session.setClientIp(truncate(clientIp, CLIENT_IP_MAX));
        session.setUserAgent(truncate(userAgent, USER_AGENT_MAX));
        return sessionRepository.save(session);
    }

    /**
     * Ends the login the given session belongs to - that session and every other one rotated out of
     * the same original login.
     *
     * <p><strong>The family, not the one session, and that is a correction rather than a widening.</strong>
     * This used to revoke a single row, which was the same thing while a login <em>was</em> a single
     * session. Since V21 it is a chain, so revoking one row would have left the refresh token that
     * mints the next access token alive: logging out would not have logged the caller out, and the
     * tests asserting that it did would have kept passing, because they log in once and never refresh.
     *
     * <p>A second call is a no-op, not an error.
     */
    @Transactional
    public void revokeLogin(UUID sessionId, RevocationReason reason) {
        int ended = sessionRepository.revokeFamilyOfSession(sessionId, Instant.now(), reason.name());
        if (ended > 0) {
            log.info("Revoked {} session(s) in the family of session {} ({})", ended, sessionId, reason);
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
     * Ends every live session descended from one login.
     *
     * <p>Two callers, for two different reasons. Logout uses it because a person logging out means the
     * login ends, not the 30-minute slice of it they happen to be holding - after a few refreshes those
     * are different things. Reuse detection uses it because it cannot tell the thief from the owner, so
     * the only safe answer is that neither of them keeps the session.
     *
     * @return how many were ended
     */
    @Transactional
    public int revokeFamily(UUID familyId, RevocationReason reason) {
        int ended = sessionRepository.revokeFamily(familyId, Instant.now(), reason.name());
        if (ended > 0) {
            log.info("Revoked {} session(s) in family {} ({})", ended, familyId, reason);
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
