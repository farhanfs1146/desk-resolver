package com.forward.desk_resolver.entity;

import com.forward.desk_resolver.enums.BusinessImpact;
import com.forward.desk_resolver.enums.IssueType;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic locking for the Ticket aggregate (audit finding P1-4).
     *
     * <p>Ticket is the only entity this application mutates after creation, and the only one the
     * audit showed taking concurrent writes, so it is the correct - and the only - place for a
     * version column. Hibernate increments it on every update and refuses a write carrying a stale
     * version, which surfaces as OptimisticLockingFailureException and then HTTP 409.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "ticket_number", nullable = false, unique = true, length = 100)
    private String ticketNumber;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 50)
    private IssueType issueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 50)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TicketStatus status;

    /**
     * How widely the problem is felt - see {@link BusinessImpact}, which also records why this
     * stopped being 50 characters of free text.
     *
     * <p>Nullable, and stays that way: impact was always optional, and an absent one means the
     * requester did not say rather than that nothing is affected.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "business_impact", length = 50)
    private BusinessImpact businessImpact;

    /**
     * Ticket timestamps are absolute instants, not wall-clock times.
     *
     * <p><strong>They used to be {@code LocalDateTime} over {@code TIMESTAMP}</strong>, and V17
     * defended that on the grounds that ticket timestamps are "business data read by humans" while
     * only session lifetimes needed an offset. V20 reversed it, because the argument quietly assumed
     * one office and one clock: a {@code TIMESTAMP} column stores the digits {@code 14:30} and not
     * which {@code 14:30}, so the same ticket read from two branches on different offsets reports two
     * different moments and neither reader can tell. Nothing fails - the field is simply wrong about
     * when something happened, which is the one thing it exists to say.
     *
     * <p>{@code Instant} maps to Hibernate's {@code TIMESTAMP_UTC}, which PostgreSQL stores as
     * {@code TIMESTAMPTZ} - the same type {@code auth.sessions} has used since V17, so the two can now
     * be compared. Rendering in a reader's own zone is the client's job and is deliberately not done
     * here: the API states the moment, and only the browser knows who is looking at it.
     */
    @Column(name = "expected_by")
    private Instant expectedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raised_by", nullable = false)
    private User raisedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "module_name", nullable = false, length = 100)
    private String moduleName;
}
