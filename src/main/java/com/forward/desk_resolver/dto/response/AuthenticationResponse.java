package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A successful authentication result, from {@code POST /api/auth/login} or
 * {@code POST /api/auth/refresh}.
 *
 * <p><strong>This was {@code LoginResponse}.</strong> It was renamed when refresh was added, because
 * the two endpoints return the identical thing - a fresh token pair and the caller's identity - and a
 * type named after one of its two producers would have had to be explained every time somebody read
 * the other. There is nothing a client should do differently with the two responses, and that is the
 * point: a refresh is not a weaker login, so the client state it builds is the same object either way.
 *
 * <p>Carries no password, no hash and no security metadata beyond what a client needs in order to use
 * and renew the tokens.
 *
 * <p>{@code roles} replaces the single {@code role} string this response used to carry - a user can
 * hold several roles now, so one name would be an arbitrary choice among them. {@code permissions} is
 * included alongside it because that is what a UI actually needs: a SPA deciding whether to render the
 * "assign" button wants to know whether the caller may assign, not which role they hold. Sending roles
 * alone would force every client to reimplement the role-to-permission mapping that was just moved
 * into the database, and each client's copy would go stale differently.
 *
 * <p><strong>Neither is what the server trusts.</strong> Authority always comes from the session the
 * server resolves on each request; these two fields are for rendering, and a client that forges them
 * only changes what its own user sees.
 *
 * <p><strong>Both lifetimes are reported, and they mean different things.</strong> A client needs
 * {@code expiresIn} to know when to refresh and {@code refreshExpiresIn} to know when refreshing will
 * stop working and a password is required again - which is what lets it warn somebody before their
 * shift's session ends rather than after.
 */
@Data
@Builder
public class AuthenticationResponse {

    @Schema(description = "Signed access token; send as 'Authorization: Bearer <token>'")
    private String accessToken;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Seconds until the access token expires", example = "1800")
    private long expiresIn;

    /**
     * The single-use credential that buys the next access token.
     *
     * <p><strong>Returned here and nowhere else, ever.</strong> The server stores only a SHA-256 of it
     * (see {@code RefreshTokenService}), so this response is the one and only time the value exists
     * outside the client. A client that drops it has not lost access - it has lost the ability to
     * continue without a password.
     *
     * <p>It rotates: every call to {@code /api/auth/refresh} returns a new one and makes this one
     * unusable. Presenting a rotated token is treated as theft and ends the whole login, so a client
     * must replace what it stored rather than keeping both.
     */
    @Schema(description = "Opaque single-use refresh token. Rotates on every use; the previous value "
            + "is dead immediately and presenting it again revokes the whole login.")
    private String refreshToken;

    @Schema(description = "Seconds until refreshing stops working and a password is required again. "
            + "Measured from the original login and NOT extended by refreshing.", example = "28800")
    private long refreshExpiresIn;

    @Schema(description = "The authenticated user's id")
    private Long userId;

    @Schema(description = "The authenticated user's full name")
    private String fullName;

    @Schema(description = "Codes of every role the user holds")
    private List<String> roles;

    @Schema(description = "The union of those roles' permissions. For rendering decisions only - "
            + "the server re-derives authority on every request and never reads this back.")
    private List<String> permissions;
}
