package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.response.LoginResponse;

public interface AuthService {

    /**
     * Authenticates an email/password pair, opens a session and issues an access token for it.
     *
     * @throws com.forward.desk_resolver.security.InvalidCredentialsException if the
     *         credentials do not match, the account has no password set, or the account is inactive
     */
    LoginResponse login(LoginRequest request);

    /**
     * Ends the session the caller is acting through.
     *
     * <p>Before {@code auth.sessions} existed, logging out was something only the client could do - drop
     * the token and hope nobody else had it - and docs/DECISIONS.md recorded that as an accepted
     * limitation. The token is now rejected on the next request.
     *
     * <p>Idempotent: logging out of an already-ended session answers 204 as well. There is nothing for
     * the caller to do differently, and distinguishing the two would tell an attacker holding a stale
     * token whether it was recently valid.
     */
    void logout();
}
