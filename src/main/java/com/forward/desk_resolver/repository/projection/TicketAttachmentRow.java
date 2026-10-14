package com.forward.desk_resolver.repository.projection;

import java.time.Instant;

/**
 * Flat read projection for a ticket's attachment list - metadata only.
 *
 * <p><strong>{@code file_path} is deliberately absent.</strong> It is the storage key, and it is the
 * one column on the table that must never reach a client: an attachment is downloaded by its
 * <em>id</em>, through an endpoint that re-checks ticket visibility, so a client has no use for the
 * key and publishing it would invite a request that tries to supply one. The download path reads the
 * entity, not this.
 *
 * <p>Same for the uploader: a name, no id. See {@link TicketCommentRow}, which records the reasoning
 * and the fact that {@code TicketResponse} deliberately diverges from it.
 */
public record TicketAttachmentRow(
        Long id,
        String fileName,
        String fileType,
        Long sizeBytes,
        String uploadedByFullName,
        Instant uploadedAt
) {
}
