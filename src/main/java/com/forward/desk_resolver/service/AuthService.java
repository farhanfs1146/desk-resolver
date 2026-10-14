package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.request.RefreshTokenRequest;
import com.forward.desk_resolver.dto.response.AuthenticationResponse;

public interface AuthService {

    /**
     * Authenticates an email/password pair, opens a session and issues an access token for it, along
     * with the refresh token that will replace it.
     *
     * @throws com.forward.desk_resolver.security.InvalidCredentialsException if the
     *         credentials do not match, the account has no password set, or the account is inactive
     */
    AuthenticationResponse login(LoginRequest request);

    /**
     * Exchanges a refresh token for a new token pair, without a password.
     *
     * <p><strong>What this fixes.</strong> Before it existed, docs/DECISIONS.md recorded that a token
     * lasted thirty minutes and then "the client logs in again" - which reads like a minor
     * inconvenience and is not. The access token expires on a clock the user cannot see, so the 401
     * lands mid-task, typically on the request submitting a ticket description somebody spent ten
     * minutes writing. No client can recover from that: re-authenticating needs a password, and all it
     * can offer is a login form over the top of the work in progress.
     *
     * <p><strong>The token is single-use and rotates.</strong> Each call returns a new refresh token
     * and makes the presented one dead. So a client must replace what it stored, never keep both:
     * presenting a rotated token is taken as evidence that two parties hold one single-use credential
     * and ends the entire login for both of them.
     *
     * <p><strong>It cannot extend a session indefinitely.</strong> The chain expires
     * {@code app.security.refresh.ttl} after the login that began it, whatever happens in between, so
     * a password is still required once per working day.
     *
     * @throws com.forward.desk_resolver.security.InvalidRefreshTokenException if the token is unknown,
     *         already used, revoked, past the chain{@code 's} absolute expiry, or belongs to an account
     *         that has since been deactivated - all of which are one indistinguishable 401 to the
     *         caller, for the same reason login failures are
     */
    AuthenticationResponse refresh(RefreshTokenRequest request);

    /**
     * Ends the login the caller is acting through - this session and every other one rotated out of
     * the same original login.
     *
     * <p>Before {@code auth.sessions} existed, logging out was something only the client could do - drop
     * the token and hope nobody else had it - and docs/DECISIONS.md recorded that as an accepted
     * limitation. The token is now rejected on the next request.
     *
     * <p><strong>The whole family, not one session</strong>, since V21. Those were the same thing while
     * a login was a single session; now a caller several refreshes into their day holds the newest
     * session, and revoking only that one would leave the refresh token that mints the next access
     * token alive. Logging out would not have logged them out.
     *
     * <p>Idempotent: logging out of an already-ended session answers 204 as well. There is nothing for
     * the caller to do differently, and distinguishing the two would tell an attacker holding a stale
     * token whether it was recently valid.
     */
    void logout();
}
