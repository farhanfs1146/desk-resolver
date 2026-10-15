package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.response.NotificationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The caller's own notification inbox.
 *
 * <p><strong>Every method is implicitly about the current user, and none of them takes a user id.</strong>
 * That is the authorization model, not a convenience: there is no operation here that could be
 * performed on somebody else's inbox, so there is no parameter that could name one and nothing for a
 * permission to grant or withhold. The recipient comes from {@code CurrentUserProvider} on every
 * call.
 *
 * <p>It is also why this interface has no counterpart to {@code TicketVisibilityGuard}. A ticket is a
 * shared object whose visibility is a question; a notification is addressed, and the address is the
 * answer.
 *
 * <p>The write side is not here - see {@code NotificationFanOut}. The ticket services create
 * notifications and never read them; this serves {@code /api/notifications} and never creates one.
 */
public interface NotificationService {

    /**
     * One page of the caller's notifications, newest first by default.
     *
     * @param unreadOnly when true, only those not yet read
     */
    Page<NotificationResponse> getMyNotifications(boolean unreadOnly, Pageable pageable);

    /**
     * How many the caller has not read.
     *
     * <p>Its own operation rather than a header on the list, because a client polling a badge wants
     * this and nothing else - and makes that request far more often than it opens the panel.
     */
    long countMyUnread();

    /**
     * Marks one notification read.
     *
     * <p>Idempotent: marking an already-read notification read again succeeds and changes nothing. A
     * second click is not an error.
     *
     * @throws com.forward.desk_resolver.common.exception.ResourceNotFoundException if no such
     *         notification exists <em>for this caller</em> - the same 404 whether it does not exist
     *         or belongs to somebody else, for the reason {@code TicketVisibilityGuard} gives: a 403
     *         would confirm the row is there
     */
    void markRead(Long notificationId);

    /**
     * Marks everything in the caller's inbox read.
     *
     * @return how many were unread until now, so a client can confirm the badge should be zero
     */
    int markAllRead();
}
