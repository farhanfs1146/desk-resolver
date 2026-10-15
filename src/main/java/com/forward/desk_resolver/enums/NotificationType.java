package com.forward.desk_resolver.enums;

/**
 * What a notification is about.
 *
 * <p><strong>These are written from the recipient's point of view, not the actor's.</strong> One
 * assignment produces up to three of them - {@link #TICKET_ASSIGNED_TO_YOU} for whoever received the
 * ticket, {@link #TICKET_NOW_ASSIGNED} for whoever raised it, {@link #TICKET_TAKEN_FROM_YOU} for
 * whoever held it before - because the row already knows who it is for and a client should not have
 * to work out which of the three it is looking at.
 *
 * <p>The alternative was one {@code TICKET_ASSIGNED} type plus a comparison at render time, and the
 * comparison is the problem: the only thing a client could compare is {@code new_value}, which is a
 * full name, and {@code auth.users.full_name} has no unique constraint. Two colleagues with the same
 * name would see each other's assignment as their own.
 *
 * <p>Stored as a string, like every other enum in this schema, so a reordering of this file cannot
 * silently change what existing rows mean.
 */
public enum NotificationType {

    /**
     * A ticket was raised and nobody owns it - sent to the holders of {@code TICKET_ASSIGN}.
     *
     * <p>The one type whose recipients come from a permission rather than from the ticket. It has to:
     * an unassigned ticket has no assignee to tell, and the requester already knows they raised it.
     */
    TICKET_AWAITING_ASSIGNMENT,

    /** You are now the assignee. */
    TICKET_ASSIGNED_TO_YOU,

    /** A ticket you raised has an owner now. {@code newValue} is their name. */
    TICKET_NOW_ASSIGNED,

    /** A ticket you were working on has been handed to somebody else. {@code newValue} is their name. */
    TICKET_TAKEN_FROM_YOU,

    /** The status moved. {@code oldValue} and {@code newValue} are the two states. */
    TICKET_STATUS_CHANGED,

    /**
     * Somebody else wrote on the thread.
     *
     * <p>Note that this does <em>not</em> correspond to a {@code ticket_history_tracking} row. A
     * comment deliberately writes no audit entry - {@code TicketCommentServiceImpl} explains why - and
     * that decision is untouched here. A notification is a message to a person; an audit row is a
     * record of a state change. A comment is the first and not the second.
     */
    TICKET_COMMENTED,

    /** The deadline passed with the ticket unresolved. Raised by the scheduler, so it has no actor. */
    TICKET_OVERDUE
}
