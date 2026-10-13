package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.common.web.PageRequests;
import com.forward.desk_resolver.common.web.TicketAttachmentPageRequests;
import com.forward.desk_resolver.dto.response.TicketAttachmentResponse;
import com.forward.desk_resolver.service.TicketAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Files attached to a ticket.
 *
 * <p>Nested under the ticket for the same reason comments are: the ticket id in the path is what
 * visibility is checked against.
 *
 * <p>This is the only controller in the project that neither consumes nor produces JSON throughout -
 * upload is {@code multipart/form-data} and download is a byte stream. Errors are still RFC 7807
 * problem documents, because those come from {@code GlobalExceptionHandler} and not from the handler's
 * declared content type.
 */
@RestController
@RequestMapping("/api/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class TicketAttachmentController {

    private final TicketAttachmentService ticketAttachmentService;

    @Operation(summary = "Attach a file to a ticket",
            description = "multipart/form-data with one part named 'file'. Requires "
                    + "TICKET_ATTACHMENT_UPLOAD and involvement in the ticket. The file is refused "
                    + "with 400 if it is empty, over app.storage.attachments.max-file-size, or of a "
                    + "content type outside the configured allow-list; 413 if the servlet container "
                    + "rejected it as too large before the application saw it. The client's filename "
                    + "is kept for display only - stored paths are generated.")
    @PreAuthorize("hasAuthority('TICKET_ATTACHMENT_UPLOAD')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketAttachmentResponse> upload(
            @PathVariable Long ticketId,
            @Parameter(description = "The file to attach",
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ticketAttachmentService.upload(ticketId, file));
    }

    @Operation(summary = "List a ticket's attachments",
            description = "Metadata only; newest first. Fetch the bytes from "
                    + "/{attachmentId}/content. Pagination metadata is in the X-* headers.")
    @PreAuthorize("hasAuthority('TICKET_ATTACHMENT_READ')")
    @GetMapping
    public ResponseEntity<List<TicketAttachmentResponse>> getAttachments(
            @PathVariable Long ticketId,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. "
                    + "Allowed: uploadedAt, fileName, sizeBytes, id")
            @RequestParam(required = false) String sort
    ) {
        Page<TicketAttachmentResponse> result = ticketAttachmentService.getAttachments(
                ticketId, TicketAttachmentPageRequests.of(page, size, sort));
        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    /**
     * Streams one attachment's bytes.
     *
     * <p><strong>Always {@code Content-Disposition: attachment}, never {@code inline}.</strong> The
     * content type on the row is what the client declared at upload and was only checked against an
     * allow-list - nothing sniffed the bytes. Serving such a file inline would let an uploader choose
     * how the browser executes it in this application's origin, which is stored XSS via a file picker.
     * Downloading it instead makes the declared type a hint about which program opens it rather than a
     * decision the browser acts on.
     *
     * <p>{@code X-Content-Type-Options: nosniff} is already set globally by {@code SecurityConfig}, so
     * the browser also will not second-guess the type we send. Two independent reasons the bytes cannot
     * be rendered as script.
     *
     * <p>The filename is encoded through {@link ContentDisposition}, which emits the {@code filename*}
     * RFC 5987 form when needed - so a non-ASCII name survives instead of being mangled, and a header
     * built by string concatenation (the usual source of header injection here) is avoided entirely.
     */
    @Operation(summary = "Download an attachment's contents",
            description = "Served as a download with the original filename. Always "
                    + "Content-Disposition: attachment, never inline - the stored content type is "
                    + "client-declared and is not trusted for rendering. 404 if the attachment does "
                    + "not belong to this ticket.")
    @PreAuthorize("hasAuthority('TICKET_ATTACHMENT_READ')")
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> download(
            @PathVariable Long ticketId,
            @PathVariable Long attachmentId
    ) {
        TicketAttachmentService.DownloadableAttachment attachment =
                ticketAttachmentService.download(ticketId, attachmentId);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaType(attachment.fileType()))
                .contentLength(attachment.sizeBytes())
                .body(attachment.content());
    }

    /**
     * The stored type, or {@code application/octet-stream} when it is missing or unparseable.
     *
     * <p>Falling back rather than failing: {@code file_type} is nullable for rows older than
     * {@code V19}, and a download is the wrong moment to discover that. Octet-stream is also the
     * safest possible answer, since it is the one type no browser will try to render.
     */
    private static MediaType mediaType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(fileType);
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
