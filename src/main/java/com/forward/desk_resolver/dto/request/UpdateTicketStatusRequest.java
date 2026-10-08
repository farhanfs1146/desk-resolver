package com.forward.desk_resolver.dto.request;

import com.forward.desk_resolver.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A status change, optionally with a note explaining it.
 *
 * <p>This DTO existed before and was deleted as dead code: the endpoint took its status from a
 * {@code ?status=} query parameter, so nothing ever bound a body. It is back because the query
 * parameter had no room for the second half of the request.
 *
 * <p><strong>Why {@code remarks} matters.</strong> {@code ticket_history_tracking.remarks} has been in
 * the schema since V7 and every audit row written so far carries a fixed string the application chose
 * ("Ticket status updated"), because no endpoint could accept anything else. So the column that was
 * meant to answer <em>why</em> a ticket moved only ever recorded <em>that</em> it moved - which the
 * {@code newValue} column already says. A note supplied here is what the trail is for.
 *
 * <p>The query parameter still works, so no existing client breaks; see
 * {@code TicketController.updateStatus}.
 */
@Data
public class UpdateTicketStatusRequest {

    @NotNull(message = "Status is required")
    @Schema(description = "The status to move the ticket to")
    private TicketStatus status;

    /**
     * Free text, and deliberately optional - requiring a note on every transition would mean every
     * caller inventing one, and "updated" repeated a thousand times is worse than nothing.
     */
    @Size(max = 500, message = "Remarks must be at most 500 characters")
    @Schema(description = "Why the status changed; recorded on the audit entry",
            example = "Fixed in build 412, awaiting user confirmation")
    private String remarks;
}
