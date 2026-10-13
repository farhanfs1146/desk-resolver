package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.response.TicketAttachmentResponse;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

/**
 * Files attached to a ticket.
 *
 * <p>Like {@code TicketCommentService}, a resource of its own that shares
 * {@code TicketVisibilityGuard} with the ticket service. Unlike it, this one also owns bytes on a
 * disk, which is behind {@code AttachmentStorage}.
 *
 * <p>There is no delete. Removing an attachment is two irreversible operations that cannot be made
 * atomic - a row and a file - and the rule it would need (may the uploader remove it? the assignee?
 * after the ticket is closed?) is not established anywhere, which is the same reason ticket status
 * transitions are unvalidated. See {@code docs/DECISIONS.md}.
 */
public interface TicketAttachmentService {

    /**
     * Stores an uploaded file against a ticket.
     *
     * @throws com.forward.desk_resolver.common.exception.InvalidUploadException if the file is empty,
     *         over the configured size, or of a content type that is not allowed
     */
    TicketAttachmentResponse upload(Long ticketId, MultipartFile file);

    Page<TicketAttachmentResponse> getAttachments(Long ticketId, Pageable pageable);

    /**
     * Opens one attachment's content for streaming.
     *
     * @param ticketId     the ticket it must belong to - part of the lookup, not a later check
     * @param attachmentId which attachment
     */
    DownloadableAttachment download(Long ticketId, Long attachmentId);

    /**
     * An attachment's bytes together with what the response has to say about them.
     *
     * @param content   the stored bytes, streamed rather than buffered
     * @param fileName  the client's original name, for {@code Content-Disposition}
     * @param fileType  the content type it was accepted as
     * @param sizeBytes the recorded size, for {@code Content-Length}
     */
    record DownloadableAttachment(Resource content, String fileName, String fileType, long sizeBytes) {
    }
}
