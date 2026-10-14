package com.forward.desk_resolver.security;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints refresh tokens and reduces them to the digest the database stores.
 *
 * <p><strong>Why the refresh token is opaque and not a JWT.</strong> The access token is a JWT because
 * it has to be verifiable without a lookup - that is the whole point of a signature. A refresh token is
 * the opposite: it must be checked against server state on every use, because single-use rotation,
 * revocation and reuse detection are all state. A signed token would therefore be looked up anyway, so
 * its claims would buy nothing and would only put the user id and expiry in a string that sits in
 * browser storage for hours. 256 bits of randomness carrying no meaning is strictly less to leak.
 *
 * <p><strong>Why a digest and not the token itself.</strong> A refresh token is a bearer credential: to
 * hold it is to be able to mint access tokens until it expires. Storing it in plaintext would mean a
 * read of {@code auth.sessions} - a backup, a log of a query, a report somebody exported - handed over
 * live sessions. What is stored cannot be presented.
 *
 * <p><strong>Why SHA-256 and not the {@code PasswordEncoder} this application already has.</strong>
 * This inverts the rule {@code auth.users.password_hash} follows, on purpose, and for two reasons:
 *
 * <ul>
 *   <li><strong>There is nothing to slow down.</strong> BCrypt is deliberately expensive because a
 *       password is low-entropy and guessable, so the attack it defends against is offline enumeration.
 *       A token from {@link SecureRandom} has 256 bits of entropy and no structure; enumerating it is
 *       not slow, it is impossible. The cost would be paid on every refresh and buy nothing.
 *   <li><strong>BCrypt cannot be looked up.</strong> It salts per row, so the same token hashes
 *       differently in every row and the matching session could only be found by verifying the
 *       candidate against every row in the table - a full scan per refresh, growing with the login
 *       history. A deterministic digest is one indexed probe.
 * </ul>
 *
 * <p>A fast digest is the wrong choice for a password and the right one here, and the difference is the
 * entropy of the input rather than anything about the algorithms.
 */
@Service
public class RefreshTokenService {

    /**
     * 32 bytes, so 256 bits.
     *
     * <p>Sized to be beyond guessing rather than to a convention. The lookup is one indexed equality
     * probe with no throttle in front of it, so the token's own length is what stands between an
     * attacker and someone else's session; at this size an exhaustive search is not a question of how
     * long it would take.
     */
    private static final int TOKEN_BYTES = 32;

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Seeded by the platform, shared, and thread-safe.
     *
     * <p>A {@code SecureRandom} per call would reseed from the operating system each time, which is
     * both slower and no stronger. {@code SecureRandom} is explicitly documented as thread-safe.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** No padding, so the token is URL- and header-safe without escaping. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * A freshly generated refresh token and the digest to store for it.
     *
     * <p>Returned as a pair so that the plaintext and its digest cannot be derived in two places and
     * drift - the caller stores {@link #hash()} and returns {@link #token()} exactly once, in the
     * response that issued it. After that response the plaintext does not exist on this server.
     *
     * @param token the value handed to the client; never logged, never stored
     * @param hash  hex SHA-256 of {@code token}; what {@code auth.sessions.refresh_token_hash} holds
     */
    public record IssuedRefreshToken(String token, String hash) {
    }

    /** Generates a new token and its digest. */
    public IssuedRefreshToken issue() {
        byte[] entropy = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(entropy);
        String token = ENCODER.encodeToString(entropy);
        return new IssuedRefreshToken(token, hash(token));
    }

    /**
     * The digest of a token a client presented, so it can be looked up.
     *
     * <p>No constant-time comparison is needed anywhere in this flow, which is worth stating because
     * its absence usually is a bug. The token is never compared against a known value in Java: it is
     * hashed and the hash is handed to an indexed database lookup, which either finds a row or does
     * not. There is no byte-by-byte comparison whose early exit could be timed.
     *
     * @throws IllegalArgumentException if the value is null or blank, so a missing token cannot be
     *                                 digested into something that accidentally matches a row
     */
    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A refresh token is required");
        }
        return HexFormat.of().formatHex(digest().digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A fresh {@link MessageDigest} per call, because the class is not thread-safe.
     *
     * <p>SHA-256 is mandated by the platform, so the checked exception cannot actually occur; it is
     * rethrown rather than swallowed because a JVM without it is not a condition this code can carry
     * on past.
     */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available on this JVM", e);
        }
    }
}
