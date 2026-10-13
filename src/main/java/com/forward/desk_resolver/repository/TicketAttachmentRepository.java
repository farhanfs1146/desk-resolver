package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.TicketAttachment;
import com.forward.desk_resolver.repository.projection.TicketAttachmentRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, Long> {

    @Query(value = """
            select new com.forward.desk_resolver.repository.projection.TicketAttachmentRow(
                a.id, a.fileName, a.fileType, a.sizeBytes, uploader.fullName, a.uploadedAt)
            from TicketAttachment a
                join a.uploadedBy uploader
            where a.ticket.id = :ticketId
            """,
            countQuery = """
                    select count(a.id) from TicketAttachment a where a.ticket.id = :ticketId
                    """)
    Page<TicketAttachmentRow> findAttachmentRows(@Param("ticketId") Long ticketId, Pageable pageable);

    /**
     * One attachment, <strong>only if it belongs to the given ticket</strong>.
     *
     * <p>The ticket id is part of the lookup rather than checked afterwards, and that is the whole
     * point of this method existing instead of {@code findById}. The download endpoint is nested under
     * a ticket, so it verifies visibility of <em>that</em> ticket - and if the attachment id were then
     * resolved on its own, any visible ticket would serve as a key to every attachment in the system.
     * Matching both means a mismatched pair is simply not found, with no second check to forget.
     */
    Optional<TicketAttachment> findByIdAndTicketId(Long id, Long ticketId);
}
