package com.forward.desk_resolver.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The credential {@code POST /api/auth/refresh} takes.
 *
 * <p><strong>In the body rather than a header or a cookie, and each alternative was considered.</strong>
 *
 * <ul>
 *   <li><strong>Not {@code Authorization: Bearer}.</strong> That header already means "an access token
 *       this application signed", and the security filter chain reads it that way on every other
 *       endpoint. Putting a different kind of credential in it would mean one header carrying two
 *       incompatible things, told apart by which path it arrived on.
 *   <li><strong>Not a cookie</strong>, which is the stronger option on paper: an {@code HttpOnly}
 *       cookie cannot be read by injected JavaScript, so a successful XSS could not exfiltrate the
 *       token for later use. It is rejected here because a cookie is an <em>ambient</em> credential,
 *       and this API is bearer-only precisely so that it has none - which is the single stated
 *       justification for CSRF protection being disabled (see {@code SecurityConfig}). Introducing one
 *       cookie would mean bringing CSRF protection back, turning {@code allowCredentials} on, and
 *       losing the property that a cross-site request cannot be authenticated at all. And the
 *       protection is narrower than it sounds: an XSS that cannot read the cookie can still call the
 *       refresh endpoint from the page and use what comes back. It stops the token being carried away,
 *       not the session being used.
 *   <li><strong>Not a query parameter.</strong> URLs reach access logs, proxies and browser history.
 * </ul>
 *
 * <p>So the token travels in a POST body over TLS, which is where this application puts every other
 * credential - {@code LoginRequest.password} included.
 */
@Data
public class RefreshTokenRequest {

    /**
     * No format validation beyond presence and a length ceiling, deliberately.
     *
     * <p>A pattern check here would only let a caller learn what a real token looks like by watching
     * which malformed values get a 400 and which get a 401. Anything that is not exactly a live token
     * fails the lookup and gets the same 401 as any other unusable credential, so there is nothing a
     * stricter annotation would catch earlier that is worth telling the caller about.
     *
     * <p>The size ceiling is not validation but a cheap guard: it stops a multi-megabyte body being
     * hashed before it is rejected. A real token is 43 characters.
     */
    @NotBlank(message = "Refresh token is required")
    @Size(max = 200, message = "Refresh token must be at most 200 characters")
    @Schema(description = "The refresh token from the most recent login or refresh response",
            example = "qRk8vV0rT2wL9mJ4bN7xZ1cS6dF3hG5yP8aU0eI2oK4")
    private String refreshToken;
}
