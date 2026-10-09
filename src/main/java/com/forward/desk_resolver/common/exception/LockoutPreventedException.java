package com.forward.desk_resolver.common.exception;

/**
 * Thrown when a change would leave the application with nobody able to undo it.
 *
 * <p>There is exactly one such change today: removing {@code USER_MANAGE} from the last active account
 * that holds it. Nothing else in the application could put it back - user administration is the
 * capability being removed, and the bootstrap administrator only runs when no administrator exists at
 * all, so it would not step in either. Recovery would mean hand-editing {@code auth.user_roles}.
 *
 * <p>A 409, not a 403: the caller is permitted to do this, and in any other circumstance it would
 * succeed. What refuses it is the current state of the data - the same reason a duplicate email is a
 * 409. The detail names the way out (grant it to someone else first) because an error that only says
 * "no" leaves the caller guessing.
 *
 * <p>Deliberately not generalised into a rule engine over permissions. One capability has this
 * property, and a framework for a single case is harder to read than the case.
 */
public class LockoutPreventedException extends RuntimeException {

    public LockoutPreventedException(String message) {
        super(message);
    }
}
