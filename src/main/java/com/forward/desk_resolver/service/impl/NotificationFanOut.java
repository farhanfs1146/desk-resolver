package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.entity.Notification;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.NotificationType;
import com.forward.desk_resolver.repository.NotificationRepository;
import com.forward.desk_resolver.repository.UserRoleRepository;
import com.forward.desk_resolver.security.Permission;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one thing that happened into the notifications it owes people.
 *
 * <p>Separate from {@code NotificationServiceImpl} because the two halves have nothing in common but
 * a table. This one is called by the ticket services on the write path and never reads an inbox; that
 * one serves {@code /api/notifications} and never creates a row. A single class would have made the
 * ticket services depend on mark-as-read.
 *
 * <p><strong>No {@code @Transactional} here, deliberately.</strong> Every method is called from
 * inside one - {@code TicketServiceImpl.assignTicket}, {@code TicketCommentServiceImpl.addComment}
 * and so on - and joins it. That is the point: a notification for a status change that rolled back
 * would be announcing something that never happened, and the only way to be certain it cannot is for
 * the rows to live or die with the change itself. It is also why these methods do nothing but build
 * and save: the transaction is the user's request, so anything slow in here is latency on their
 * click.
 *
 * <p>When delivery arrives - push, email - it does <em>not</em> belong in this class. Recording is
 * part of the change; sending is not, and an SMTP call inside the transaction that assigns a ticket
 * would make a mail outage look like an assignment failure. That is an
 * {@code @TransactionalEventListener(AFTER_COMMIT)} reading these rows, and it is deliberately not
 * built yet rather than stubbed.
 *
 * <p><strong>Two rules hold for every method below.</strong> The actor is never a recipient - nobody
 * needs telling what they have just done, and self-notification is the single largest source of
 * notification noise. And a recipient appears at most once per event, because one person can be both
 * the raiser and the assignee of a ticket.
 */
@Component
@RequiredArgsConstructor
public class NotificationFanOut {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanOut.class);

    private final NotificationRepository notificationRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * A ticket was raised.
     *
     * <p>Notifies nobody when the ticket already has an assignee, which through the API it never
     * does - {@code createTicket} leaves every ticket {@code OPEN} and unassigned. The check is here
     * because this method describes an event rather than an endpoint, and a seeded or imported ticket
     * that arrives already assigned should not wake the triage queue for work that is already
     * somebody's.
     */
    public void onTicketCreated(Ticket ticket, User actor) {
        if (ticket.getAssignedTo() != null) {
            return;
        }

        Instant now = now();
        String eventKey = key(NotificationType.TICKET_AWAITING_ASSIGNMENT, ticket, now);

        List<Long> triage = userRoleRepository.findActiveHolderIdsOfPermission(
                Permission.TICKET_ASSIGN.name());

        Map<Long, NotificationType> recipients = new LinkedHashMap<>();
        for (Long holder : triage) {
            recipients.put(holder, NotificationType.TICKET_AWAITING_ASSIGNMENT);
        }

        save(recipients, ticket, actor, now, eventKey, null, null);
    }

    /**
     * A ticket was assigned, or moved from one person to another.
     *
     * <p>Three recipients, three types, one event - see {@link NotificationType} for why the type
     * varies by recipient rather than the client working it out.
     *
     * <p>The order the recipients are added in <em>is</em> the precedence, because the map keeps the
     * first type written for a given person. Somebody who raised a ticket and has now been assigned
     * it hears the more specific of the two: that it is theirs to work, not that somebody is on it.
     *
     * @param previousAssignee who held it before, or null if nobody did
     */
    public void onTicketAssigned(Ticket ticket, User actor, User previousAssignee) {
        User newAssignee = ticket.getAssignedTo();
        if (newAssignee == null) {
            return;
        }

        // Reassignment to the same person changes nothing, so it announces nothing.
        if (previousAssignee != null && previousAssignee.getId().equals(newAssignee.getId())) {
            return;
        }

        Instant now = now();
        String eventKey = key(NotificationType.TICKET_ASSIGNED_TO_YOU, ticket, now);

        Map<Long, NotificationType> recipients = new LinkedHashMap<>();
        recipients.put(newAssignee.getId(), NotificationType.TICKET_ASSIGNED_TO_YOU);
        if (previousAssignee != null) {
            recipients.putIfAbsent(previousAssignee.getId(), NotificationType.TICKET_TAKEN_FROM_YOU);
        }
        recipients.putIfAbsent(ticket.getRaisedBy().getId(), NotificationType.TICKET_NOW_ASSIGNED);

        save(recipients, ticket, actor, now, eventKey,
                previousAssignee == null ? null : previousAssignee.getFullName(),
                newAssignee.getFullName());
    }

    /**
     * The status moved.
     *
     * <p>Both sides of the ticket hear it, minus whoever did it. In practice that is usually one
     * person: support staff change the status, so the requester is told; a requester cannot, because
     * {@code TICKET_STATUS_CHANGE} is withheld from them.
     */
    public void onStatusChanged(Ticket ticket, User actor, String oldStatus, String newStatus) {
        Instant now = now();

        Map<Long, NotificationType> recipients = new LinkedHashMap<>();
        recipients.put(ticket.getRaisedBy().getId(), NotificationType.TICKET_STATUS_CHANGED);
        if (ticket.getAssignedTo() != null) {
            recipients.putIfAbsent(ticket.getAssignedTo().getId(), NotificationType.TICKET_STATUS_CHANGED);
        }

        save(recipients, ticket, actor, now,
                key(NotificationType.TICKET_STATUS_CHANGED, ticket, now),
                oldStatus, newStatus);
    }

    /**
     * Somebody wrote on the thread.
     *
     * <p>Goes to the other party, which is the whole point: a thread is a conversation between a
     * requester and whoever is working their ticket, and a reply nobody is told about is a reply
     * nobody answers.
     *
     * <p>No {@code oldValue} or {@code newValue}, and no copy of the comment. The notification says
     * that there is something to read; the thread is where it is read. Copying the text here would
     * duplicate it into a second table that no edit to the comment could ever reach - and would put
     * it in front of somebody whose access to the ticket may have changed since.
     */
    public void onCommentAdded(Ticket ticket, User author) {
        Instant now = now();

        Map<Long, NotificationType> recipients = new LinkedHashMap<>();
        recipients.put(ticket.getRaisedBy().getId(), NotificationType.TICKET_COMMENTED);
        if (ticket.getAssignedTo() != null) {
            recipients.putIfAbsent(ticket.getAssignedTo().getId(), NotificationType.TICKET_COMMENTED);
        }

        save(recipients, ticket, author, now,
                key(NotificationType.TICKET_COMMENTED, ticket, now), null, null);
    }

    /**
     * A deadline passed with the ticket unresolved.
     *
     * <p>No actor: nobody did this, a clock did. {@code actorId} stays null, which is why the inbox
     * query joins the actor with a left join.
     *
     * <p>Recipients are whoever can act on it. The assignee if there is one; the triage queue if
     * there is not, because an unowned ticket that has already missed its deadline is the most urgent
     * thing in that queue. The requester hears either way - it is their deadline.
     *
     * <p>The event key carries the deadline rather than the moment of discovery, so this is one event
     * for as long as the ticket stays overdue. {@code OverdueTicketScanner} filters on that key
     * before calling, so an hourly scan does not announce the same ticket every hour.
     */
    public void onTicketOverdue(Ticket ticket, String eventKey) {
        Instant now = now();

        Map<Long, NotificationType> recipients = new LinkedHashMap<>();
        if (ticket.getAssignedTo() != null) {
            recipients.put(ticket.getAssignedTo().getId(), NotificationType.TICKET_OVERDUE);
        } else {
            for (Long holder : userRoleRepository.findActiveHolderIdsOfPermission(
                    Permission.TICKET_ASSIGN.name())) {
                recipients.put(holder, NotificationType.TICKET_OVERDUE);
            }
        }
        recipients.putIfAbsent(ticket.getRaisedBy().getId(), NotificationType.TICKET_OVERDUE);

        save(recipients, ticket, null, now, eventKey, null,
                ticket.getExpectedBy() == null ? null : ticket.getExpectedBy().toString());
    }

    // ------------------------------------------------------------------ internals

    /**
     * Writes the rows, dropping the actor from the recipients on the way.
     *
     * <p>The actor is removed here rather than at each call site so it cannot be forgotten at one of
     * them - and so that "never notify somebody of their own action" is one line of code rather than
     * a convention.
     */
    private void save(Map<Long, NotificationType> recipients,
                      Ticket ticket,
                      User actor,
                      Instant createdAt,
                      String eventKey,
                      String oldValue,
                      String newValue) {

        if (actor != null) {
            recipients.remove(actor.getId());
        }
        if (recipients.isEmpty()) {
            return;
        }

        List<Notification> rows = new ArrayList<>(recipients.size());
        for (Map.Entry<Long, NotificationType> entry : recipients.entrySet()) {
            Notification notification = new Notification();
            notification.setRecipientId(entry.getKey());
            notification.setActorId(actor == null ? null : actor.getId());
            notification.setNotificationType(entry.getValue());
            notification.setTicketId(ticket.getId());
            notification.setTicketNumber(ticket.getTicketNumber());
            notification.setTicketTitle(ticket.getTitle());
            notification.setOldValue(oldValue);
            notification.setNewValue(newValue);
            notification.setCreatedAt(createdAt);
            notification.setEventKey(eventKey);
            rows.add(notification);
        }

        notificationRepository.saveAll(rows);
        log.debug("Notified {} recipient(s) about ticket {} ({})",
                rows.size(), ticket.getId(), eventKey);
    }

    /**
     * The event key for an event-driven notification.
     *
     * <p>The timestamp is what separates two status changes on one ticket, and a microsecond is finer
     * than two changes can be made - each is its own transaction, each takes a round trip. It is not
     * trying to be a hash of the event: these producers write inside the transaction that caused the
     * change, so a retry rolls the rows back and there is nothing to collide with. The constraint
     * earns its place on {@code TICKET_OVERDUE}, whose key is built by the scanner instead.
     */
    private static String key(NotificationType type, Ticket ticket, Instant at) {
        return type.name() + ':' + ticket.getId() + ':' + at.toEpochMilli() + ':' + at.getNano();
    }

    /** Truncated to microseconds, matching every other timestamp this application writes. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
