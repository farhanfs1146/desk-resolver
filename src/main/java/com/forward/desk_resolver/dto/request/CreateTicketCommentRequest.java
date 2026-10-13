package com.forward.desk_resolver.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A comment to append to a ticket.
 *
 * <p>One field, and no author: who is commenting comes from the access token, never from the body.
 * The same rule {@code CreateTicketRequest} follows for {@code raisedBy} - a client that could name
 * the author could attribute a comment to somebody else.
 */
@Data
public class CreateTicketCommentRequest {

    /**
     * {@code @Size} rather than a column limit: the database column is {@code TEXT}.
     *
     * <p>The cap is here so an over-long comment is a 400 naming the field, which a client can show
     * next to the box the user typed into. Without it, the only limit would be whatever the request
     * body size happens to be, and the failure would be a parse error with nothing to point at.
     */
    @NotBlank(message = "A comment is required")
    @Size(max = 4000, message = "A comment must be at most 4000 characters")
    @Schema(description = "The message to add to the ticket's thread",
            example = "Reproduced on staging with the March payroll run - screenshot attached.")
    private String comment;
}
