package com.forward.desk_resolver.entity;

import com.forward.desk_resolver.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One notification, addressed to one person.
 *
 * <p><strong>Every reference is a raw id, not a {@code @ManyToOne}.</strong> That is the one thing
 * about this mapping worth explaining, and it is deliberate - {@link UserRole} does the same.
 *
 * <p>The reason is the write path. A new unassigned ticket fans out to everyone holding
 * {@code TICKET_ASSIGN}, and with associations each of those rows would need a managed {@code User}
 * to set - so creating five notifications would mean five selects for data the fan-out has no use
 * for. It never reads the recipient; it only needs the foreign key. The same is true of the ticket:
 * its number and title are copied into the row (see {@code V22}), so after that there is nothing
 * left to load it for.
 *
 * <p>The database still enforces every one of these as a foreign key. What is absent is the
 * mapping, not the constraint.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    /** Null for an event nobody caused - today, only a deadline passing. */
    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** Copied from the ticket, not joined. {@code V22} records why. */
    @Column(name = "ticket_number", nullable = false, length = 100)
    private String ticketNumber;

    @Column(name = "ticket_title", nullable = false, columnDefinition = "TEXT")
    private String ticketTitle;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until read. "When", not "whether" - see {@code V22}. */
    @Column(name = "read_at")
    private Instant readAt;

    /** Identifies the logical event, so one event cannot appear twice in one inbox. */
    @Column(name = "event_key", nullable = false, length = 200)
    private String eventKey;
}
