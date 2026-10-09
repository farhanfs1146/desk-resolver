package com.forward.desk_resolver.security;

import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.common.identity.MissingUserIdentityException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the acting user from the authenticated principal.
 *
 * <p>This replaces {@code RequestHeaderCurrentUserProvider}, which read an {@code X-User-Id} header.
 * That mechanism was a deliberate, fail-closed stopgap introduced in Phase 2 to stop the application
 * silently attributing everything to user id 1 - but it trusted a value the client chose, so any caller
 * could act as any user. It is now <strong>deleted</strong>, not merely bypassed: the header is no
 * longer read anywhere, so it cannot override or influence identity.
 *
 * <p><strong>No database lookup happens here.</strong> The user id and session id come from the
 * token's {@code sub} and {@code sid} claims, which the resource server has already verified by
 * signature, expiry and issuer - and which {@link DatabaseAuthoritiesConverter} has already resolved
 * against {@code auth.sessions}, refusing the request if the session was revoked, expired or belonged
 * to a deactivated account. Repeating that query here would be asking the same question twice in one
 * request. The services that genuinely need a {@code User} entity - because {@code raised_by} and
 * {@code changed_by} are foreign keys - load it themselves at that point, so the lookup happens
 * exactly where it is required and nowhere else.
 *
 * <p><strong>It fails closed.</strong> No authentication, an anonymous authentication, a principal that
 * is not a JWT, or a {@code sub} that is not a positive number all raise
 * {@link MissingUserIdentityException}. There is no default user and no fallback.
 */
@Component
public class AuthenticatedCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Long requireCurrentUserId() {
        Jwt jwt = requireJwt();

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MissingUserIdentityException("The access token carries no subject claim");
        }

        long userId;
        try {
            userId = Long.parseLong(subject.trim());
        } catch (NumberFormatException e) {
            throw new MissingUserIdentityException("The access token subject is not a user id");
        }
        if (userId <= 0) {
            throw new MissingUserIdentityException("The access token subject is not a valid user id");
        }
        return userId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read from the same verified token as the user id, and subject to the same fail-closed rule: a
     * missing or malformed {@code sid} raises rather than returning null. By the time a service calls
     * this, {@link DatabaseAuthoritiesConverter} has already resolved the session and refused the
     * request if it was not usable - so this is re-reading a claim that has been validated, not
     * trusting one that has not.
     */
    @Override
    public UUID requireCurrentSessionId() {
        Jwt jwt = requireJwt();

        String claim = jwt.getClaimAsString(JwtTokenService.CLAIM_SESSION_ID);
        if (claim == null || claim.isBlank()) {
            throw new MissingUserIdentityException("The access token carries no session claim");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException e) {
            throw new MissingUserIdentityException("The access token session claim is not a session id");
        }
    }

    private Jwt requireJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new MissingUserIdentityException("No authenticated user is present");
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new MissingUserIdentityException(
                    "The authenticated principal does not carry a verified token");
        }
        return jwt;
    }

    /**
     * {@inheritDoc}
     *
     * <p>An instance method, not a static one. The static version could not be stubbed, so a service
     * unit test had to stand up a real {@code SecurityContext} to exercise a business rule - see
     * {@link CurrentUserProvider#hasPermission}.
     */
    @Override
    public boolean hasPermission(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> permission.name().equals(granted.getAuthority()));
    }
}
