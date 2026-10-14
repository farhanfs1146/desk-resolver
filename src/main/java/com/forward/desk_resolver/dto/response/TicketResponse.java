package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * One ticket as the API renders it.
 *
 * <p><strong>Every referenced record is identified as well as named.</strong> This response used to
 * carry {@code raisedBy}, {@code assignedTo} and {@code applicationName} as bare display strings and
 * nothing else, and that was a defect rather than a simplification:
 *
 * <ul>
 *   <li><strong>Names are not unique.</strong> {@code auth.users.full_name} has no unique constraint
 *       and real directories contain repeats, so two colleagues called "Ahmed Raza" were
 *       indistinguishable in a queue - including when deciding whether a ticket was one's own.
 *       {@code applications.app_name} is explicitly non-unique too; {@code ApplicationPageRequests}
 *       appends {@code id} as a sort tiebreaker for exactly that reason.
 *   <li><strong>Nothing could be navigated to.</strong> {@code GET /api/users/{id}} takes an id, and
 *       so do {@code ?assignedTo=} and {@code ?raisedBy=} on the list endpoint. With only a name, a
 *       client wanting "other tickets by this person" had to fetch the whole user directory and match
 *       on a string - an N+1 over a list, keyed on a value that is not a key.
 * </ul>
 *
 * <p>The names stay, because they are what a list renders and resolving them client-side would be
 * that same N+1 in the other direction. Both halves cost nothing to send: the ids come from the same
 * already-joined projection row, so no query changed shape.
 */
@Data
@Builder
public class TicketResponse {

    private Long id;
    private String ticketNumber;
    private String title;
    private String description;
    private String issueType;
    private String priority;
    private String status;
    private String businessImpact;

    @Schema(description = "When the requester needs this resolved by. ISO-8601 instant, UTC.")
    private Instant expectedBy;

    @Schema(description = "ISO-8601 instant, UTC. Render it in the reader's own zone.")
    private Instant createdAt;

    private Instant updatedAt;
    private Instant resolvedAt;

    @Schema(description = "Full name of the user who raised it", example = "Farhan Ali")
    private String raisedBy;

    @Schema(description = "Id of the user who raised it. The value GET /api/users/{id} and "
            + "GET /api/tickets?raisedBy= both take.")
    private Long raisedById;

    @Schema(description = "Full name of the assignee, or null when unassigned")
    private String assignedTo;

    @Schema(description = "Id of the assignee, or null when unassigned")
    private Long assignedToId;

    @Schema(description = "Name of the application this ticket is against", example = "Payroll")
    private String applicationName;

    @Schema(description = "Id of that application. Application names are not unique, so this is the "
            + "only way to tell two identically named entries apart.")
    private Long applicationId;

    private String moduleName;
}
