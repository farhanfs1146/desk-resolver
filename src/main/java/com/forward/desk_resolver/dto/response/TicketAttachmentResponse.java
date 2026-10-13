package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One attachment's metadata. The bytes come from
 * {@code GET /api/tickets/{ticketId}/attachments/{attachmentId}/content}.
 *
 * <p>No storage key and no download URL. The key is internal (see {@code TicketAttachmentRow}), and a
 * URL would be this response asserting a route the controller owns - the id plus the documented path
 * is the same information without two places to keep in step.
 */
@Data
@Builder
public class TicketAttachmentResponse {

    private Long id;

    @Schema(description = "The name the file was uploaded under", example = "payslip-march.png")
    private String fileName;

    @Schema(description = "Content type the upload was accepted as", example = "image/png")
    private String fileType;

    @Schema(description = "Size in bytes, as stored", example = "48213")
    private Long sizeBytes;

    @Schema(description = "Full name of the user who uploaded it", example = "Farhan Ali")
    private String uploadedBy;

    private LocalDateTime uploadedAt;
}
