package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.exception.InvalidReferenceException;
import com.forward.desk_resolver.common.exception.InvalidUploadException;
import com.forward.desk_resolver.common.exception.ResourceNotFoundException;
import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.dto.response.TicketAttachmentResponse;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.TicketAttachment;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.repository.TicketAttachmentRepository;
import com.forward.desk_resolver.repository.TicketRepository;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.projection.TicketAttachmentRow;
import com.forward.desk_resolver.service.TicketAttachmentService;
import com.forward.desk_resolver.storage.AttachmentStorage;
import com.forward.desk_resolver.storage.AttachmentStorageProperties;
import com.forward.desk_resolver.storage.LocalAttachmentStorage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Uploads, lists and serves ticket attachments.
 *
 * <h2>The order of operations on upload, and why it is this order</h2>
 *
 * <ol>
 *   <li><strong>Visibility.</strong> Before the file is looked at, so a caller cannot probe which
 *       ticket ids exist by watching which uploads get as far as a validation message.
 *   <li><strong>Validation.</strong> Empty, oversized, or a content type not on the allow-list - all
 *       400s, decided before a byte is written.
 *   <li><strong>Write the file.</strong>
 *   <li><strong>Insert the row.</strong>
 * </ol>
 *
 * <p>Steps 3 and 4 cannot be atomic: no transaction spans a filesystem and PostgreSQL. The file goes
 * first so that the failure which survives is an unreferenced blob rather than a row pointing at
 * nothing - an invisible waste of disk instead of an attachment a user can see and cannot download.
 * The {@code catch} then narrows even that: if the insert throws, the blob is removed, so the only way
 * to leak one is a crash between the two statements.
 *
 * <h2>Why the size is checked here as well as by the container</h2>
 *
 * {@code spring.servlet.multipart.max-file-size} refuses an oversized part before this code runs, and
 * that limit is set a little above this one on purpose. The container's refusal is a transport-level
 * error with no knowledge of the application; this one is a problem document naming the limit in the
 * units a person reads. The container's is the backstop that keeps an enormous body from being
 * buffered at all, so both exist and neither is redundant.
 */
@Service
@RequiredArgsConstructor
public class TicketAttachmentServiceImpl implements TicketAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(TicketAttachmentServiceImpl.class);

    /** Longest client filename kept, matching {@code ticket_attachments.file_name}. */
    private static final int FILE_NAME_MAX = 255;

    private final TicketAttachmentRepository ticketAttachmentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final TicketVisibilityGuard ticketVisibilityGuard;
    private final AttachmentStorage attachmentStorage;
    private final AttachmentStorageProperties storageProperties;

    @Override
    @Transactional
    public TicketAttachmentResponse upload(Long ticketId, MultipartFile file) {
        ticketVisibilityGuard.requireVisible(ticketId);
        requireAcceptable(file);

        Long uploaderId = currentUserProvider.requireCurrentUserId();
        User uploader = userRepository.findById(uploaderId)
                .orElseThrow(() -> InvalidReferenceException.of("authenticated user id", uploaderId));
        Ticket ticket = ticketRepository.getReferenceById(ticketId);

        AttachmentStorage.StoredFile stored = attachmentStorage.store(ticketId, file);
        try {
            TicketAttachment attachment = new TicketAttachment();
            attachment.setTicket(ticket);
            attachment.setFileName(safeFileName(file.getOriginalFilename()));
            attachment.setFilePath(stored.storageKey());
            attachment.setFileType(normalisedContentType(file.getContentType()));
            attachment.setSizeBytes(stored.sizeBytes());
            attachment.setUploadedBy(uploader);
            attachment.setUploadedAt(now());

            TicketAttachment saved = ticketAttachmentRepository.saveAndFlush(attachment);
            log.info("User {} attached {} ({} bytes) to ticket {} as {}",
                    uploaderId, saved.getFileName(), saved.getSizeBytes(), ticketId, stored.storageKey());

            return TicketAttachmentResponse.builder()
                    .id(saved.getId())
                    .fileName(saved.getFileName())
                    .fileType(saved.getFileType())
                    .sizeBytes(saved.getSizeBytes())
                    .uploadedBy(uploader.getFullName())
                    .uploadedAt(saved.getUploadedAt())
                    .build();
        } catch (RuntimeException e) {
            // The row did not land, so the file must not stay. saveAndFlush above is what makes this
            // reachable: without the flush the insert would be deferred to commit, outside this try,
            // and a constraint violation would leave the blob behind with nothing pointing at it.
            removeOrphan(stored.storageKey());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TicketAttachmentResponse> getAttachments(Long ticketId, Pageable pageable) {
        ticketVisibilityGuard.requireVisible(ticketId);

        return ticketAttachmentRepository.findAttachmentRows(ticketId, pageable)
                .map(TicketAttachmentServiceImpl::mapRow);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadableAttachment download(Long ticketId, Long attachmentId) {
        ticketVisibilityGuard.requireVisible(ticketId);

        // Matched on both ids, so an attachment belonging to a different ticket is "not found" rather
        // than something a second check has to catch. See TicketAttachmentRepository.
        TicketAttachment attachment = ticketAttachmentRepository
                .findByIdAndTicketId(attachmentId, ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Attachment", attachmentId));

        return new DownloadableAttachment(
                attachmentStorage.load(attachment.getFilePath()),
                attachment.getFileName(),
                attachment.getFileType(),
                attachment.getSizeBytes());
    }

    /**
     * Refuses a file this application will not store.
     *
     * <p>An empty part is its own case rather than a zero-size pass: a form submitted with no file
     * selected arrives as an empty part, so "no file" and "an empty file" are the same request and the
     * message has to serve both.
     */
    private void requireAcceptable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("No file was supplied, or the file is empty");
        }

        long maxBytes = storageProperties.maxFileSize().toBytes();
        if (file.getSize() > maxBytes) {
            log.info("Rejected an upload of {} bytes; the limit is {}", file.getSize(), maxBytes);
            throw new InvalidUploadException(
                    "The file is " + file.getSize() + " bytes, which is over the "
                            + maxBytes + " byte limit");
        }

        if (!storageProperties.isAllowed(file.getContentType())) {
            log.info("Rejected an upload with content type '{}'", file.getContentType());
            throw new InvalidUploadException(
                    "Content type '" + file.getContentType() + "' is not accepted. Allowed types: "
                            // Sorted, so two readings of the message agree and a client can diff them.
                            + String.join(", ", new TreeSet<>(storageProperties.normalisedAllowedTypes())));
        }
    }

    /**
     * Deletes a blob whose row did not survive.
     *
     * <p>Only {@link LocalAttachmentStorage} can do this - deletion is not on the
     * {@link AttachmentStorage} interface, because the interface exists for a feature that has no
     * delete operation and adding one there would be shaping the contract around a rollback. If a
     * future implementation needs it, that is the point to widen the interface; until then a leaked
     * blob is logged rather than silently accepted.
     */
    private void removeOrphan(String storageKey) {
        if (attachmentStorage instanceof LocalAttachmentStorage local) {
            local.deleteQuietly(storageKey);
            return;
        }
        log.warn("The attachment row for key {} failed to persist and the stored file could not be "
                + "removed; it is now unreferenced.", storageKey);
    }

    /**
     * The client's filename, made safe to <em>store and display</em>.
     *
     * <p>It is never used to build a path - {@code LocalAttachmentStorage} generates those - so this is
     * not the traversal defence. It is about what the string itself can do:
     *
     * <ul>
     *   <li>path separators are stripped, so a name like {@code ../../etc/passwd} is displayed as
     *       {@code passwd} rather than rendered to a reader as though it were a location;
     *   <li>control characters go, because they end up in a {@code Content-Disposition} header and a
     *       {@code CR} there is header injection;
     *   <li>it is truncated to the column width, so an overlong name is a stored row rather than a
     *       database error on upload.
     * </ul>
     *
     * <p>A name left with nothing in it becomes {@code attachment}: a blank filename would produce a
     * {@code Content-Disposition} the browser has to guess at.
     */
    static String safeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "attachment";
        }

        // The last segment under either separator, whichever the client's platform used. Done by index
        // rather than through java.nio.file.Path, deliberately: Path is the wrong tool for a string
        // that is not a path on this machine. Path.of("/") has a null file name, and on Windows
        // Path.of rejects a name containing a NUL outright - so both of those inputs, which are exactly
        // the ones an attacker sends, would be a crash rather than a sanitised name.
        int lastSeparator = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        String name = originalFilename.substring(lastSeparator + 1);

        StringBuilder cleaned = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean printable = character >= 0x20 && character != 0x7F;
            // Quote and backslash are the quoting syntax of the Content-Disposition header this ends up
            // in. Removing them here means the stored value is clean, not merely its rendering.
            boolean structural = character == '"' || character == '\\' || character == '/';
            if (printable && !structural) {
                cleaned.append(character);
            }
        }

        String result = cleaned.toString().strip();
        if (result.isEmpty() || result.equals(".") || result.equals("..")) {
            return "attachment";
        }
        return result.length() <= FILE_NAME_MAX ? result : result.substring(0, FILE_NAME_MAX);
    }

    /** Stores the bare type without parameters, so {@code file_type} is comparable across rows. */
    private static String normalisedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static TicketAttachmentResponse mapRow(TicketAttachmentRow row) {
        return TicketAttachmentResponse.builder()
                .id(row.id())
                .fileName(row.fileName())
                .fileType(row.fileType())
                .sizeBytes(row.sizeBytes())
                .uploadedBy(row.uploadedByFullName())
                .uploadedAt(row.uploadedAt())
                .build();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
