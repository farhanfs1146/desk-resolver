package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.TicketComment;
import com.forward.desk_resolver.repository.projection.TicketCommentRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {

    /**
     * One page of a ticket's thread, projected rather than loaded as entities.
     *
     * <p>Modelled on {@code TicketHistoryTrackingRepository.findHistoryRows}, including the separate
     * {@code countQuery}: the derived count for a constructor-expression query would otherwise carry
     * the join to {@code auth.users} along for a number that does not depend on it.
     *
     * <p>{@code join c.commentedBy author} is an inner join because {@code commented_by} is
     * {@code NOT NULL} - a comment without an author cannot exist, so a left join would only suggest
     * that it could.
     */
    @Query(value = """
            select new com.forward.desk_resolver.repository.projection.TicketCommentRow(
                c.id, c.comment, author.fullName, c.createdAt)
            from TicketComment c
                join c.commentedBy author
            where c.ticket.id = :ticketId
            """,
            countQuery = """
                    select count(c.id) from TicketComment c where c.ticket.id = :ticketId
                    """)
    Page<TicketCommentRow> findCommentRows(@Param("ticketId") Long ticketId, Pageable pageable);
}
