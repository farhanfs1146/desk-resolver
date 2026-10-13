package com.forward.desk_resolver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One message in a ticket's discussion thread.
 *
 * <p><strong>The table is older than this class by seventeen migrations.</strong> {@code V4} created
 * it and {@code V11} gave it foreign keys, but nothing ever mapped it: an earlier attempt existed with
 * every association commented out, which could not have been persisted against {@code NOT NULL}
 * columns, and it was deleted rather than left looking like working code. {@code V19} completes the
 * feature, and this is the mapping it was waiting for.
 *
 * <p><strong>Append-only, and there is no update or delete endpoint.</strong> A comment is a record of
 * something somebody said at a time, which is the same reason {@code ticket_history_tracking} is only
 * ever inserted into. Editing one would rewrite a conversation other people have already read and
 * answered. Nothing here forbids it at the database level - the decision is that no code path offers
 * it, which is why there is no {@code updatedAt} column to maintain.
 *
 * <p><strong>No {@code @Version}.</strong> {@link Ticket} has one because it is mutated after creation
 * and takes concurrent writes. Nothing mutates a comment, so two people commenting at once are two
 * inserts that cannot conflict.
 *
 * <p>Both associations are {@code LAZY} and neither is traversed on the read path - the list endpoint
 * selects into {@code TicketCommentRow}, which reads the author's name through an explicit join. They
 * exist so a write can set them, which is what demands the real entities rather than bare ids:
 * {@code ticket_id} and {@code commented_by} are foreign keys, and the association is what makes
 * Hibernate check them at insert time rather than letting the database refuse later.
 */
@Entity
@Table(name = "ticket_comments")
@Getter
@Setter
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The message body.
     *
     * <p>{@code TEXT}, so there is no column limit to collide with. The request DTO caps it at 4,000
     * characters anyway: the limit belongs to the API contract rather than to storage, because a
     * rejected comment should be a 400 naming the field and not a database error.
     */
    @Column(name = "comment", nullable = false, columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    /** Who said it. Never null, and never the caller's own claim - taken from the access token. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commented_by", nullable = false)
    private User commentedBy;
}
