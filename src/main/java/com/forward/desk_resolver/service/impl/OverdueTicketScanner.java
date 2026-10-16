package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.enums.NotificationType;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.repository.NotificationRepository;
import com.forward.desk_resolver.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notices tickets whose deadline has passed, and tells whoever can act on them.
 *
 * <p><strong>Why this needs a scheduler at all.</strong> Every other notification is caused by
 * somebody doing something, so the code that performs the action is also the place that knows a
 * notification is owed. Nobody causes a deadline to pass. Without a job that looks, "overdue" is only
 * ever a state the UI can compute while somebody happens to be looking at the ticket - which is the
 * one moment it does not need pointing out.
 *
 * <p><strong>Scanning rather than scheduling per ticket.</strong> The alternative is a timer per
 * deadline, which means durable timers surviving restarts, cancellation when a deadline moves or a
 * ticket is resolved, and a scheduler that holds state. A query is one statement against an indexed
 * column and has no state to get wrong. The cost is latency: a ticket is announced up to one scan
 * interval after it goes late, which for a business deadline is not a meaningful delay.
 *
 * <p><strong>Idempotency is the whole design.</strong> The scan sees the same overdue ticket on every
 * run for as long as it stays overdue, so the thing that must be right is not finding them but not
 * announcing them twice. The event key carries the deadline, so one ticket passing one deadline is
 * one event however many times it is seen - and changing the deadline correctly makes it a new one.
 *
 * <p>Keys already present are filtered out <em>before</em> inserting rather than letting
 * {@code uq_notifications_recipient_event} reject the duplicates: a constraint violation would roll
 * back the whole transaction and take the tickets this run had not reached yet with it, so a single
 * already-notified ticket would stop the scan making progress for ever.
 *
 * <p><strong>Not safe to run on two instances at once.</strong> Both would read the same candidates,
 * both would see the same empty set of existing keys, and the second to commit would fail on the
 * unique constraint - so one scan would succeed and the other would roll back having done nothing.
 * Nothing is duplicated and nothing is lost, but a scan is wasted, and under contention it is
 * possible for neither to make progress. Before running more than one instance this needs a lock -
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} over the candidates, or ShedLock around the method. It is
 * stated rather than fixed because this application has no multi-instance deployment to test it in,
 * and an untested lock is a worse answer than a documented limit.
 */
@Component
@RequiredArgsConstructor
public class OverdueTicketScanner {

    private static final Logger log = LoggerFactory.getLogger(OverdueTicketScanner.class);

    /**
     * Work that cannot be late any more, whatever its deadline says.
     *
     * <p>Defined on {@link TicketStatus#settled()} rather than here, because the ticket statistics
     * endpoint asks the same question and two copies of this set would eventually disagree. The
     * reasoning for the two members it has is recorded there.
     */
    private static final Set<TicketStatus> SETTLED = TicketStatus.settled();

    /**
     * How many tickets one scan will handle.
     *
     * <p>A cap, not a limit on how many can be announced. The first scan after this feature ships
     * meets the whole existing backlog at once, and the honest shape of that is several bounded
     * transactions rather than one unbounded one holding locks on every overdue ticket in the
     * database. Whatever a run does not reach is picked up by the next, oldest deadline first,
     * because the query orders by it.
     */
    private static final int BATCH_SIZE = 500;

    private final TicketRepository ticketRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationFanOut notificationFanOut;

    /**
     * {@code initialDelayString} as well as {@code fixedDelayString}, matching
     * {@code SessionService.purgeExpiredSessions}: without it the scan runs during startup, when
     * nothing has asked for anything yet and a slow first query delays readiness.
     */
    @Scheduled(fixedDelayString = "${app.notifications.overdue-scan-interval:PT1H}",
            initialDelayString = "${app.notifications.overdue-scan-interval:PT1H}")
    @Transactional
    public void scanForOverdueTickets() {
        int announced = scanOnce(Instant.now());
        if (announced > 0) {
            log.info("Announced {} newly overdue ticket(s)", announced);
        }
    }

    /**
     * One pass, at a caller-supplied instant.
     *
     * <p>Public and parameterised so a test can run a scan without waiting an hour and without
     * reaching into the clock. {@code @Transactional} is on both this and the scheduled method, so a
     * test calling it directly still gets the same atomicity the job does.
     *
     * @return how many tickets were announced - not how many are overdue
     */
    @Transactional
    public int scanOnce(Instant now) {
        List<Ticket> candidates =
                ticketRepository.findOverdue(now, SETTLED, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<Long, String> keysByTicket = new LinkedHashMap<>();
        for (Ticket ticket : candidates) {
            keysByTicket.put(ticket.getId(), overdueKey(ticket));
        }

        Set<String> alreadyAnnounced =
                new HashSet<>(notificationRepository.findExistingEventKeys(keysByTicket.values()));

        List<Ticket> fresh = new ArrayList<>();
        for (Ticket ticket : candidates) {
            if (!alreadyAnnounced.contains(keysByTicket.get(ticket.getId()))) {
                fresh.add(ticket);
            }
        }

        for (Ticket ticket : fresh) {
            notificationFanOut.onTicketOverdue(ticket, keysByTicket.get(ticket.getId()));
        }
        return fresh.size();
    }

    /**
     * The deadline, not the moment of discovery.
     *
     * <p>This is what makes the scan idempotent: every run computes the same key for the same ticket
     * and the same deadline, so the second run finds it already present. Including the deadline - and
     * not just the ticket id - is what makes a <em>moved</em> deadline a new event, which it is:
     * somebody promised a new date and missed that too.
     *
     * <p>Epoch milliseconds rather than the {@code Instant}'s string form, so the key cannot change
     * because of how a timestamp renders.
     */
    private static String overdueKey(Ticket ticket) {
        return NotificationType.TICKET_OVERDUE.name()
                + ':' + ticket.getId()
                + ':' + ticket.getExpectedBy().toEpochMilli();
    }
}
