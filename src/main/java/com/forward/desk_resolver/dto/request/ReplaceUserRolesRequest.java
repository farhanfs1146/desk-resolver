package com.forward.desk_resolver.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * The complete set of roles a user should hold afterwards.
 *
 * <p><strong>Replace, not add-and-remove.</strong> A {@code PUT} carrying the full set is idempotent
 * and leaves no room for client and server to disagree about what the user ends up with. The
 * alternative - a {@code POST} and a {@code DELETE} per role - requires the caller to know the current
 * state, and two administrators editing at once silently merge their intentions instead of one of them
 * being able to see they lost the race.
 *
 * <p>{@code @NotNull} but deliberately <em>not</em> {@code @NotEmpty}: an empty set is a legitimate
 * instruction - strip this account of every capability while leaving it able to sign in and change its
 * own password. That is how an account is wound down without being deleted, which the ticket foreign
 * keys forbid anyway. The lockout guard in the service is what stops that instruction being aimed at
 * the last administrator.
 */
@Data
public class ReplaceUserRolesRequest {

    @NotNull(message = "Roles are required; send an empty array to remove every role")
    @Schema(description = "The complete set of role codes the user should hold afterwards. "
            + "An empty array removes every role.")
    private Set<@NotBlank(message = "A role code must not be blank") String> roles;
}
