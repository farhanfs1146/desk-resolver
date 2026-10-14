package com.forward.desk_resolver.security;

/**
 * A refresh token could not be exchanged.
 *
 * <p>Thrown for every failure mode of {@code POST /api/auth/refresh} - a token nobody issued, one
 * already rotated, a revoked session, a chain past its absolute expiry, an account deactivated since
 * the login, and losing a concurrent race for the same token. The uniformity is the same rule
 * {@link InvalidCredentialsException} follows and exists for the same reason: a caller who learns
 * <em>which</em> of those applied learns whether a token they hold was ever real, and whether the
 * account behind it still exists. The real cause is logged at the point it is detected.
 *
 * <p><strong>Why this is a separate type rather than reusing {@code InvalidCredentialsException}.</strong>
 * Sharing it would have been less code and a worse API. {@code GlobalExceptionHandler} renders that
 * exception with a fixed {@code detail} of "Invalid email or password." - deliberately fixed, so that
 * no future message can turn the login endpoint into an enumeration oracle by accident. Refresh has
 * neither an email nor a password, so that sentence is simply untrue of it, and it is the sentence a
 * client would show somebody whose shift-long session had quietly reached its cap. They would go
 * looking for a password problem that does not exist.
 *
 * <p>So uniformity is kept <em>within</em> each endpoint, which is where the oracle risk lives, rather
 * than across endpoints, where it buys nothing. Two exception types, two fixed messages, neither of
 * them derived from a caller-supplied value.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
