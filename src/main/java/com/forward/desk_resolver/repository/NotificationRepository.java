package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.Notification;
import com.forward.desk_resolver.repository.projection.NotificationRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * One page of a recipient's notifications.
     *
     * <p><strong>{@code recipientId} is the whole authorization check.</strong> It is a parameter
     * rather than something applied afterwards for the obvious reason - a filter the caller could
     * omit is a filter that will eventually be omitted - and it is why these endpoints assert no
     * permission: there is no such thing as reading somebody else's inbox, so there is nothing for a
     * permission to grant.
     *
     * <p>{@code unreadOnly} is handled inside the query rather than by a second method. The predicate
     * {@code (:unreadOnly = false or n.readAt is null)} is constant-folded per execution, so both
     * forms still use {@code idx_notifications_recipient_created_at}, and the alternative - two
     * near-identical queries and two count queries - is four places for the recipient predicate to
     * live instead of one.
     *
     * <p>The join to the actor is a <strong>left</strong> join, and that is load-bearing:
     * {@code actor_id} is null for {@code TICKET_OVERDUE}, which the scheduler raises and no user
     * causes. An inner join would drop exactly those rows, and it would do it silently.
     */
    @Query(value = """
            select new com.forward.desk_resolver.repository.projection.NotificationRow(
                n.id, n.notificationType, n.ticketId, n.ticketNumber, n.ticketTitle,
                n.oldValue, n.newValue, n.actorId, actor.fullName, n.createdAt, n.readAt)
            from Notification n
                left join User actor on actor.id = n.actorId
            where n.recipientId = :recipientId
              and (:unreadOnly = false or n.readAt is null)
            """,
            countQuery = """
                    select count(n.id)
                    from Notification n
                    where n.recipientId = :recipientId
                      and (:unreadOnly = false or n.readAt is null)
                    """)
    Page<NotificationRow> findInbox(@Param("recipientId") Long recipientId,
                                    @Param("unreadOnly") boolean unreadOnly,
                                    Pageable pageable);

    /**
     * The badge count.
     *
     * <p>Requested on every poll and answered from {@code idx_notifications_unread}, which holds only
     * unread rows - so the cost of this does not grow with the inbox's history, only with what is
     * actually waiting.
     */
    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /**
     * Marks one notification read, if it belongs to this recipient and is not already read.
     *
     * <p>Both conditions are in the {@code where} clause rather than checked first, so the operation
     * is one statement and cannot race with itself. The return value is what the service reads:
     *
     * <ul>
     *   <li>{@code 1} - it was this recipient's, and unread until now;
     *   <li>{@code 0} - it does not exist, or belongs to somebody else, or was already read.
     * </ul>
     *
     * <p>The service cannot tell those three apart from this number alone, which is why it asks
     * {@code existsByIdAndRecipientId} before deciding between 404 and success. Collapsing them here
     * would mean answering 404 for a second click on the same notification.
     */
    @Modifying
    @Query("""
            update Notification n
               set n.readAt = :readAt
             where n.id = :id
               and n.recipientId = :recipientId
               and n.readAt is null
            """)
    int markRead(@Param("id") Long id,
                 @Param("recipientId") Long recipientId,
                 @Param("readAt") Instant readAt);

    /** Distinguishes "not yours / not there" from "already read" - see {@link #markRead}. */
    boolean existsByIdAndRecipientId(Long id, Long recipientId);

    /**
     * Marks every unread notification of one recipient read, and reports how many.
     *
     * <p>A bulk update rather than loading the rows: nothing about them is read on the way through,
     * and an inbox left unread for a month is an unbounded number of entities to hydrate for a
     * single column write.
     */
    @Modifying
    @Query("""
            update Notification n
               set n.readAt = :readAt
             where n.recipientId = :recipientId
               and n.readAt is null
            """)
    int markAllRead(@Param("recipientId") Long recipientId, @Param("readAt") Instant readAt);

    /**
     * Which of these event keys have already produced a notification.
     *
     * <p>Exists for the overdue scan, which is the one producer that meets the same event again on
     * every run. It asks this first and skips what it finds, rather than inserting and letting
     * {@code uq_notifications_recipient_event} reject the duplicate - a constraint violation would
     * roll back the whole scan, taking the tickets it had not reached yet with it.
     *
     * <p>The key alone is enough, without the recipient: every recipient of one event is written in
     * the same transaction, so the key is either absent or complete.
     */
    @Query("select distinct n.eventKey from Notification n where n.eventKey in :eventKeys")
    List<String> findExistingEventKeys(@Param("eventKeys") Collection<String> eventKeys);
}
