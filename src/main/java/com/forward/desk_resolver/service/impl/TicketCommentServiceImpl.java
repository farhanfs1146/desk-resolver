package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.exception.InvalidReferenceException;
import com.forward.desk_resolver.common.exception.ResourceNotFoundException;
import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.dto.request.CreateTicketCommentRequest;
import com.forward.desk_resolver.dto.response.TicketCommentResponse;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.TicketComment;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.repository.TicketCommentRepository;
import com.forward.desk_resolver.repository.TicketRepository;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.projection.TicketCommentRow;
import com.forward.desk_resolver.service.TicketCommentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reads and appends ticket comments.
 *
 * <p><strong>Every method begins with the visibility guard, and that is the whole authorization
 * story at this level.</strong> {@code @PreAuthorize} on the controller settles whether the caller may
 * use the comment endpoints at all; the guard settles whether they may touch <em>this</em> ticket.
 * Neither check substitutes for the other, and the guard goes first in both directions - a caller who
 * cannot see a ticket learns nothing about it by trying to comment on it, because the 404 arrives
 * before the comment is validated against anything.
 *
 * <p><strong>Commenting does not touch the ticket.</strong> No {@code updated_at} bump, no status
 * change, and no row in {@code ticket_history_tracking}. That is deliberate and it is the reason
 * comments can be granted to requesters: a comment is a message, not a workflow event, so it cannot
 * move a ticket through states or disturb the resolution timestamps that metrics read. The thread is
 * its own append-only record, next to the audit trail rather than inside it.
 */
@Service
@RequiredArgsConstructor
public class TicketCommentServiceImpl implements TicketCommentService {

    private static final Logger log = LoggerFactory.getLogger(TicketCommentServiceImpl.class);

    private final TicketCommentRepository ticketCommentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final TicketVisibilityGuard ticketVisibilityGuard;
    private final NotificationFanOut notificationFanOut;

    @Override
    @Transactional
    public TicketCommentResponse addComment(Long ticketId, CreateTicketCommentRequest request) {
        ticketVisibilityGuard.requireVisible(ticketId);

        Long authorId = currentUserProvider.requireCurrentUserId();
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> InvalidReferenceException.of("authenticated user id", authorId));

        // findById rather than getReferenceById, which is what this was until notifications arrived.
        //
        // The insert itself still needs nothing but the foreign key, which a proxy carries. The
        // fan-out needs the row: the raiser's and assignee's ids to address, and the number and title
        // to copy into each notification. So the choice is one select here or a proxy plus a select
        // in the fan-out - the same statement either way, and one less thing to reason about.
        //
        // It stays one select, not two: raisedBy and assignedTo are LAZY to-one associations whose
        // foreign keys are on this row, so reading their ids initialises nothing.
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));

        TicketComment comment = new TicketComment();
        comment.setTicket(ticket);
        comment.setComment(request.getComment().trim());
        comment.setCommentedBy(author);
        comment.setCreatedAt(now());

        TicketComment saved = ticketCommentRepository.save(comment);
        log.info("User {} commented on ticket {} (comment {})", authorId, ticketId, saved.getId());

        // A notification, and still no audit row. The decision in this class's header is untouched:
        // a comment is a message rather than a workflow event, so it writes nothing to
        // ticket_history_tracking and does not bump updated_at. Telling the other party there is
        // something to read is not a state change - it is the message arriving.
        notificationFanOut.onCommentAdded(ticket, author);

        // Mapped from the entity rather than re-read through the projection: the author's name is
        // already loaded, and a read-back would only prove the insert flushed.
        return TicketCommentResponse.builder()
                .id(saved.getId())
                .comment(saved.getComment())
                .commentedBy(author.getFullName())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TicketCommentResponse> getComments(Long ticketId, Pageable pageable) {
        ticketVisibilityGuard.requireVisible(ticketId);

        return ticketCommentRepository.findCommentRows(ticketId, pageable)
                .map(TicketCommentServiceImpl::mapRow);
    }

    private static TicketCommentResponse mapRow(TicketCommentRow row) {
        return TicketCommentResponse.builder()
                .id(row.id())
                .comment(row.comment())
                .commentedBy(row.commentedByFullName())
                .createdAt(row.createdAt())
                .build();
    }

    /**
     * Truncated to microseconds, matching {@code TicketServiceImpl}.
     *
     * <p>PostgreSQL's {@code timestamptz} stores microseconds, so an untruncated {@code Instant.now()}
     * is rounded on write and comes back different from what was returned in the response that created
     * it.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
