package com.forward.desk_resolver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a validated token into an authentication carrying this application's permissions.
 *
 * <p>Replaces {@code JwtRoleAuthoritiesConverter}, which read a {@code role} claim and looked it up in
 * a compiled static map. Authorities now come from {@code auth.user_roles} and
 * {@code auth.role_permissions} through {@link AuthContextLoader}, and the token's {@code sid} claim is
 * what the lookup starts from.
 *
 * <p><strong>Nothing authorization-bearing is read out of the token.</strong> That property is older
 * than this class and was the reason the previous converter derived authorities instead of trusting a
 * claim: an administrator's change to what a role may do applies to the next request rather than
 * waiting for every outstanding token to expire. Moving the mapping into the database makes it matter
 * more, not less.
 *
 * <h2>The four ways this refuses a token</h2>
 *
 * All four produce {@link InvalidBearerTokenException}, which the entry point renders as an identical
 * 401 problem document - the client learns that the token is unusable and nothing else.
 *
 * <ol>
 *   <li><strong>No {@code sid} claim, or not a UUID.</strong> A token minted before sessions existed,
 *       or a tampered one. It verified by signature, so it is not forged; it simply cannot be
 *       resolved, and authenticating it would mean authenticating a session nobody can revoke.
 *   <li><strong>The session is unknown, revoked or expired.</strong> This is logout, and
 *       revoke-all-on-password-change, taking effect.
 *   <li><strong>The account is deactivated.</strong> Previously a deactivated user kept working until
 *       their token expired, because {@code active} was only checked at login.
 *   <li><strong>{@code sub} does not match the session's user.</strong> Defence in depth: both values
 *       are written by this application in the same transaction, so a mismatch means something is
 *       wrong that no other check would notice.
 * </ol>
 *
 * <p>A caller whose session is valid but who holds no roles authenticates with no authorities. That is
 * a deliberate distinction - they can still call the endpoints that need only
 * {@code isAuthenticated()}, such as changing their own password, and will be refused with 403
 * everywhere else.
 */
@Component
public class DatabaseAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAuthoritiesConverter.class);

    private final AuthContextLoader authContextLoader;

    public DatabaseAuthoritiesConverter(AuthContextLoader authContextLoader) {
        this.authContextLoader = authContextLoader;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID sessionId = sessionId(jwt);

        AuthContext context = authContextLoader.load(sessionId, Instant.now())
                .orElseThrow(() -> {
                    log.debug("Rejected token for subject {}: session {} is unknown, revoked or expired",
                            jwt.getSubject(), sessionId);
                    return new InvalidBearerTokenException("The session is no longer valid");
                });

        if (!context.userActive()) {
            log.info("Rejected token for user {}: the account is deactivated", context.userId());
            throw new InvalidBearerTokenException("The session is no longer valid");
        }

        if (!String.valueOf(context.userId()).equals(jwt.getSubject())) {
            log.warn("Rejected token: subject '{}' does not match session owner {}",
                    jwt.getSubject(), context.userId());
            throw new InvalidBearerTokenException("The session is no longer valid");
        }

        return new JwtAuthenticationToken(jwt, authorities(context), jwt.getSubject());
    }

    private static UUID sessionId(Jwt jwt) {
        String claim = jwt.getClaimAsString(JwtTokenService.CLAIM_SESSION_ID);
        if (claim == null || claim.isBlank()) {
            log.debug("Rejected token for subject {}: no {} claim",
                    jwt.getSubject(), JwtTokenService.CLAIM_SESSION_ID);
            throw new InvalidBearerTokenException("The session is no longer valid");
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            log.debug("Rejected token for subject {}: {} claim is not a UUID",
                    jwt.getSubject(), JwtTokenService.CLAIM_SESSION_ID);
            throw new InvalidBearerTokenException("The session is no longer valid");
        }
    }

    /**
     * One authority per permission, plus a conventional {@code ROLE_<CODE>} per role held.
     *
     * <p>Checks throughout the application assert permissions; the role authorities exist so that
     * role-shaped expressions remain possible without re-deriving the mapping. With several roles per
     * user there are now several of them, and they are what {@code hasAnyRole(...)} would read.
     */
    private static List<GrantedAuthority> authorities(AuthContext context) {
        List<GrantedAuthority> authorities =
                new ArrayList<>(context.permissions().size() + context.roleCodes().size());
        for (Permission permission : context.permissions()) {
            authorities.add(new SimpleGrantedAuthority(permission.name()));
        }
        for (String roleCode : context.roleCodes()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
        }
        return authorities;
    }
}
