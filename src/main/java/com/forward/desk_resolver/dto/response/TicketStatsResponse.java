package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Aggregate ticket counts for a dashboard, as the API renders them.
 *
 * <p><strong>Why an endpoint rather than letting a client count.</strong> Every number here was
 * obtainable from {@code GET /api/tickets} already - by issuing one request per status, one per
 * priority and three more for the scalars, and reading {@code X-Total-Count} off each. That is
 * fourteen round trips to render one screen, each paying the authorization restriction again, and the
 * fourteen answers are not a consistent snapshot of anything. This is three queries and one request.
 *
 * <p><strong>Every count respects the caller's visibility.</strong> A caller without
 * {@code TICKET_READ_ALL} gets counts over the tickets they raised or are assigned to, exactly as the
 * list endpoint restricts their rows - so this endpoint cannot be used to learn how many tickets
 * exist. {@link #scopedToMe} states which of the two happened, because the difference changes what
 * the numbers mean and a client should not have to infer it from the permissions it was issued.
 */
@Data
@Builder
public class TicketStatsResponse {

    @Schema(description = "Every ticket the caller may see, in any status.", example = "128")
    private long total;

    @Schema(description = "Not RESOLVED and not CLOSED - work still in play.", example = "37")
    private long open;

    @Schema(description = "Past its expectedBy and still open. A ticket with no deadline, "
            + "and finished work, cannot be overdue.", example = "4")
    private long overdue;

    @Schema(description = "Open and with no assignee. Always 0 for a caller who only sees their own "
            + "tickets, since a ticket they raised is visible to them whether or not it has an "
            + "assignee - the number is a triage figure and only means anything with TICKET_READ_ALL.",
            example = "6")
    private long unassigned;

    @Schema(description = "Open and assigned to the caller - their own queue.", example = "9")
    private long assignedToMe;

    @Schema(description = "Raised by the caller, in any status. Unlike the others this one counts "
            + "settled tickets too, so resolving a request does not make it disappear from the "
            + "caller's own total.", example = "11")
    private long raisedByMe;

    @Schema(description = "Count per ticket status. Contains an entry for EVERY status the API "
            + "defines, zero included, so a client can render a complete breakdown without knowing "
            + "the enum. Keys are the enum constant names.")
    private Map<String, Long> byStatus;

    @Schema(description = "Count per priority, over OPEN work only - a year of closed criticals says "
            + "nothing about today's urgency. Contains an entry for every priority, zero included.")
    private Map<String, Long> byPriority;

    @Schema(description = "False when the caller holds TICKET_READ_ALL and these counts therefore "
            + "cover every ticket; true when they cover only the tickets the caller raised or is "
            + "assigned to. A client should word its headings from this rather than call a restricted "
            + "count 'all tickets'.", example = "false")
    private boolean scopedToMe;
}
