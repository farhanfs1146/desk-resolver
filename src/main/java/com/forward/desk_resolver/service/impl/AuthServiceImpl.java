package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.request.RefreshTokenRequest;
import com.forward.desk_resolver.dto.response.AuthenticationResponse;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserSession;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.UserRoleRepository;
import com.forward.desk_resolver.repository.UserSessionRepository;
import com.forward.desk_resolver.security.InvalidCredentialsException;
import com.forward.desk_resolver.security.InvalidRefreshTokenException;
import com.forward.desk_resolver.security.JwtTokenService;
import com.forward.desk_resolver.security.RefreshTokenService;
import com.forward.desk_resolver.security.SecurityProperties;
import com.forward.desk_resolver.security.SessionService;
import com.forward.desk_resolver.security.ratelimit.ClientIpResolver;
import com.forward.desk_resolver.security.ratelimit.LoginAttemptLimiter;
import com.forward.desk_resolver.security.ratelimit.TooManyLoginAttemptsException;
import com.forward.desk_resolver.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Email/password authentication against {@code auth.users}.
 *
 * <p>Three properties worth noting:
 *
 * <ul>
 *   <li><strong>Uniform failure.</strong> Unknown email, wrong password, no password set and
 *       deactivated account all produce the same {@link InvalidCredentialsException}. The specific
 *       cause is logged, never returned.
 *   <li><strong>No timing shortcut.</strong> When no user or no hash is found the encoder still runs
 *       against a dummy hash, so a request for a nonexistent account costs roughly the same as one for
 *       a real account. Returning early would let an attacker enumerate accounts by response time.
 *   <li><strong>The password never leaves this method.</strong> It is not logged, not stored, and not
 *       echoed in any response.
 * </ul>
 *
 * <p><strong>A successful login now writes a row</strong> - the session the token belongs to - so this
 * service is no longer read-only. The session and the token are built from one reading of the clock
 * and one computed expiry, so {@code sessions.expires_at} and the token's {@code exp} cannot drift
 * apart; see {@link #issueSessionAndToken}.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final SessionService sessionService;
    private final SecurityProperties properties;
    private final CurrentUserProvider currentUserProvider;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final ClientIpResolver clientIpResolver;
    /** Request-scoped proxy; used only to read the peer address and user agent. */
    private final HttpServletRequest httpServletRequest;

    /**
     * A hash of a random value nobody knows, verified against when the account does not exist so that
     * the request still costs a full password verification. Comparing any input against it fails.
     *
     * <p><strong>Produced by the injected encoder rather than written as a literal, deliberately.</strong>
     * The previous constant was a hand-written string that was one character too long to be a valid
     * BCrypt hash. {@code BCryptPasswordEncoder.matches} rejects a malformed hash on a regex before it
     * does any hashing, so it returned false immediately: the timing equalisation this field exists for
     * was not happening at all, and every login for an unknown email logged a warning. Deriving the
     * value from the encoder removes both the possibility of a malformed literal and a second, quieter
     * failure mode - a literal pinned at cost factor 10 stops matching the real verification cost the
     * moment the encoder's strength is raised.
     *
     * <p>Costs one BCrypt computation at startup, which is the point: it is the same computation a real
     * verification performs.
     */
    private final String dummyHash;

    public AuthServiceImpl(UserRepository userRepository,
                           UserRoleRepository userRoleRepository,
                           UserSessionRepository sessionRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService,
                           RefreshTokenService refreshTokenService,
                           SessionService sessionService,
                           SecurityProperties properties,
                           CurrentUserProvider currentUserProvider,
                           LoginAttemptLimiter loginAttemptLimiter,
                           ClientIpResolver clientIpResolver,
                           HttpServletRequest httpServletRequest) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.sessionService = sessionService;
        this.properties = properties;
        this.currentUserProvider = currentUserProvider;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.clientIpResolver = clientIpResolver;
        this.httpServletRequest = httpServletRequest;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public AuthenticationResponse login(LoginRequest request) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);

        // Checked before the account is even looked up, so a refusal costs no query and no BCrypt work,
        // and cannot depend on whether the account exists.
        guard(() -> loginAttemptLimiter.checkAllowed(request.getEmail(), clientIp),
                TooManyLoginAttemptsException.class);

        try {
            AuthenticationResponse response = authenticate(request, clientIp);
            guard(() -> loginAttemptLimiter.recordSuccess(request.getEmail(), clientIp), null);
            return response;
        } catch (InvalidCredentialsException e) {
            guard(() -> loginAttemptLimiter.recordFailure(request.getEmail(), clientIp), null);
            throw e;
        }
    }

    @Override
    @Transactional
    public void logout() {
        UUID sessionId = currentUserProvider.requireCurrentSessionId();
        sessionService.revokeLogin(sessionId, SessionService.RevocationReason.LOGOUT);
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>There is no {@code @Transactional} on this method, and that is load-bearing.</strong>
     * Adding one would look like an improvement and would silently disable reuse detection. The reuse
     * branch revokes the family and <em>then throws</em>, so under a method-level transaction the
     * revocation would roll back with the exception: the attacker{@code 's} token would be refused once
     * and the family it came from would stay alive, which is the whole protection gone with nothing
     * failing anywhere.
     *
     * <p>So each write here is its own transaction, and each one is atomic on its own terms. The two
     * that matter:
     *
     * <ul>
     *   <li>{@code revokeFamily} is a single statement, so it commits before the throw.
     *   <li>{@code rotate} marks the old token used and inserts the successor inside one transaction,
     *       so a client can never be left holding a dead refresh token with no replacement.
     * </ul>
     *
     * <h2>Why "already used" and "lost the race" are different outcomes</h2>
     *
     * Both mean this caller does not get a token, and they are worlds apart in what they imply:
     *
     * <ul>
     *   <li><strong>The row was already marked when it was read</strong> - somebody rotated this token
     *       earlier, and here it is again. A refresh token is used exactly once, so two parties hold
     *       it. Nothing can say which of them is the owner, so the family is revoked and both have to
     *       log in. That is the intended cost: one interruption for the user, and an eight-hour
     *       credential destroyed for whoever copied it.
     *   <li><strong>The row looked usable but the conditional update lost</strong> - two requests
     *       presenting the same token arrived at once, which is a client that fired twice, not a theft.
     *       A single page can trivially do this: three requests get 401 together and three refreshes
     *       start. Revoking the family here would log people out for double-clicking, so this is a
     *       plain 401 and the session survives. The sibling request that won has a valid token, which
     *       is why a browser client must single-flight its refreshes - one in-flight refresh, with
     *       other requests queued behind it.
     * </ul>
     *
     * <p>Every failure is the same 401 problem document with the same wording, for the same reason
     * login failures are: the only thing a caller learns is that the credential is unusable.
     */
    @Override
    public AuthenticationResponse refresh(RefreshTokenRequest request) {
        String hash = refreshTokenService.hash(request.getRefreshToken());

        // Looked up whatever its state. Filtering revoked and used rows out in the query would make
        // reuse indistinguishable from a token nobody ever issued, which is the one distinction this
        // method exists to draw.
        UserSession presented = sessionRepository.findByRefreshTokenHash(hash)
                .orElseThrow(() -> {
                    log.info("Refresh rejected: the token matches no session");
                    return invalidRefreshToken();
                });

        if (presented.getRefreshUsedAt() != null) {
            log.warn("Refresh token for session {} presented a second time; revoking family {}. "
                            + "A single-use credential in two hands means one of them copied it.",
                    presented.getId(), presented.getFamilyId());
            sessionService.revokeFamily(
                    presented.getFamilyId(), SessionService.RevocationReason.REFRESH_TOKEN_REUSED);
            throw invalidRefreshToken();
        }
        if (presented.getRevokedAt() != null) {
            log.info("Refresh rejected for session {}: the session was revoked ({})",
                    presented.getId(), presented.getRevokedReason());
            throw invalidRefreshToken();
        }

        Instant now = Instant.now();
        // Null covers the sessions that predate V21: issued before refresh existed, so there is no
        // chain for them to continue. Fails closed rather than treating absent as unlimited.
        if (presented.getRefreshExpiresAt() == null || !presented.getRefreshExpiresAt().isAfter(now)) {
            log.info("Refresh rejected for session {}: the chain expired at {}",
                    presented.getId(), presented.getRefreshExpiresAt());
            throw invalidRefreshToken();
        }

        // Re-read, not taken on trust from the session row. An account deactivated an hour ago still
        // has live sessions - the per-request path rejects them by joining users.active, and this path
        // has to make the same check rather than inherit it.
        User user = userRepository.findById(presented.getUserId())
                .orElseThrow(() -> {
                    log.warn("Refresh rejected: session {} names user {}, which no longer exists",
                            presented.getId(), presented.getUserId());
                    return invalidRefreshToken();
                });
        if (!Boolean.TRUE.equals(user.getActive())) {
            log.info("Refresh rejected for user {}: the account is deactivated", user.getId());
            throw invalidRefreshToken();
        }

        return rotateSessionAndTokens(presented, user);
    }

    /**
     * Runs a limiter operation without letting a fault in it break authentication.    /**
     * Runs a limiter operation without letting a fault in it break authentication.
     *
     * <p><strong>Fail-safe policy.</strong> Throttling is protective, not authoritative: it decides
     * whether to <em>refuse</em> an attempt, never whether to <em>accept</em> one. So an unexpected fault
     * inside the limiter is logged at ERROR and swallowed, and the request proceeds to normal password
     * verification. The consequence is degraded brute-force protection, loudly reported — not an
     * authentication bypass, because the password is still verified, and not a 500 for an ordinary login,
     * because a tracking bug must not take authentication offline.
     *
     * <p>{@code TooManyLoginAttemptsException} is the limiter working correctly, so it is rethrown rather
     * than swallowed - hence the {@code expected} parameter.
     */
    private void guard(Runnable limiterOperation, Class<? extends RuntimeException> expected) {
        try {
            limiterOperation.run();
        } catch (RuntimeException e) {
            if (expected != null && expected.isInstance(e)) {
                throw e;
            }
            log.error("Login attempt limiter failed; continuing without throttling for this request. "
                    + "Brute-force protection is degraded until this is resolved.", e);
        }
    }

    private AuthenticationResponse authenticate(LoginRequest request, String clientIp) {
        Optional<User> candidate = userRepository.findByEmail(request.getEmail());

        String storedHash = candidate
                .map(User::getPasswordHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .orElse(dummyHash);

        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);

        if (candidate.isEmpty()) {
            log.info("Login rejected: no account for the supplied email");
            throw invalidCredentials();
        }
        User user = candidate.get();

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            log.info("Login rejected for user {}: no password has been set", user.getId());
            throw invalidCredentials();
        }
        if (!passwordMatches) {
            log.info("Login rejected for user {}: incorrect password", user.getId());
            throw invalidCredentials();
        }
        if (!Boolean.TRUE.equals(user.getActive())) {
            log.info("Login rejected for user {}: account is deactivated", user.getId());
            throw invalidCredentials();
        }

        return issueSessionAndToken(user, clientIp);
    }

    /**
     * Opens a session and mints the token that names it.
     *
     * <p>Order matters and is forced: the token carries the session id, so the session has to exist
     * first. {@code issuedAt} and {@code expiresAt} are computed once here and handed to both, which is
     * the only way to be sure the row and the claims describe the same lifetime - deriving the expiry
     * twice from two {@code Instant.now()} calls would leave them microseconds apart, which is harmless
     * until the day it is not.
     *
     * <p>The roles and permissions in the response are read back from the database rather than
     * assembled in memory, so what the client is told matches what the request path will decide. They
     * are for rendering only; nothing on the server reads them again.
     */
    private AuthenticationResponse issueSessionAndToken(User user, String clientIp) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.jwt().ttl());
        Instant refreshExpiresAt = issuedAt.plus(properties.refresh().ttl());

        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue();

        UserSession session = sessionService.open(
                user.getId(), issuedAt, expiresAt,
                refresh.hash(), refreshExpiresAt,
                clientIp, userAgent());

        log.info("Opened session {} for user {}", session.getId(), user.getId());

        return respond(user, session, refresh.token(), issuedAt, expiresAt, refreshExpiresAt);
    }

    /**
     * Issues the successor token pair for a refresh.
     *
     * <p>The same shape as {@link #issueSessionAndToken} with one difference that is the entire point:
     * {@code refreshExpiresAt} is <strong>not</strong> recomputed. It comes back off the row
     * {@code SessionService.rotate} wrote, which copied it unchanged from the session being replaced -
     * so the chain still dies {@code app.security.refresh.ttl} after the original login however many
     * times it rotates, and the {@code refreshExpiresIn} the client is told shrinks towards zero across
     * the day. That is what lets a UI warn somebody that their shift{@code 's} session is about to end
     * instead of discovering it on a failed save.
     *
     * <p>An empty result from {@code rotate} means a concurrent request claimed the same token first.
     * That is a 401 and nothing else - not reuse, and nothing is revoked; see {@link #refresh}.
     */
    private AuthenticationResponse rotateSessionAndTokens(UserSession previous, User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.jwt().ttl());

        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue();

        UserSession session = sessionService.rotate(
                        previous, issuedAt, expiresAt, refresh.hash(),
                        clientIpResolver.resolve(httpServletRequest), userAgent())
                .orElseThrow(AuthServiceImpl::invalidRefreshToken);

        log.info("Rotated session {} into {} for user {} in family {}",
                previous.getId(), session.getId(), user.getId(), session.getFamilyId());

        return respond(user, session, refresh.token(),
                issuedAt, expiresAt, session.getRefreshExpiresAt());
    }

    /**
     * Builds the response both entry points return.
     *
     * <p>The roles and permissions are read back from the database rather than assembled in memory, so
     * what the client is told matches what the request path will decide. They are for rendering only;
     * nothing on the server reads them again.
     *
     * <p>Both lifetimes are derived from the instants rather than from configuration.
     * {@code expiresIn} would be the same either way, but {@code refreshExpiresIn} would not: the
     * configured TTL is the length of a <em>chain</em>, and what a client needs is how much of this
     * particular chain is left. Reporting the TTL on every refresh would tell a user at hour seven that
     * they had eight hours remaining, and the warning they needed would never fire.
     */
    private AuthenticationResponse respond(User user, UserSession session, String refreshToken,
                                           Instant issuedAt, Instant expiresAt,
                                           Instant refreshExpiresAt) {
        JwtTokenService.IssuedToken token =
                jwtTokenService.issue(user, session.getId(), issuedAt, expiresAt);

        var roles = userRoleRepository.findActiveRoleCodesByUserId(user.getId());
        var permissions = userRoleRepository.findActivePermissionCodesByUserId(user.getId());

        log.info("Issued access token for user {} on session {} with roles {}",
                user.getId(), session.getId(), roles);

        return AuthenticationResponse.builder()
                .accessToken(token.token())
                .tokenType("Bearer")
                .expiresIn(Duration.between(issuedAt, expiresAt).toSeconds())
                .refreshToken(refreshToken)
                .refreshExpiresIn(Duration.between(issuedAt, refreshExpiresAt).toSeconds())
                .userId(user.getId())
                .fullName(user.getFullName())
                .roles(roles)
                .permissions(permissions)
                .build();
    }

    /** Recorded on the session for diagnostics, never trusted for anything. */
    private String userAgent() {
        return httpServletRequest == null ? null : httpServletRequest.getHeader("User-Agent");
    }

    private static InvalidCredentialsException invalidCredentials() {
        return new InvalidCredentialsException("Invalid email or password");
    }

    /**
     * The one failure the refresh endpoint has.
     *
     * <p>Unknown token, already rotated, revoked session, expired chain, deactivated account and a lost
     * concurrency race all produce this, with identical wording - the same uniform-failure rule
     * {@link #authenticate} follows, and for the same reason. A caller who learns <em>which</em> of
     * those applied learns whether a token they are holding was ever real, and whether the account
     * behind it exists. The specific cause is logged at the point it is detected and never returned.
     *
     * <p>It is its own type rather than {@code InvalidCredentialsException}, which would have rendered
     * as "Invalid email or password." - a sentence with no password in it, shown to somebody whose
     * session simply reached its cap. See {@code InvalidRefreshTokenException}.
     */
    private static InvalidRefreshTokenException invalidRefreshToken() {
        return new InvalidRefreshTokenException("Invalid or expired refresh token");
    }
}
