package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.exception.ResourceNotFoundException;
import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.dto.response.NotificationResponse;
import com.forward.desk_resolver.repository.NotificationRepository;
import com.forward.desk_resolver.repository.projection.NotificationRow;
import com.forward.desk_resolver.service.NotificationService;
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
 * Reads and marks the caller's notifications.
 *
 * <p>Every method begins by resolving the caller, and that single line is the authorization for all
 * of them - see {@link com.forward.desk_resolver.service.NotificationService} for why there is
 * nothing more to check. The recipient id is then passed into the query rather than used to filter
 * what came back, so there is no version of these operations that could return somebody else's rows
 * if a predicate were dropped.
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notificationRepository;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(boolean unreadOnly, Pageable pageable) {
        Long recipientId = currentUserProvider.requireCurrentUserId();
        return notificationRepository.findInbox(recipientId, unreadOnly, pageable)
                .map(NotificationServiceImpl::mapRow);
    }

    @Override
    @Transactional(readOnly = true)
    public long countMyUnread() {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(
                currentUserProvider.requireCurrentUserId());
    }

    /**
     * Marks one read, or explains why it could not.
     *
     * <p>The update is tried first and the existence check only runs when it changed nothing. That
     * order matters: the common case - an unread notification being read - is then one statement, and
     * the extra query is paid only on the uncommon path. It is also what separates "already read"
     * from "not yours", which the update's row count alone cannot do; without the distinction a
     * second click on the same notification would answer 404.
     */
    @Override
    @Transactional
    public void markRead(Long notificationId) {
        Long recipientId = currentUserProvider.requireCurrentUserId();

        int updated = notificationRepository.markRead(notificationId, recipientId, now());
        if (updated > 0) {
            return;
        }

        if (!notificationRepository.existsByIdAndRecipientId(notificationId, recipientId)) {
            // 404 whether it does not exist or belongs to somebody else. A 403 would confirm the row
            // is there, which is the one thing worth not telling somebody probing ids.
            log.debug("User {} tried to read notification {}, which is not theirs or does not exist",
                    recipientId, notificationId);
            throw ResourceNotFoundException.of("Notification", notificationId);
        }
        // Theirs, and already read. Nothing to do and nothing wrong.
    }

    @Override
    @Transactional
    public int markAllRead() {
        Long recipientId = currentUserProvider.requireCurrentUserId();
        int updated = notificationRepository.markAllRead(recipientId, now());
        if (updated > 0) {
            log.debug("User {} marked {} notification(s) read", recipientId, updated);
        }
        return updated;
    }

    private static NotificationResponse mapRow(NotificationRow row) {
        return NotificationResponse.builder()
                .id(row.id())
                .type(row.notificationType())
                .ticketId(row.ticketId())
                .ticketNumber(row.ticketNumber())
                .ticketTitle(row.ticketTitle())
                .oldValue(row.oldValue())
                .newValue(row.newValue())
                .actorId(row.actorId())
                .actorFullName(row.actorFullName())
                .createdAt(row.createdAt())
                .readAt(row.readAt())
                .build();
    }

    /** Truncated to microseconds, matching every other timestamp this application writes. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
