package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.response.LoginResponse;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserSession;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.UserRoleRepository;
import com.forward.desk_resolver.security.InvalidCredentialsException;
import com.forward.desk_resolver.security.JwtTokenService;
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
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
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
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService,
                           SessionService sessionService,
                           SecurityProperties properties,
                           CurrentUserProvider currentUserProvider,
                           LoginAttemptLimiter loginAttemptLimiter,
                           ClientIpResolver clientIpResolver,
                           HttpServletRequest httpServletRequest) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
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
    public LoginResponse login(LoginRequest request) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);

        // Checked before the account is even looked up, so a refusal costs no query and no BCrypt work,
        // and cannot depend on whether the account exists.
        guard(() -> loginAttemptLimiter.checkAllowed(request.getEmail(), clientIp),
                TooManyLoginAttemptsException.class);

        try {
            LoginResponse response = authenticate(request, clientIp);
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
        sessionService.revoke(sessionId, SessionService.RevocationReason.LOGOUT);
    }

    /**
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

    private LoginResponse authenticate(LoginRequest request, String clientIp) {
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
    private LoginResponse issueSessionAndToken(User user, String clientIp) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.jwt().ttl());

        UserSession session = sessionService.open(
                user.getId(), issuedAt, expiresAt, clientIp, userAgent());

        JwtTokenService.IssuedToken token =
                jwtTokenService.issue(user, session.getId(), issuedAt, expiresAt);

        var roles = userRoleRepository.findActiveRoleCodesByUserId(user.getId());
        var permissions = userRoleRepository.findActivePermissionCodesByUserId(user.getId());

        log.info("Issued access token for user {} on session {} with roles {}",
                user.getId(), session.getId(), roles);

        return LoginResponse.builder()
                .accessToken(token.token())
                .tokenType("Bearer")
                .expiresIn(token.expiresIn())
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
}
