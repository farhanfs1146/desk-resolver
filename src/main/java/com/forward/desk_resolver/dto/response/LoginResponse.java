package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A successful authentication result.
 *
 * <p>Carries no password, no hash and no security metadata beyond what a client needs in order to use
 * and renew the token.
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
 */
@Data
@Builder
public class LoginResponse {

    @Schema(description = "Signed access token; send as 'Authorization: Bearer <token>'")
    private String accessToken;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Seconds until the token expires", example = "1800")
    private long expiresIn;

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
