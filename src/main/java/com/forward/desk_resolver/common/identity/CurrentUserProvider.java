package com.forward.desk_resolver.common.identity;

import com.forward.desk_resolver.security.Permission;

import java.util.UUID;

/**
 * The acting user for the current request, what they are allowed to do, and the session they are
 * acting through.
 *
 * <p>Service code asks this interface rather than reaching into Spring Security, so a service stays
 * testable with a stub and carries no dependency on how identity is transported.
 *
 * <p>{@link #hasPermission} lives here rather than as a static helper on the implementation.
 * It used to be {@code AuthenticatedCurrentUserProvider.currentUserHas(...)}, a static method on a
 * {@code @Component}, called from services that <em>also</em> injected this interface - so a service
 * depended on the abstraction for identity and on the concrete class for authority, and the static
 * half could not be stubbed in a test. One seam, not two.
 */
public interface CurrentUserProvider {

    /**
     * @return the id of the authenticated caller
     * @throws MissingUserIdentityException when no usable caller identity is present; there is no
     *                                      default user and no fallback
     */
    Long requireCurrentUserId();

    /**
     * @return the session the caller is acting through
     * @throws MissingUserIdentityException when no usable session identity is present
     *
     * <p>Needed by exactly the operations that act on the session itself - logging out of this one,
     * and ending every session a user holds. It is deliberately on the same seam as the user id: both
     * come from the same verified token, and a service that could read one without the other would be
     * able to end a session without knowing whose it was.
     */
    UUID requireCurrentSessionId();

    /**
     * @return true when the current caller holds the given permission
     *
     * <p>For resource-level decisions an endpoint annotation cannot express - for example "may this
     * caller read a ticket they are not involved in". Returns false rather than throwing when there is
     * no authenticated caller, because the question is "may they" and the answer is no.
     */
    boolean hasPermission(Permission permission);
}
