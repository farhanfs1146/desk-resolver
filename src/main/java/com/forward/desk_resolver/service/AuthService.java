package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.response.LoginResponse;

public interface AuthService {

    /**
     * Authenticates an email/password pair and issues an access token.
     *
     * @throws com.forward.desk_resolver.security.InvalidCredentialsException if the
     *         credentials do not match, the account has no password set, or the account is inactive
     */
    LoginResponse login(LoginRequest request);
}
