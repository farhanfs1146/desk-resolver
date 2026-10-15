package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Notification;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.NotificationType;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.service.impl.OverdueTicketScanner;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notifications: who is told what, and who is not.
 *
 * <p>Most of these assert an <em>absence</em>, and that is the point. Creating a notification is
 * easy; the whole difficulty of the feature is in not creating the wrong ones, because every
 * surplus notification trains its recipient to stop reading them. So the cases that matter are the
 * actor not hearing about their own action, a bystander not hearing at all, and one person who is
 * both raiser and assignee hearing once rather than twice.
 *
 * <p>The fan-out is exercised through the real endpoints rather than by calling
 * {@code NotificationFanOut} directly. It writes inside the caller's transaction, so "a notification
 * exists after the request" and "a notification exists" are different claims, and only the first one
 * is worth anything.
 */
class NotificationIT extends AbstractPostgresIT {

    @Autowired
    private OverdueTicketScanner overdueTicketScanner;

    // ---------------------------------------------------------------- helpers

    /** Every notification in one person's inbox, oldest first, read straight from the table. */
    private List<Notification> inboxOf(User user) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getRecipientId().equals(user.getId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    private List<NotificationType> typesFor(User user) {
        return inboxOf(user).stream().map(Notification::getNotificationType).toList();
    }

    private void changeStatus(long ticketId, String token, TicketStatus to) throws Exception {
        mockMvc.perform(authenticated(patch("/api/tickets/" + ticketId + "/status"), token)
                        .content("""
                                {"status":"%s"}""".formatted(to.name())))
                .andExpect(status().isOk());
    }

    private void assign(long ticketId, long assigneeId, String token) throws Exception {
        mockMvc.perform(authenticated(
                        put("/api/tickets/" + ticketId + "/assign/" + assigneeId), token))
                .andExpect(status().isOk());
    }

    private void comment(long ticketId, String token, String text) throws Exception {
        mockMvc.perform(authenticated(post("/api/tickets/" + ticketId + "/comments"), token)
                        .content("""
                                {"comment":"%s"}""".formatted(text)))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- fan-out

    @Nested
    @DisplayName("who gets told")
    class FanOut {

        @Test
        @DisplayName("a status change tells the requester and not the agent who made it")
        void statusChangeTellsTheRequester() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.IN_PROGRESS);

            assertThat(typesFor(raiser)).containsExactly(NotificationType.TICKET_STATUS_CHANGED);
            // The agent did it. Telling them is the single largest source of notification noise.
            assertThat(inboxOf(agent)).isEmpty();

            Notification told = inboxOf(raiser).getFirst();
            assertThat(told.getOldValue()).isEqualTo("ASSIGNED");
            assertThat(told.getNewValue()).isEqualTo("IN_PROGRESS");
            assertThat(told.getActorId()).isEqualTo(agent.getId());
            // Copied from the ticket, not joined - a reader sees what was true at the time.
            assertThat(told.getTicketNumber()).isEqualTo(ticket.getTicketNumber());
            assertThat(told.getTicketTitle()).isEqualTo(ticket.getTitle());
            assertThat(told.getReadAt()).isNull();
        }

        /**
         * One assignment, two recipients, two different types - see {@code NotificationType} for why
         * the type varies by recipient instead of the client comparing names to work it out.
         */
        @Test
        @DisplayName("an assignment tells the new assignee and the requester, differently")
        void assignmentTellsBothSides() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User admin = givenUser("ADMIN");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, null, givenApplication());

            assign(ticket.getId(), agent.getId(), tokenFor(admin));

            assertThat(typesFor(agent)).containsExactly(NotificationType.TICKET_ASSIGNED_TO_YOU);
            assertThat(typesFor(raiser)).containsExactly(NotificationType.TICKET_NOW_ASSIGNED);
            assertThat(inboxOf(admin)).isEmpty();

            assertThat(inboxOf(raiser).getFirst().getNewValue()).isEqualTo(agent.getFullName());
        }

        /**
         * The assignment also forces the status to ASSIGNED, and that is deliberately not announced
         * separately: it is a consequence of the assignment rather than a second event, and the new
         * assignee does not need telling twice about one action.
         */
        @Test
        @DisplayName("an assignment produces one notification each, not one per audit row")
        void assignmentDoesNotAlsoAnnounceItsStatusChange() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User admin = givenUser("ADMIN");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, null, givenApplication());

            assign(ticket.getId(), agent.getId(), tokenFor(admin));

            // Two audit rows were written - ASSIGNED and STATUS_CHANGED. One notification each.
            assertThat(ticketHistoryTrackingRepository.count()).isEqualTo(2);
            assertThat(inboxOf(agent)).hasSize(1);
            assertThat(inboxOf(raiser)).hasSize(1);
        }

        @Test
        @DisplayName("a reassignment tells the person who is losing the ticket")
        void reassignmentTellsThePreviousAssignee() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User admin = givenUser("ADMIN");
            User first = givenUser("IT_SUPPORT");
            User second = givenUser("DEVELOPER");
            Ticket ticket = givenTicket(raiser, first, givenApplication());

            assign(ticket.getId(), second.getId(), tokenFor(admin));

            assertThat(typesFor(second)).containsExactly(NotificationType.TICKET_ASSIGNED_TO_YOU);
            assertThat(typesFor(first)).containsExactly(NotificationType.TICKET_TAKEN_FROM_YOU);
            assertThat(typesFor(raiser)).containsExactly(NotificationType.TICKET_NOW_ASSIGNED);
        }

        @Test
        @DisplayName("reassigning to the same person announces nothing")
        void reassignmentToTheSamePersonIsNotAnEvent() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User admin = givenUser("ADMIN");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            assign(ticket.getId(), agent.getId(), tokenFor(admin));

            // Nothing changed hands, so there is nothing to tell anybody - including the raiser, who
            // would otherwise be told their ticket "is now assigned" to the person already on it.
            assertThat(notificationRepository.count()).isZero();
        }

        @Test
        @DisplayName("a comment tells the other side of the thread and nobody else")
        void commentTellsTheOtherParty() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            User bystander = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            comment(ticket.getId(), tokenFor(raiser), "Which log should I send?");

            assertThat(typesFor(agent)).containsExactly(NotificationType.TICKET_COMMENTED);
            assertThat(inboxOf(raiser)).isEmpty();
            // Holds TICKET_READ_ALL and could read the thread. Not involved, so not told: the triage
            // permission is not a subscription to every conversation in the system.
            assertThat(inboxOf(bystander)).isEmpty();
        }

        /**
         * Commenting still writes no audit row. The decision predates notifications and is untouched
         * by them - {@code TicketCommentServiceImpl} explains why a comment is a message rather than
         * a workflow event - so this guards against the notification having dragged an audit row in
         * with it.
         */
        @Test
        @DisplayName("a comment notifies without becoming an audit event")
        void commentDoesNotWriteHistory() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            // Read back rather than taken from the fixture's in-memory entity. givenTicket writes
            // Instant.now() at nanosecond precision and timestamptz keeps microseconds, so the stored
            // value differs from the one in hand before anything has touched the row - which is the
            // very rounding TicketServiceImpl.now() truncates to avoid. Comparing stored to stored
            // asks the question this test is actually about.
            Instant before = ticketRepository.findById(ticket.getId()).orElseThrow().getUpdatedAt();

            comment(ticket.getId(), tokenFor(agent), "Looking at it now.");

            assertThat(inboxOf(raiser)).hasSize(1);
            assertThat(ticketHistoryTrackingRepository.count()).isZero();
            assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getUpdatedAt())
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("a newly raised ticket wakes whoever can assign it, and only them")
        void newTicketWakesTheTriageQueue() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            User otherRequester = givenUser("EMPLOYEE");
            Application application = givenApplication();

            mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(raiser))
                            .content("""
                                    {"title":"Payslip wrong","description":"Net pay is short",
                                     "issueType":"BUG","priority":"HIGH","applicationId":%d,
                                     "moduleName":"%s"}"""
                                    .formatted(application.getId(), application.getModuleName())))
                    .andExpect(status().isCreated());

            // The one type whose recipients come from a permission rather than from the ticket -
            // an unassigned ticket has no assignee to address.
            assertThat(typesFor(agent))
                    .containsExactly(NotificationType.TICKET_AWAITING_ASSIGNMENT);
            // The raiser knows: they just raised it.
            assertThat(inboxOf(raiser)).isEmpty();
            // Holds TICKET_CREATE but not TICKET_ASSIGN, so this is not their queue.
            assertThat(inboxOf(otherRequester)).isEmpty();
        }

        @Test
        @DisplayName("an inactive agent is not told about work they cannot pick up")
        void inactiveHoldersAreNotTold() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User active = givenUser("IT_SUPPORT");
            User inactive = givenInactiveUser("IT_SUPPORT");
            Application application = givenApplication();

            mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(raiser))
                            .content("""
                                    {"title":"Report empty","description":"No rows since Monday",
                                     "issueType":"BUG","priority":"MEDIUM","applicationId":%d,
                                     "moduleName":"%s"}"""
                                    .formatted(application.getId(), application.getModuleName())))
                    .andExpect(status().isCreated());

            assertThat(inboxOf(active)).hasSize(1);
            // A notification to somebody who cannot act on it is worse than none: it sits unread in
            // an inbox nobody opens while the queue looks attended to.
            assertThat(inboxOf(inactive)).isEmpty();
        }

        /**
         * Possible through seeded data and through self-assignment, and the sort of thing that
         * produces two identical lines in a panel if the fan-out keys on the event rather than the
         * person.
         */
        @Test
        @DisplayName("somebody who is both raiser and assignee is told once, not twice")
        void oneRecipientPerEventEvenWhenBothRoles() throws Exception {
            User agent = givenUser("IT_SUPPORT");
            User admin = givenUser("ADMIN");
            Ticket ticket = givenTicket(agent, agent, givenApplication());

            changeStatus(ticket.getId(), tokenFor(admin), TicketStatus.IN_PROGRESS);

            assertThat(inboxOf(agent)).hasSize(1);
        }

        @Test
        @DisplayName("a status change that changes nothing announces nothing")
        void anIdempotentStatusChangeIsNotAnEvent() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            // Already ASSIGNED. The service answers 200 and records nothing - so there is no event,
            // and an announcement would be of a change that did not happen.
            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.ASSIGNED);

            assertThat(notificationRepository.count()).isZero();
        }
    }

    // ---------------------------------------------------------------- the inbox endpoints

    @Nested
    @DisplayName("reading my inbox")
    class Inbox {

        @Test
        @DisplayName("returns only the caller's own notifications")
        void isScopedToTheCaller() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User other = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket mine = givenTicket(raiser, agent, givenApplication());
            Ticket theirs = givenTicket(other, agent, givenApplication());

            changeStatus(mine.getId(), tokenFor(agent), TicketStatus.IN_PROGRESS);
            changeStatus(theirs.getId(), tokenFor(agent), TicketStatus.IN_PROGRESS);

            // Two notifications exist. There is no parameter for whose inbox to read, and no
            // permission that grants another - the recipient comes from the token.
            assertThat(notificationRepository.count()).isEqualTo(2);

            mockMvc.perform(authenticated(get("/api/notifications"), tokenFor(raiser)))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("X-Total-Count", "1"))
                    .andExpect(jsonPath("$[0].ticketId").value(mine.getId()))
                    .andExpect(jsonPath("$[0].type").value("TICKET_STATUS_CHANGED"))
                    .andExpect(jsonPath("$[0].actorFullName").value(agent.getFullName()))
                    .andExpect(jsonPath("$[0].readAt").doesNotExist());
        }

        @Test
        @DisplayName("newest first")
        void isNewestFirst() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.IN_PROGRESS);
            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.RESOLVED);

            mockMvc.perform(authenticated(get("/api/notifications"), tokenFor(raiser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].newValue").value("RESOLVED"))
                    .andExpect(jsonPath("$[1].newValue").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("unreadOnly hides what has been read")
        void filtersToUnread() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());

            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.IN_PROGRESS);
            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.RESOLVED);

            String token = tokenFor(raiser);
            long firstId = inboxOf(raiser).getFirst().getId();

            mockMvc.perform(authenticated(patch("/api/notifications/" + firstId + "/read"), token))
                    .andExpect(status().isNoContent());

            mockMvc.perform(authenticated(get("/api/notifications?unreadOnly=true"), token))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("X-Total-Count", "1"))
                    .andExpect(jsonPath("$[0].newValue").value("RESOLVED"));
        }

        @Test
        @DisplayName("the unread count is the badge, and it falls as things are read")
        void reportsTheUnreadCount() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenTicket(raiser, agent, givenApplication());
            String token = tokenFor(raiser);

            mockMvc.perform(authenticated(get("/api/notifications/unread-count"), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unread").value(0));

            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.IN_PROGRESS);
            changeStatus(ticket.getId(), tokenFor(agent), TicketStatus.RESOLVED);

            mockMvc.perform(authenticated(get("/api/notifications/unread-count"), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unread").value(2));

            mockMvc.perform(authenticated(post("/api/notifications/read-all"), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.markedRead").value(2));

            mockMvc.perform(authenticated(get("/api/notifications/unread-count"), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unread").value(0));
        }

        @Test
        @DisplayName("read-all touches nobody else's inbox")
        void readAllIsScopedToTheCaller() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User other = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            changeStatus(givenTicket(raiser, agent, givenApplication()).getId(),
                    tokenFor(agent), TicketStatus.IN_PROGRESS);
            changeStatus(givenTicket(other, agent, givenApplication()).getId(),
                    tokenFor(agent), TicketStatus.IN_PROGRESS);

            mockMvc.perform(authenticated(post("/api/notifications/read-all"), tokenFor(raiser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.markedRead").value(1));

            assertThat(inboxOf(other).getFirst().getReadAt()).isNull();
        }
    }

    // ---------------------------------------------------------------- marking read

    @Nested
    @DisplayName("marking one read")
    class MarkRead {

        @Test
        @DisplayName("is idempotent - a second click is not an error")
        void isIdempotent() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            changeStatus(givenTicket(raiser, agent, givenApplication()).getId(),
                    tokenFor(agent), TicketStatus.IN_PROGRESS);

            String token = tokenFor(raiser);
            long id = inboxOf(raiser).getFirst().getId();

            mockMvc.perform(authenticated(patch("/api/notifications/" + id + "/read"), token))
                    .andExpect(status().isNoContent());
            Instant firstRead = inboxOf(raiser).getFirst().getReadAt();
            assertThat(firstRead).isNotNull();

            mockMvc.perform(authenticated(patch("/api/notifications/" + id + "/read"), token))
                    .andExpect(status().isNoContent());

            // Unchanged: the first read is when they read it, and a second request does not move it.
            assertThat(inboxOf(raiser).getFirst().getReadAt()).isEqualTo(firstRead);
        }

        /**
         * 404, not 403 - the same rule {@code TicketVisibilityGuard} applies to tickets. A 403 would
         * confirm the row exists, which is the one thing worth withholding from somebody walking ids.
         */
        @Test
        @DisplayName("answers 404 for somebody else's notification")
        void refusesSomebodyElsesNotification() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User other = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            changeStatus(givenTicket(raiser, agent, givenApplication()).getId(),
                    tokenFor(agent), TicketStatus.IN_PROGRESS);

            long notMine = inboxOf(raiser).getFirst().getId();

            mockMvc.perform(authenticated(
                            patch("/api/notifications/" + notMine + "/read"), tokenFor(other)))
                    .andExpect(status().isNotFound());

            assertThat(inboxOf(raiser).getFirst().getReadAt()).isNull();
        }

        @Test
        @DisplayName("answers 404 for a notification that does not exist")
        void refusesAnUnknownId() throws Exception {
            mockMvc.perform(authenticated(
                            patch("/api/notifications/999999/read"), tokenFor(givenUser("EMPLOYEE"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------------------------------------------------------------- the overdue scan

    @Nested
    @DisplayName("the overdue scan")
    class Overdue {

        private Ticket givenOverdueTicket(User raiser, User assignee, Instant deadline) {
            Ticket ticket = givenTicket(raiser, assignee, givenApplication());
            ticket.setExpectedBy(deadline);
            return ticketRepository.save(ticket);
        }

        @Test
        @DisplayName("tells the assignee and the requester, with no actor")
        void announcesAnOverdueTicket() {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            givenOverdueTicket(raiser, agent, Instant.now().minus(2, ChronoUnit.HOURS));

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isEqualTo(1);

            assertThat(typesFor(agent)).containsExactly(NotificationType.TICKET_OVERDUE);
            assertThat(typesFor(raiser)).containsExactly(NotificationType.TICKET_OVERDUE);
            // Nobody did this, a clock did - which is why the inbox query left-joins the actor.
            assertThat(inboxOf(agent).getFirst().getActorId()).isNull();
        }

        /**
         * The property the whole design turns on. The scan meets the same overdue ticket on every run
         * for as long as it stays overdue, so not announcing it twice is the hard part, not finding
         * it.
         */
        @Test
        @DisplayName("announces one ticket once, however many times it runs")
        void isIdempotentAcrossRuns() {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            givenOverdueTicket(raiser, agent, Instant.now().minus(2, ChronoUnit.HOURS));

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isEqualTo(1);
            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isZero();
            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isZero();

            assertThat(notificationRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("a moved deadline that is missed again is a new event")
        void aNewDeadlineIsANewEvent() {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenOverdueTicket(raiser, agent,
                    Instant.now().minus(3, ChronoUnit.HOURS));

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isEqualTo(1);

            // Somebody promised a new date and missed that too. The key carries the deadline, so
            // this is a second event rather than a repeat of the first.
            ticket.setExpectedBy(Instant.now().minus(1, ChronoUnit.HOURS));
            ticketRepository.save(ticket);

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isEqualTo(1);
            assertThat(typesFor(agent)).containsExactly(
                    NotificationType.TICKET_OVERDUE, NotificationType.TICKET_OVERDUE);
        }

        @Test
        @DisplayName("an unowned overdue ticket goes to whoever can assign it")
        void anUnassignedOverdueTicketGoesToTriage() {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            givenOverdueTicket(raiser, null, Instant.now().minus(2, ChronoUnit.HOURS));

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isEqualTo(1);

            // The most urgent thing in that queue: already late and still nobody's.
            assertThat(typesFor(agent)).containsExactly(NotificationType.TICKET_OVERDUE);
            assertThat(typesFor(raiser)).containsExactly(NotificationType.TICKET_OVERDUE);
        }

        @Test
        @DisplayName("finished work is never late")
        void ignoresSettledTickets() {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket resolved = givenOverdueTicket(raiser, agent,
                    Instant.now().minus(2, ChronoUnit.HOURS));
            resolved.setStatus(TicketStatus.RESOLVED);
            ticketRepository.save(resolved);

            Ticket closed = givenOverdueTicket(raiser, agent,
                    Instant.now().minus(2, ChronoUnit.HOURS));
            // Closed without ever being RESOLVED, which is why the test is on status rather than on
            // resolved_at being set.
            closed.setStatus(TicketStatus.CLOSED);
            ticketRepository.save(closed);

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isZero();
            assertThat(notificationRepository.count()).isZero();
        }

        @Test
        @DisplayName("a deadline still ahead is not overdue, and no deadline cannot be")
        void ignoresTicketsThatAreNotLate() {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            givenOverdueTicket(raiser, agent, Instant.now().plus(2, ChronoUnit.HOURS));
            // Most tickets: expectedBy is optional on the create form.
            givenTicket(raiser, agent, givenApplication());

            assertThat(overdueTicketScanner.scanOnce(Instant.now())).isZero();
            assertThat(notificationRepository.count()).isZero();
        }

        @Test
        @DisplayName("an overdue notification reaches the inbox endpoint like any other")
        void appearsInTheInbox() throws Exception {
            User raiser = givenUser("EMPLOYEE");
            User agent = givenUser("IT_SUPPORT");
            Ticket ticket = givenOverdueTicket(raiser, agent,
                    Instant.now().minus(2, ChronoUnit.HOURS));

            overdueTicketScanner.scanOnce(Instant.now());

            mockMvc.perform(authenticated(get("/api/notifications"), tokenFor(agent)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].type").value("TICKET_OVERDUE"))
                    .andExpect(jsonPath("$[0].ticketId").value(ticket.getId()))
                    .andExpect(jsonPath("$[0].ticketNumber").value(ticket.getTicketNumber()))
                    // The left join at work: an inner one would drop every actorless row silently.
                    .andExpect(jsonPath("$[0].actorId").doesNotExist())
                    .andExpect(jsonPath("$[0].actorFullName").doesNotExist());
        }
    }
}
