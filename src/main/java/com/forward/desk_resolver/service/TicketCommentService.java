package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.request.CreateTicketCommentRequest;
import com.forward.desk_resolver.dto.response.TicketCommentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * A ticket's discussion thread.
 *
 * <p>Separate from {@code TicketService} rather than six more methods on it. Comments are their own
 * resource with their own permissions, and the two services share the one thing they must not
 * duplicate - {@code TicketVisibilityGuard} - through that component rather than through inheritance
 * or a copied predicate.
 *
 * <p>There is no update and no delete. See {@code TicketComment} for why.
 */
public interface TicketCommentService {

    /**
     * Appends a comment to a ticket.
     *
     * @throws com.forward.desk_resolver.common.exception.ResourceNotFoundException if the ticket does
     *         not exist or the caller may not see it - the same 404 for both
     */
    TicketCommentResponse addComment(Long ticketId, CreateTicketCommentRequest request);

    /** One page of the thread, oldest first by default, because a conversation reads that way. */
    Page<TicketCommentResponse> getComments(Long ticketId, Pageable pageable);
}
