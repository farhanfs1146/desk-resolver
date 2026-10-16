package com.forward.desk_resolver.enums;

/**
 * How widely a ticket's problem is felt.
 *
 * <p><strong>Scope, not severity.</strong> This answers "how many people does it affect", and
 * {@link Priority} answers "how soon does it need fixing". Together they are the pair a support desk
 * actually triages on - the classic impact-and-urgency grid - and keeping them on separate axes is
 * the whole point of having both. An impact scale of LOW/MEDIUM/HIGH would have been a second
 * priority field wearing a different name, and the two would have disagreed with each other within a
 * week.
 *
 * <p><strong>Why this replaced free text.</strong> The column was a 50-character open string with a
 * placeholder suggesting "Department-level". Four tickets existed when it was converted, all meaning
 * the same thing, spelled two ways: {@code Organization-Level} twice and {@code Organization Level}
 * twice. Nothing was wrong with any of them - the field simply could not be counted, filtered or
 * grouped, which is the only reason to record impact at all. Four rows and two spellings is the
 * entire argument.
 *
 * <p><strong>Declaration order is the scale</strong>, ascending, from one person to everybody. That
 * makes it meaningful in the way {@code Priority}'s order is and {@link TicketStatus}'s deliberately
 * is not - so if this is ever made sortable, it should sort by ordinal through the same
 * {@code CASE} expression {@code priority} uses, never as text. Anything inserted in the middle
 * later must go in its place on the scale rather than on the end.
 *
 * <p><strong>There is no NONE.</strong> The field is optional, so an unset impact already means "the
 * requester did not say" - which is not the same claim as "this affects nobody", and a constant that
 * blurred the two would make an absent answer indistinguishable from a considered one.
 */
public enum BusinessImpact {

    /**
     * One person cannot get on with their work. The default shape of a help-desk ticket.
     */
    SINGLE_USER,

    /**
     * A team or a shift - several people doing the same job are held up together.
     */
    TEAM,

    /**
     * A whole department or business function, such as payroll or recruitment.
     */
    DEPARTMENT,

    /**
     * Multiple departments or business functions.
     */
    MULTIPLE_DEPARTMENTS,

    /**
     * Everyone, or a shared system that everyone depends on. The top of the scale.
     */
    COMPANY_WIDE
}
