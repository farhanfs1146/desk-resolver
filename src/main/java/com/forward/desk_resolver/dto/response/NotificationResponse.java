package com.forward.desk_resolver.dto.response;

import com.forward.desk_resolver.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * One notification in the caller's own inbox.
 *
 * <p><strong>There is no rendered message.</strong> The type, the ticket and the two values are
 * reported; the sentence is the client's to write. That is not an omission - a sentence stored in a
 * row cannot be reworded, corrected or translated afterwards, and {@code auth.users.full_name} has
 * no unique constraint, so "Assigned to Farhan Ali" is ambiguous in a way {@code actorId} is not.
 * {@code V22} records the decision.
 *
 * <p>Unlike {@code TicketCommentResponse} and {@code TicketHistoryResponse}, which report a person by
 * name alone, this carries {@code actorId} <em>and</em> {@code actorFullName}. The difference is what
 * each is for: those render a historical record, where a name is all a reader needs, while a client
 * reading this has to decide whether the actor is the reader - and comparing names cannot answer
 * that when two colleagues share one.
 */
@Data
@Builder
public class NotificationResponse {

    private Long id;

    @Schema(description = "What happened, written from the recipient's point of view",
            example = "TICKET_ASSIGNED_TO_YOU")
    private NotificationType type;

    @Schema(description = "The ticket this is about")
    private Long ticketId;

    /**
     * The ticket's number and title as they were when this was raised.
     *
     * <p>A copy, not a join. Neither can be changed through this API, so the snapshot cannot drift -
     * and it keeps a notification readable by somebody who has since been reassigned away from the
     * ticket, which is the right outcome: they are being shown what they were already told.
     */
    @Schema(example = "TKT-000042")
    private String ticketNumber;

    private String ticketTitle;

    @Schema(description = "The previous value, for the types where that is the news - the old status, "
            + "or the previous assignee's name. Null otherwise.")
    private String oldValue;

    @Schema(description = "The new value. Null for the types that carry their whole meaning in the type.")
    private String newValue;

    @Schema(description = "Who caused it. Null when nobody did - a passed deadline has no actor.")
    private Long actorId;

    @Schema(description = "Full name of the actor, or null when there is none", example = "Farhan Ali")
    private String actorFullName;

    private Instant createdAt;

    @Schema(description = "When the recipient read it, or null while it is unread")
    private Instant readAt;
}
