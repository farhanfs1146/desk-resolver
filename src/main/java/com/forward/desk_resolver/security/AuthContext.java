package com.forward.desk_resolver.security;

import java.util.Set;

/**
 * Everything the request path needs to know about the caller, resolved from one query.
 *
 * @param userId      the account the session belongs to; cross-checked against the token's {@code sub}
 * @param userActive  whether the account is still enabled - a deactivated user's live sessions are
 *                    rejected on their next request rather than at token expiry
 * @param roleCodes   the codes of the <em>active</em> roles the user holds
 * @param permissions the flat union of those roles' permissions. Union, never intersection, and no
 *                    deny: holding an additional role can only ever widen what a caller may do. That
 *                    is worth stating, because it means a user given both a narrow and a broad role
 *                    has the broad one - for instance an {@code EMPLOYEE} who is also
 *                    {@code IT_SUPPORT} holds {@code TICKET_READ_ALL} and therefore sees every ticket.
 */
public record AuthContext(
        Long userId,
        boolean userActive,
        Set<String> roleCodes,
        Set<Permission> permissions
) {
}
