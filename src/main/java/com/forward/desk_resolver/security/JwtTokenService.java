package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.User;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues signed access tokens.
 *
 * <p>Delegates all signing to Spring Security's {@link JwtEncoder} (Nimbus underneath). No JWT is
 * assembled, signed or parsed by hand anywhere in this codebase - that is the part of token handling
 * where a mistake is both easy and fatal.
 *
 * <p><strong>Claims, and what is deliberately absent.</strong> The token carries {@code sub} (the
 * application user id), {@code sid} (the session this token belongs to), {@code iss}, {@code iat},
 * {@code exp}, plus {@code email} for convenience.
 *
 * <p>It carries <strong>neither authorities nor roles</strong>. Both are resolved from
 * {@code auth.user_roles} and {@code auth.role_permissions} on every request by
 * {@link DatabaseAuthoritiesConverter}, so a change to who holds what takes effect on the next request
 * instead of only after every outstanding token has expired. The {@code role} claim the previous
 * version of this class wrote is gone: a user can hold several roles now, and a claim naming one of
 * them would be both incomplete and a tempting thing for something to start trusting.
 *
 * <p>{@code sid} is what made revocation possible. It is an opaque UUID, and the session it names is
 * looked up - and checked for revocation, expiry and account status - by the same query that resolves
 * the caller's permissions.
 */
@Service
public class JwtTokenService {

    static final String CLAIM_EMAIL = "email";

    /** The session this token belongs to. See {@link DatabaseAuthoritiesConverter}. */
    static final String CLAIM_SESSION_ID = "sid";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;

    public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * @param user      the authenticated account
     * @param sessionId the session opened for this token
     * @param issuedAt  the instant recorded on the session row, so {@code iat} and
     *                  {@code sessions.issued_at} cannot disagree
     * @param expiresAt likewise for {@code exp} and {@code sessions.expires_at}: the token and the
     *                  session it names must die together, or one of them outlives its own revocation
     *                  window
     * @return a signed access token identifying the given user and session
     */
    public IssuedToken issue(User user, UUID sessionId, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_SESSION_ID, sessionId.toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(SecurityConfig.JWS_ALGORITHM).build(), claims))
                .getTokenValue();

        return new IssuedToken(token, properties.jwt().ttl().toSeconds());
    }

    /**
     * @param token     the signed JWT
     * @param expiresIn lifetime in seconds, so a client knows when to re-authenticate
     */
    public record IssuedToken(String token, long expiresIn) {
    }
}
