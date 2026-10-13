package com.forward.desk_resolver.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Where attachment bytes live, behind an interface.
 *
 * <p>The implementation that ships is {@link LocalAttachmentStorage}, which writes to a directory. The
 * interface exists because that is the part of this feature most likely to be replaced - object
 * storage is the obvious destination for a deployment running more than one instance, since a local
 * directory means an upload handled by one instance cannot be downloaded from another. Keeping the
 * seam explicit is what makes that a new class rather than a change to the service.
 *
 * <p>The contract is deliberately narrow. The storage layer knows nothing about tickets, permissions
 * or users: it is handed a ticket id only as a path segment to group files under, and every
 * authorization decision has already been made by the time anything here is called.
 */
public interface AttachmentStorage {

    /**
     * Writes an uploaded file and returns the key it was written under.
     *
     * @param ticketId the ticket the file belongs to, used only to group blobs on disk
     * @param file     the uploaded part, already validated for size and content type by the caller
     * @return what was stored, including the generated key to record on the row
     */
    StoredFile store(Long ticketId, MultipartFile file);

    /**
     * Opens a stored blob for reading.
     *
     * @param storageKey a key previously returned by {@link #store}
     * @return the bytes, as a streamable resource
     * @throws AttachmentNotStoredException if the key resolves to nothing readable - a row whose file
     *                                      is missing, which is possible because the row and the blob
     *                                      are not written in one transaction
     */
    Resource load(String storageKey);

    /**
     * @param storageKey the generated key, relative to the storage root
     * @param sizeBytes  what was actually written, counted on the way through
     */
    record StoredFile(String storageKey, long sizeBytes) {
    }
}
