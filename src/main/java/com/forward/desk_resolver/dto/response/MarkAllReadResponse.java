package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How many notifications {@code POST /api/notifications/read-all} just marked read.
 *
 * <p>A record rather than a map, for the reason {@link UnreadCountResponse} gives: a map serialises
 * to the right JSON but describes itself in OpenAPI as an object with unknown keys, so the field name
 * survives only in prose.
 *
 * <p>Reported at all so a client can confirm the badge should now be zero rather than refetching to
 * find out - and so that "nothing was unread" is distinguishable from "something went wrong", which
 * a 204 would not be.
 */
@Schema(description = "The outcome of marking a whole inbox read")
public record MarkAllReadResponse(
        @Schema(description = "How many notifications were unread until now", example = "3")
        int markedRead
) {
}
