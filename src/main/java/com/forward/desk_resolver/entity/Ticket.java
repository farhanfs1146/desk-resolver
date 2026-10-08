package com.forward.desk_resolver.entity;

import com.forward.desk_resolver.enums.IssueType;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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

    @Column(name = "business_impact", length = 50)
    private String businessImpact;

    @Column(name = "expected_by")
    private LocalDateTime expectedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

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
