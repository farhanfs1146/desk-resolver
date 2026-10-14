package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.request.RefreshTokenRequest;
import com.forward.desk_resolver.dto.response.AuthenticationResponse;
import com.forward.desk_resolver.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>{@code /login} and {@code /refresh} are the only endpoints in the application reachable without
 * an access token, and for the same reason: both exist to produce one. {@code /logout} needs one,
 * because it acts on the session that token names.
 *
 * <p>"Without an access token" is not "without a credential". {@code /login} demands a password and
 * {@code /refresh} demands a 256-bit single-use token this application issued; both answer the same
 * 401 to anything else.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * The only operation that opts out of the document-wide bearer requirement.
     *
     * <p>An empty {@code @SecurityRequirements} overrides the global requirement declared in
     * {@code OpenApiConfig}, so Swagger UI shows no padlock here and does not attach a token. It is
     * annotation on the exception rather than on every rule: the global default is "needs a token",
     * matching the filter chain, and this is the one endpoint where that is untrue.
     *
     * <p>It changes the documentation only. What actually makes this endpoint reachable without a
     * token is {@code SecurityConfig.ALWAYS_PUBLIC}.
     */
    @Operation(summary = "Authenticate and obtain an access token",
            description = "Returns a signed bearer token. Send it as 'Authorization: Bearer <token>' "
                    + "on every other endpoint. Returns 401 for any credential failure.")
    @SecurityRequirements
    @PostMapping("/login")
    public AuthenticationResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Exchanges a refresh token for a new token pair.
     *
     * <p>Opts out of the document-wide bearer requirement for the same reason {@code /login} does, and
     * with a sharper edge: by the time a client calls this, the access token it holds has expired.
     * Requiring one would make the endpoint unusable in precisely the situation it exists for.
     *
     * <p>Answers <strong>200</strong> with the same body as {@code /login} - a new access token and a
     * new refresh token. The presented refresh token is dead the moment this returns, so a client
     * replaces what it stored rather than keeping both: presenting a rotated token again is taken as
     * theft and ends the whole login.
     */
    @Operation(summary = "Exchange a refresh token for a new access token",
            description = "Returns a fresh token pair without a password. The refresh token is "
                    + "single-use and rotates on every call; the presented one stops working "
                    + "immediately, and presenting it a second time revokes every session descended "
                    + "from the original login. Cannot extend a login beyond its absolute refresh "
                    + "window. Returns 401 for any unusable token, with no indication of why.")
    @SecurityRequirements
    @PostMapping("/refresh")
    public AuthenticationResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    /**
     * Ends the login this token belongs to - this session and every other one rotated out of the same
     * original login.
     *
     * <p>Only {@code isAuthenticated()}, and no permission: ending your own session is not an
     * administrative act, and it must be reachable by an account that holds no roles at all.
     *
     * <p>Answers <strong>204</strong> - there is nothing to return - and answers it whether or not the
     * session was still live. Reporting "already logged out" differently would tell somebody holding a
     * stale token whether it had recently been valid, and there is nothing the caller would do with the
     * distinction anyway.
     */
    @Operation(summary = "End the current login",
            description = "Revokes the session named by this token and every other session rotated "
                    + "out of the same login, so neither the access token nor the refresh token works "
                    + "on the next request rather than when they expire. Idempotent.")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public void logout() {
        authService.logout();
    }
}
