package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.common.web.PageRequests;
import com.forward.desk_resolver.common.web.TicketHistoryPageRequests;
import com.forward.desk_resolver.common.web.TicketPageRequests;
import com.forward.desk_resolver.dto.request.CreateTicketRequest;
import com.forward.desk_resolver.dto.request.TicketFilter;
import com.forward.desk_resolver.dto.request.UpdateTicketStatusRequest;
import com.forward.desk_resolver.dto.response.TicketHistoryResponse;
import com.forward.desk_resolver.dto.response.TicketResponse;
import com.forward.desk_resolver.dto.response.TicketStatsResponse;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.service.TicketService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    /** Answers 201 with the created ticket, including its generated ticket number (audit P2-9). */
    @PreAuthorize("hasAuthority('TICKET_CREATE')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public TicketResponse createTicket(@Valid @RequestBody CreateTicketRequest request) {
        return ticketService.createTicket(request);
    }

    /**
     * Readable by anyone involved in the ticket, or by a caller holding TICKET_READ_ALL.
     * The involvement check is resource-level and lives in the service, which can see the ticket.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable Long id) {
        return ticketService.getTicketById(id);
    }

    /**
     * Aggregate ticket counts for a dashboard.
     *
     * <p><strong>Mapped above {@code /{id}} in this file, but the order does not matter here</strong>
     * - Spring matches the more specific pattern first, so a literal {@code /stats} wins over the
     * {@code /{id}} template regardless of declaration order. It is written next to the list endpoint
     * because that is what it summarises, not to influence matching. (A client asking for
     * {@code /api/tickets/stats} before this existed got the 400 that parsing "stats" as a Long
     * produces, not a 404 - so nothing was relying on the old behaviour.)
     *
     * <p>Guarded by {@code TICKET_READ_OWN}, the same floor as the list, and the service applies the
     * same ownership restriction for a caller without {@code TICKET_READ_ALL}. A requester therefore
     * sees counts of their own tickets and cannot read the size of the table. The response says which
     * of the two it is in {@code scopedToMe}.
     *
     * <p>No new permission was introduced for this. One would have had to be added to
     * {@code Permission} and to {@code auth.permissions} in a migration, and granted to every role
     * that already holds a ticket-read permission - a lot of moving parts to express "may read counts
     * of the tickets you may already read", which is not a separate capability.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping("/stats")
    public TicketStatsResponse getStats() {
        return ticketService.getTicketStats();
    }

    /**
     * Paged, filtered, sorted ticket list.
     *
     * <p><strong>Response body shape is unchanged:</strong> still a JSON array of
     * {@code TicketResponse}. What changed is that it is now a bounded page rather than the entire
     * table, and that pagination metadata travels in headers ({@code X-Total-Count},
     * {@code X-Total-Pages}, {@code X-Page-Number}, {@code X-Page-Size}, {@code X-Has-Next}).
     *
     * <p>Headers were chosen over a wrapper object specifically to keep the body parseable by existing
     * clients. The behavioural change is unavoidable - an unbounded list was the defect - but a client
     * that ignores the new parameters now receives the newest
     * {@value PageRequests#DEFAULT_PAGE_SIZE} tickets instead of every row.
     *
     * <p><strong>Search is the {@code q} parameter here, not a {@code /search} endpoint.</strong> A
     * separate endpoint would have had to grow its own copy of the paging, the sorting, the status and
     * priority filters and - the part that matters - the ownership restriction, because a search that
     * cannot be narrowed or paged is not useful and a search that forgets the restriction is a way to
     * read other people's tickets. Searching is a filter over the same collection, so it is a
     * parameter on the same endpoint, and every one of those properties is inherited rather than
     * reimplemented.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping
    public ResponseEntity<List<TicketResponse>> getAll(
            @Parameter(description = "Filter by ticket status")
            @RequestParam(required = false) TicketStatus status,
            @Parameter(description = "Filter by priority")
            @RequestParam(required = false) Priority priority,
            @Parameter(description = "Filter by the assigned user's id")
            @RequestParam(required = false) Long assignedTo,
            @Parameter(description = "Filter by the raising user's id")
            @RequestParam(required = false) Long raisedBy,
            @Parameter(description = "Free-text search over ticket number, title and description. "
                    + "Case-insensitive substring match; '%' and '_' are matched literally, not as "
                    + "wildcards. Blank or whitespace-only means no search. ANDed with the other "
                    + "filters, so it narrows them rather than replacing them.")
            @RequestParam(required = false) String q,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. "
                    + "Allowed: createdAt, updatedAt, priority, status, ticketNumber, id. "
                    + "'priority' orders by severity (LOW < MEDIUM < HIGH < CRITICAL), so "
                    + "'priority,desc' is worst-first. 'status' groups equal statuses together but "
                    + "is not lifecycle order - no lifecycle is defined yet.")
            @RequestParam(required = false) String sort
    ) {
        Pageable pageable = TicketPageRequests.of(page, size, sort);
        TicketFilter filter = new TicketFilter(status, priority, assignedTo, raisedBy, q);

        Page<TicketResponse> result = ticketService.searchTickets(filter, pageable);

        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    /**
     * One page of a ticket's audit trail, newest first.
     *
     * <p>Guarded by {@code TICKET_READ_OWN} exactly like {@code GET /api/tickets/{id}}, and the
     * service applies the same involvement check to the ticket itself - so a caller who cannot read a
     * ticket cannot read its history either, and gets the same 404 rather than an empty page.
     *
     * <p>Same shape as every other listing here: a JSON array body with pagination metadata in
     * {@code X-Total-Count}, {@code X-Total-Pages}, {@code X-Page-Number}, {@code X-Page-Size} and
     * {@code X-Has-Next}.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping("/{id}/history")
    public ResponseEntity<List<TicketHistoryResponse>> getHistory(
            @PathVariable Long id,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. Allowed: changedAt, id")
            @RequestParam(required = false) String sort
    ) {
        Page<TicketHistoryResponse> result =
                ticketService.getTicketHistory(id, TicketHistoryPageRequests.of(page, size, sort));

        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    /**
     * {@code userId} is the assignment TARGET, never the actor. The acting user comes from the
     * authenticated principal; a client cannot nominate who performed the assignment.
     */
    @PreAuthorize("hasAuthority('TICKET_ASSIGN')")
    @PutMapping("/{ticketId}/assign/{userId}")
    public TicketResponse assignTicket(
            @PathVariable Long ticketId,
            @PathVariable Long userId
    ) {
        return ticketService.assignTicket(ticketId, userId);
    }

    /**
     * Moves a ticket to a new status, optionally recording why.
     *
     * <p>Requires the status-change permission outright. Whether a requester should be able to move
     * their own ticket - closing it once satisfied, for instance - is a business rule this codebase does
     * not define, so the safe direction is to withhold it. See docs/DECISIONS.md.
     *
     * <p><strong>Two ways to supply the status, on purpose.</strong> The endpoint originally took only
     * {@code ?status=}, which left nowhere to put the note that {@code ticket_history_tracking.remarks}
     * has had a column for since V7 - so every audit entry carried the same fixed string. A JSON body
     * carrying {@code status} and {@code remarks} is now the preferred form:
     *
     * <pre>
     *   PATCH /api/tickets/5/status
     *   {"status":"RESOLVED","remarks":"Fixed in build 412"}
     * </pre>
     *
     * <p>The query parameter still works and still means exactly what it did, because removing it would
     * break every client already using it for no gain. When both are supplied the body wins, since it is
     * the form that can carry more. When neither is, the response is the same 400 as before.
     *
     * <p>Transitions are still not validated - any status may follow any other. That remains an open
     * business decision rather than an oversight; see docs/DECISIONS.md.
     */
    @PreAuthorize("hasAuthority('TICKET_STATUS_CHANGE')")
    @PatchMapping("/{ticketId}/status")
    public TicketResponse updateStatus(
            @PathVariable Long ticketId,
            @Parameter(description = "Legacy form; prefer the request body, which can also carry remarks")
            @RequestParam(required = false) TicketStatus status,
            @RequestBody(required = false) @Valid UpdateTicketStatusRequest request
    ) throws MissingServletRequestParameterException {

        TicketStatus target = request != null && request.getStatus() != null
                ? request.getStatus()
                : status;

        if (target == null) {
            // Thrown rather than hand-rolled so this keeps answering through the handler that already
            // exists for a missing parameter: same 400, same title, same wording as when `status` was a
            // required @RequestParam. Making the parameter optional must not change what a caller who
            // supplies nothing is told.
            throw new MissingServletRequestParameterException("status", "TicketStatus");
        }

        String remarks = request == null ? null : request.getRemarks();
        return ticketService.updateStatus(ticketId, target, remarks);
    }
}
