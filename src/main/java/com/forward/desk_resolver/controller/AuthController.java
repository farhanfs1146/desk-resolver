package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.dto.request.LoginRequest;
import com.forward.desk_resolver.dto.response.LoginResponse;
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
 * <p>{@code /login} is the only endpoint in the application reachable without a token. {@code /logout}
 * needs one, because it acts on the session that token names.
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
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Ends the session this token belongs to.
     *
     * <p>Only {@code isAuthenticated()}, and no permission: ending your own session is not an
     * administrative act, and it must be reachable by an account that holds no roles at all.
     *
     * <p>Answers <strong>204</strong> - there is nothing to return - and answers it whether or not the
     * session was still live. Reporting "already logged out" differently would tell somebody holding a
     * stale token whether it had recently been valid, and there is nothing the caller would do with the
     * distinction anyway.
     */
    @Operation(summary = "End the current session",
            description = "Revokes the session named by this token, so the token stops working on the "
                    + "next request rather than when it expires. Idempotent.")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public void logout() {
        authService.logout();
    }
}
