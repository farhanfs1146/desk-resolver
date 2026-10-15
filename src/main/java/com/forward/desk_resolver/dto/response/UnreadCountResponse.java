package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How many notifications the caller has not read.
 *
 * <p><strong>A record rather than {@code Map.of("unread", n)}, which is what this was first.</strong>
 * The map worked and the tests passed, but it serialises to the right JSON while describing itself in
 * OpenAPI as {@code additionalProperties: integer} - an object with unknown keys. The field name
 * existed only in the endpoint's prose description, so a client generated from the document would
 * receive {@code Record<string, number>} and lose it. This project's front end is generated from that
 * document by hand (see its README), which makes the document the contract rather than a by-product.
 *
 * <p>An object rather than a bare number for the usual reason: a second count can be added beside
 * this one - unread that are overdue, say - without the response changing shape. A bare {@code 3}
 * would have to become an object to grow, and that is a breaking change.
 */
@Schema(description = "The caller's unread notification count")
public record UnreadCountResponse(
        @Schema(description = "Notifications the caller has not read", example = "3")
        long unread
) {
}
