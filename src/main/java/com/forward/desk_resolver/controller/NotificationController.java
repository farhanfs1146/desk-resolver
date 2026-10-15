package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.common.web.NotificationPageRequests;
import com.forward.desk_resolver.common.web.PageRequests;
import com.forward.desk_resolver.dto.response.MarkAllReadResponse;
import com.forward.desk_resolver.dto.response.NotificationResponse;
import com.forward.desk_resolver.dto.response.UnreadCountResponse;
import com.forward.desk_resolver.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The caller's own notification inbox.
 *
 * <p><strong>No {@code @PreAuthorize} on any of these, and that is the one thing about this
 * controller that needs justifying.</strong> Every other controller here asserts a permission;
 * these cannot, because there is no capability to assert. The resource is defined as "mine": the
 * recipient comes from the verified token on every call and appears in the {@code where} clause of
 * every query, so the only caller who can reach a notification is the one it was addressed to.
 *
 * <p>Inventing a {@code NOTIFICATION_READ} permission would make this worse rather than more
 * consistent. A permission is something an administrator can grant, and granting it would have to
 * mean something - the only thing it could mean is reading other people's inboxes, which nobody
 * should be able to do. {@code SecurityConfig}'s {@code anyRequest().authenticated()} is the whole
 * requirement: be somebody.
 *
 * <p>Flat routes, unlike comments and attachments, which are nested under their ticket. A
 * notification is not a sub-resource of the ticket it mentions - it belongs to a person, most of a
 * reader's inbox spans many tickets, and the ticket id in the path is not what any authorization
 * decision is made against here.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Read my notifications",
            description = "Newest first. Scoped to the authenticated caller - there is no parameter "
                    + "for whose inbox to read and no permission that grants another. Pagination "
                    + "metadata is in the X-* headers.")
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getMyNotifications(
            @Parameter(description = "Only those not yet read")
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. "
                    + "Allowed: createdAt, id. Defaults to createdAt,desc")
            @RequestParam(required = false) String sort
    ) {
        Page<NotificationResponse> result = notificationService.getMyNotifications(
                unreadOnly, NotificationPageRequests.of(page, size, sort));
        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    @Operation(summary = "How many notifications I have not read",
            description = "Its own endpoint because a client polling a badge asks for this far more "
                    + "often than it opens the list, and this answers from a partial index over "
                    + "unread rows alone.")
    @GetMapping("/unread-count")
    public UnreadCountResponse getUnreadCount() {
        return new UnreadCountResponse(notificationService.countMyUnread());
    }

    @Operation(summary = "Mark one notification read",
            description = "Idempotent - a second call succeeds and changes nothing. Answers 404 for "
                    + "a notification that is not the caller's, the same as for one that does not "
                    + "exist: a 403 would confirm it is there.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PatchMapping("/{notificationId}/read")
    public void markRead(@PathVariable Long notificationId) {
        notificationService.markRead(notificationId);
    }

    /**
     * POST rather than PATCH, because there is no single resource being patched - this acts on the
     * collection, and {@code PATCH /api/notifications} would read as "change the inbox" rather than
     * "read everything in it".
     */
    @Operation(summary = "Mark everything in my inbox read",
            description = "Returns how many were unread until now, so a client can confirm the badge "
                    + "should be zero rather than refetching to find out.")
    @PostMapping("/read-all")
    public MarkAllReadResponse markAllRead() {
        return new MarkAllReadResponse(notificationService.markAllRead());
    }
}
