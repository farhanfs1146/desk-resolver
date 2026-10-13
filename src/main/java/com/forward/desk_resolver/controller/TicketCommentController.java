package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.common.web.PageRequests;
import com.forward.desk_resolver.common.web.TicketCommentPageRequests;
import com.forward.desk_resolver.dto.request.CreateTicketCommentRequest;
import com.forward.desk_resolver.dto.response.TicketCommentResponse;
import com.forward.desk_resolver.service.TicketCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A ticket's discussion thread, as a sub-resource of the ticket.
 *
 * <p>Nested under {@code /api/tickets/{ticketId}} rather than exposed as {@code /api/comments},
 * because a comment has no meaning away from its ticket and because the ticket id in the path is what
 * every authorization decision is made against. A flat route would have to take the ticket id in a
 * query parameter and would make "which ticket's visibility do I check" a question the handler could
 * get wrong.
 *
 * <p>A separate controller from {@code TicketController} for the same reason the services are
 * separate: different permissions, different lifecycle, and {@code TicketController} is already the
 * largest in the project.
 */
@RestController
@RequestMapping("/api/tickets/{ticketId}/comments")
@RequiredArgsConstructor
public class TicketCommentController {

    private final TicketCommentService ticketCommentService;

    @Operation(summary = "Add a comment to a ticket",
            description = "Appends to the ticket's thread. Requires TICKET_COMMENT_CREATE and "
                    + "involvement in the ticket - a caller without TICKET_READ_ALL who did not raise "
                    + "it and is not its assignee gets 404, the same as for a ticket that does not "
                    + "exist. Comments do not change the ticket: no status change, no updated_at, no "
                    + "audit row.")
    @PreAuthorize("hasAuthority('TICKET_COMMENT_CREATE')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public TicketCommentResponse addComment(
            @PathVariable Long ticketId,
            @Valid @RequestBody CreateTicketCommentRequest request
    ) {
        return ticketCommentService.addComment(ticketId, request);
    }

    @Operation(summary = "Read a ticket's comment thread",
            description = "Oldest first by default, because a conversation reads that way - unlike "
                    + "every other list in this API. Pagination metadata is in the X-* headers.")
    @PreAuthorize("hasAuthority('TICKET_COMMENT_READ')")
    @GetMapping
    public ResponseEntity<List<TicketCommentResponse>> getComments(
            @PathVariable Long ticketId,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. "
                    + "Allowed: createdAt, id. Defaults to createdAt,asc")
            @RequestParam(required = false) String sort
    ) {
        Page<TicketCommentResponse> result =
                ticketCommentService.getComments(ticketId, TicketCommentPageRequests.of(page, size, sort));
        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }
}
