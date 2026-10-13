package com.forward.desk_resolver.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where attachments are written, how large they may be, and what they may contain.
 *
 * @param directory           filesystem root for stored blobs. Created at startup if absent.
 * @param maxFileSize         per-file ceiling enforced by this application, independently of the
 *                            servlet container's own multipart limit
 * @param allowedContentTypes exact content types accepted, lower-cased. An allow-list, never a
 *                            deny-list - see {@link #isAllowed(String)}.
 */
@ConfigurationProperties(prefix = "app.storage.attachments")
public record AttachmentStorageProperties(
        String directory,
        DataSize maxFileSize,
        List<String> allowedContentTypes
) {

    /**
     * Defaults chosen to be usable without configuration but not to be the interesting case.
     *
     * <p>{@code ./data/attachments} is relative to the working directory, which is right for a
     * developer machine and wrong for a deployment - a container would lose every file on restart. The
     * property is documented in {@code application.yaml} as one to set, rather than defaulted to
     * something that only looks durable.
     */
    public AttachmentStorageProperties {
        directory = directory == null || directory.isBlank() ? "./data/attachments" : directory;
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(10) : maxFileSize;
        allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty()
                ? List.of("image/png", "image/jpeg", "image/gif", "image/webp",
                "application/pdf", "text/plain", "text/csv",
                "application/zip",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : List.copyOf(allowedContentTypes);
    }

    /**
     * Whether a content type is accepted.
     *
     * <p><strong>An allow-list, and the direction matters more than the contents.</strong> A deny-list
     * of dangerous types is wrong by construction: it has to be complete to work, and the entry nobody
     * thought of is the one that gets used. An allow-list fails closed - an unfamiliar type is refused,
     * and widening it is a deliberate configuration change.
     *
     * <p>Note what this does <em>not</em> establish. The value compared here is the {@code Content-Type}
     * the client sent, which a client can set to anything; it is not sniffed from the bytes. So this is
     * not a guarantee about what a file contains, and the download endpoint does not rely on it being
     * one: it serves every attachment as an attachment, with no inline rendering, precisely because the
     * declared type cannot be trusted that far.
     */
    public boolean isAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        // Strip any parameters: "text/plain; charset=utf-8" is text/plain.
        String bare = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalisedAllowedTypes().contains(bare);
    }

    public Set<String> normalisedAllowedTypes() {
        return allowedContentTypes.stream()
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
