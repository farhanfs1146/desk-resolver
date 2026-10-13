package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.exception.InvalidReferenceException;
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

import java.time.LocalDateTime;
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

    @Override
    @Transactional
    public TicketCommentResponse addComment(Long ticketId, CreateTicketCommentRequest request) {
        ticketVisibilityGuard.requireVisible(ticketId);

        Long authorId = currentUserProvider.requireCurrentUserId();
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> InvalidReferenceException.of("authenticated user id", authorId));

        // getReferenceById rather than findById: the guard has already proved the row exists, and the
        // only thing the insert needs from it is the foreign key. Loading the ticket itself would be a
        // second select for a value already in hand.
        Ticket ticket = ticketRepository.getReferenceById(ticketId);

        TicketComment comment = new TicketComment();
        comment.setTicket(ticket);
        comment.setComment(request.getComment().trim());
        comment.setCommentedBy(author);
        comment.setCreatedAt(now());

        TicketComment saved = ticketCommentRepository.save(comment);
        log.info("User {} commented on ticket {} (comment {})", authorId, ticketId, saved.getId());

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
     * <p>PostgreSQL's {@code timestamp} stores microseconds, so an untruncated
     * {@code LocalDateTime.now()} is rounded on write and comes back different from what was returned
     * in the response that created it.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
