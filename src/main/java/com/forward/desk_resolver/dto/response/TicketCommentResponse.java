package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One message in a ticket's thread. */
@Data
@Builder
public class TicketCommentResponse {

    private Long id;

    @Schema(description = "The message body")
    private String comment;

    /**
     * The author's full name, with no user id alongside it.
     *
     * <p>Consistent with {@code TicketHistoryResponse.changedBy} and
     * {@code TicketResponse.raisedBy}, and for the same reason: a name is what a thread displays, and
     * a user id is what {@code GET /api/users/{id}} takes.
     */
    @Schema(description = "Full name of the user who wrote it", example = "Farhan Ali")
    private String commentedBy;

    private LocalDateTime createdAt;
}
