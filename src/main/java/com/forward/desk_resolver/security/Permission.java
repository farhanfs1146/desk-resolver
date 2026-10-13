package com.forward.desk_resolver.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The capabilities this application actually has, expressed as granted authorities.
 *
 * <p><strong>Why permissions rather than roles in the checks.</strong> Endpoints are guarded by
 * permission (`hasAuthority("TICKET_ASSIGN")`) instead of by role (`hasRole("IT_SUPPORT")`). The role
 * stays the thing stored against a user; the permission is what the code asserts. That means changing
 * which roles may assign a ticket is a row in {@code auth.role_permissions} rather than a hunt through
 * controllers, and a new role can be introduced without touching a single authorization expression.
 *
 * <p><strong>Why this enum still exists now that permissions are a table.</strong> It is the source of
 * truth for <em>which permissions exist</em>, and {@code auth.permissions} is seeded from it by
 * {@code V17}. The division is between vocabulary and assignment:
 *
 * <ul>
 *   <li><strong>Vocabulary - code.</strong> A permission is enforced by a string literal inside
 *       {@code @PreAuthorize}. If the catalogue were editable data, renaming or deleting a row would
 *       disable that check with nothing failing anywhere, which is the worst possible shape for an
 *       authorization bug. Keeping the list in the enum means a permission cannot exist without code
 *       that names it, and cannot stop existing while that code is still there.
 *   <li><strong>Assignment - data.</strong> Which role holds which permission
 *       ({@code auth.role_permissions}), and which user holds which role ({@code auth.user_roles}).
 *       These are what an administrator changes, and they change without a redeploy.
 * </ul>
 *
 * <p>{@code PermissionCatalogueValidator} refuses to start the application if the two halves disagree
 * in either direction, so the seed cannot quietly rot.
 *
 * <p><strong>Why this list and no more.</strong> One permission per capability the API already
 * exposes, and these cover every endpoint it has. (This sentence used to assert an endpoint count,
 * which was already wrong and went further out of date the moment an endpoint was added - a number
 * nothing verifies is a comment that rots.) No speculative permissions for features that do not exist
 * (SLA, reporting), because an unused permission is an untested permission.
 *
 * <p>Comments and attachments were on that list of things that did not exist. They do now, so they
 * have permissions - which is the rule working rather than an exception to it: the four constants
 * below arrived in the same change as the endpoints that assert them and the tests that exercise them.
 */
public enum Permission {

    /** Raise a ticket. Every authenticated user can do this - it is the point of a support portal. */
    TICKET_CREATE,

    /**
     * Read tickets the user is involved in, as raiser or as assignee.
     *
     * <p>This is the floor: without it a user could not see the ticket they just created. Enforcing
     * "involved in" is a resource-level check, not something an endpoint annotation can express.
     */
    TICKET_READ_OWN,

    /**
     * Read any ticket regardless of involvement.
     *
     * <p>Support staff need this to triage. It is also what distinguishes a filtered list from an
     * unfiltered one: a caller without this permission has an ownership predicate forced into their
     * ticket queries.
     *
     * <p>Note what multiple roles per user means here: permissions are a union, so an {@code EMPLOYEE}
     * who is also granted {@code IT_SUPPORT} holds this and sees every ticket. Adding a role can only
     * widen access, never narrow it.
     */
    TICKET_READ_ALL,

    /** Change a ticket's status. */
    TICKET_STATUS_CHANGE,

    /** Assign or reassign a ticket to a user. */
    TICKET_ASSIGN,

    /**
     * List and read user records.
     *
     * <p>Needed to choose an assignee. Before Phase 4 {@code GET /api/users} was anonymous and
     * returned every user's name and email address.
     */
    USER_READ,

    /**
     * Create user accounts, including setting their initial password, and grant or revoke their roles.
     *
     * <p>Also what {@code GET /api/roles} requires: the list of roles exists so that whoever assigns
     * them can see what they are choosing from.
     *
     * <p>This is the permission the lockout guard protects. The last active account holding it cannot
     * have it taken away, because nothing else in the application could put it back - the bootstrap
     * administrator only runs when there is no administrator at all.
     */
    USER_MANAGE,

    /** Read the application/module catalogue. Needed to raise a ticket against an application. */
    APPLICATION_READ,

    /** Create, update or deactivate applications. */
    APPLICATION_MANAGE,

    /**
     * Read the discussion thread on a ticket.
     *
     * <p><strong>Holding it does not widen which tickets are visible.</strong> Every comment endpoint
     * runs {@code TicketVisibilityGuard} first, so this permission governs whether a caller may use
     * the sub-resource at all, and involvement still governs <em>whose</em> threads they reach. The two
     * checks are deliberately separate: an endpoint annotation cannot express "this ticket", and a
     * row-level rule cannot be read off the API surface.
     */
    TICKET_COMMENT_READ,

    /**
     * Add a comment to a ticket.
     *
     * <p>Granted to requester roles as well as support staff, which is the point of the feature: the
     * raiser answering "which browser?" is the exchange a ticketing system exists to carry, and before
     * this there was nowhere to put it. A comment changes no ticket field and drives no workflow, so it
     * is the one write a requester can make on a ticket after raising it.
     */
    TICKET_COMMENT_CREATE,

    /** List a ticket's attachments and download their contents. */
    TICKET_ATTACHMENT_READ,

    /**
     * Attach a file to a ticket.
     *
     * <p>Separate from {@link #TICKET_COMMENT_CREATE} rather than folded into a single "contribute"
     * permission. Uploading accepts bytes from a client and writes them to disk, which is a materially
     * different thing to allow than appending text to a table - so it is revocable on its own.
     */
    TICKET_ATTACHMENT_UPLOAD;

    /** The constant names, for comparison against {@code auth.permissions.code}. */
    public static Set<String> names() {
        return Arrays.stream(values()).map(Permission::name).collect(Collectors.toUnmodifiableSet());
    }
}
