package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.dto.request.TicketFilter;
import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.repository.projection.TicketCountRow;
import com.forward.desk_resolver.repository.projection.TicketRow;
import com.forward.desk_resolver.repository.projection.TicketStatsRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Criteria-API implementation of {@link TicketQueryRepository}.
 *
 * <p>Two queries per page and no more: one for the rows, one for the total. Both are built with only
 * the predicates the caller actually supplied, which is what keeps the plans stable - see the
 * interface for the measurements that motivated this.
 */
public class TicketQueryRepositoryImpl implements TicketQueryRepository {

    /**
     * The {@code ESCAPE} character for the free-text {@code LIKE} patterns.
     *
     * <p>A backslash, which is also PostgreSQL's default - but declared explicitly in every
     * {@code like()} call below rather than relied upon, because the default is a property of the
     * database and this class does not get to assume which one it is talking to. The tests run on
     * PostgreSQL; the escaping should not become wrong if that ever stops being true.
     */
    private static final char LIKE_ESCAPE = '\\';

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable) {
        return findTicketRows(filter, pageable, null);
    }

    /**
     * @param restrictToUserId when non-null, results are restricted to tickets this user raised or is
     *                         assigned to. This is the <strong>authorization</strong> boundary for the
     *                         collection endpoint, not a user-supplied filter: a caller without
     *                         {@code TICKET_READ_ALL} cannot widen it, and because it is ANDed with the
     *                         request's own filters, asking for someone else's tickets simply returns
     *                         nothing rather than leaking a count.
     */
    @Override
    public Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable, Long restrictToUserId) {
        TicketFilter effective = filter == null ? TicketFilter.none() : filter;

        List<TicketRow> rows = fetchRows(effective, pageable, restrictToUserId);
        // PageableExecutionUtils skips the count query when the total is already implied - a first
        // page that came back short cannot have more rows behind it. That removes the second query
        // entirely for small result sets, which is the common case for a filtered queue view.
        return PageableExecutionUtils.getPage(rows, pageable, () -> countRows(effective, restrictToUserId));
    }

    // ---------------------------------------------------------------------------------------------
    // Statistics
    //
    // Three queries, and the split is by shape rather than by convenience: one conditional
    // aggregation for the scalars, and one `group by` each for the two breakdowns. Collapsing the
    // breakdowns into the scalar query is not possible - a row cannot be grouped two ways at once -
    // and splitting the scalars apart would scan the same rows four times to answer four questions
    // about them.
    //
    // None of the three joins anything. Every predicate compares a column on `tickets` itself,
    // including the association ids, for the reason recorded at length in fetchRows: Hibernate
    // renders an association id on the root as the foreign-key column, which is what keeps
    // idx_tickets_assigned_to_created_at and idx_tickets_raised_by_created_at reachable.
    // ---------------------------------------------------------------------------------------------

    @Override
    public TicketStatsRow findTicketStats(Instant now, Long currentUserId, Long restrictToUserId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TicketStatsRow> query = cb.createQuery(TicketStatsRow.class);
        Root<Ticket> ticket = query.from(Ticket.class);

        Predicate unsettled = cb.not(ticket.get("status").in(TicketStatus.settled()));

        // count() over a CASE that yields NULL for non-matching rows, rather than sum() over 1/0.
        // Both work; count() cannot return NULL for an empty result set, and sum() can - which would
        // make every field here a nullable Long that the record would then have to defend against.
        Expression<Long> overdue = countWhere(cb, cb.and(
                cb.isNotNull(ticket.get("expectedBy")),
                cb.lessThan(ticket.get("expectedBy"), now),
                unsettled));

        Expression<Long> unassigned = countWhere(cb, cb.and(
                cb.isNull(ticket.get("assignedTo")),
                unsettled));

        Expression<Long> assignedToMe = countWhere(cb, cb.and(
                cb.equal(ticket.get("assignedTo").get("id"), currentUserId),
                unsettled));

        // No `unsettled` here, deliberately - see TicketStatsRow: this one answers "where are my
        // requests up to", so a resolved request is still one of them.
        Expression<Long> raisedByMe = countWhere(cb,
                cb.equal(ticket.get("raisedBy").get("id"), currentUserId));

        query.select(cb.construct(TicketStatsRow.class, overdue, unassigned, assignedToMe, raisedByMe));
        applyVisibility(cb, query, ticket, restrictToUserId);

        return entityManager.createQuery(query).getSingleResult();
    }

    @Override
    public List<TicketCountRow<TicketStatus>> countTicketsByStatus(Long restrictToUserId) {
        return countGroupedBy("status", false, restrictToUserId);
    }

    @Override
    public List<TicketCountRow<Priority>> countOpenTicketsByPriority(Long restrictToUserId) {
        return countGroupedBy("priority", true, restrictToUserId);
    }

    /**
     * One {@code select column, count(*) ... group by column} over the caller's visible tickets.
     *
     * <p>The grouped column is named by string because the two callers differ in nothing else. It is
     * never user input - both call sites pass a literal - so there is no injection surface here;
     * Criteria would reject an unmapped name at query build time in any case.
     *
     * @param unsettledOnly excludes settled tickets. False means count every ticket, which is what
     *                      the status breakdown wants - a breakdown of statuses that omitted two of
     *                      them would be a strange thing to render.
     *
     * <p><strong>The casts.</strong> {@code CriteriaBuilder.construct} is declared over a raw
     * {@code Class}, so it cannot carry {@code TicketCountRow}'s own type argument through; there is
     * no way to express "construct a {@code TicketCountRow<T>}" in the Criteria API. The grouped
     * column is {@code NOT NULL} and mapped to the enum the caller asks for, so the cast cannot fail
     * at runtime - only the two literal call sites above choose the property, and each pairs it with
     * the matching enum.
     */
    @SuppressWarnings("unchecked")
    private <T> List<TicketCountRow<T>> countGroupedBy(String property,
                                                       boolean unsettledOnly,
                                                       Long restrictToUserId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TicketCountRow> query = cb.createQuery(TicketCountRow.class);
        Root<Ticket> ticket = query.from(Ticket.class);

        Path<T> grouped = ticket.get(property);
        query.select(cb.construct(TicketCountRow.class, grouped, cb.count(ticket)));
        query.groupBy(grouped);

        Predicate extra = unsettledOnly
                ? cb.not(ticket.get("status").in(TicketStatus.settled()))
                : null;
        applyVisibility(cb, query, ticket, restrictToUserId, extra);

        List<TicketCountRow> rows = entityManager.createQuery(query).getResultList();
        return (List<TicketCountRow<T>>) (List<?>) rows;
    }

    /** {@code count(case when <condition> then 1 end)} - see the note in {@link #findTicketStats}. */
    private Expression<Long> countWhere(CriteriaBuilder cb, Predicate condition) {
        return cb.count(cb.selectCase().when(condition, 1).otherwise(cb.nullLiteral(Integer.class)));
    }

    /**
     * Applies the authorization boundary, and any extra predicate, to a statistics query.
     *
     * <p>Identical in meaning to the {@code restrictToUserId} block in {@link #fetchRows} - and
     * written once here rather than copied into three query builders, because an authorization
     * predicate that exists in four places is one that eventually gets left out of the fourth.
     */
    private void applyVisibility(CriteriaBuilder cb,
                                 CriteriaQuery<?> query,
                                 Root<Ticket> ticket,
                                 Long restrictToUserId,
                                 Predicate... extra) {
        List<Predicate> predicates = new ArrayList<>();
        for (Predicate predicate : extra) {
            if (predicate != null) {
                predicates.add(predicate);
            }
        }
        if (restrictToUserId != null) {
            predicates.add(cb.or(
                    cb.equal(ticket.get("raisedBy").get("id"), restrictToUserId),
                    cb.equal(ticket.get("assignedTo").get("id"), restrictToUserId)));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(Predicate[]::new)));
        }
    }

    private List<TicketRow> fetchRows(TicketFilter filter, Pageable pageable, Long restrictToUserId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TicketRow> query = cb.createQuery(TicketRow.class);
        Root<Ticket> ticket = query.from(Ticket.class);

        // raisedBy and application are NOT NULL, so inner joins cannot drop rows.
        // assignedTo is nullable and an unassigned ticket must still appear, hence the LEFT join.
        Join<Ticket, User> raiser = ticket.join("raisedBy");
        Join<Ticket, User> assignee = ticket.join("assignedTo", JoinType.LEFT);
        Join<Ticket, Application> application = ticket.join("application");

        query.select(cb.construct(TicketRow.class,
                ticket.get("id"),
                ticket.get("ticketNumber"),
                ticket.get("title"),
                ticket.get("description"),
                ticket.get("issueType"),
                ticket.get("priority"),
                ticket.get("status"),
                ticket.get("businessImpact"),
                ticket.get("expectedBy"),
                ticket.get("createdAt"),
                ticket.get("updatedAt"),
                ticket.get("resolvedAt"),
                raiser.get("fullName"),
                assignee.get("fullName"),
                application.get("appName"),
                ticket.get("moduleName"),
                raiser.get("id"),
                assignee.get("id"),
                // The FK column on tickets, not application.get("id") on the joined relation - the
                // same reason the predicates below read association ids off the root: Hibernate
                // renders it as tickets.application_id and adds no join.
                ticket.get("application").get("id")));

        List<Predicate> predicates = predicates(cb, ticket, filter);
        if (restrictToUserId != null) {
            // Deliberately compares the TICKET's own foreign-key columns rather than the joined
            // users' primary keys. Writing this as (raiser.id = ? or assignee.id = ?) is logically
            // identical but spans two joined relations, which PostgreSQL cannot push down into the
            // ticket indexes: measured at 20,000 rows it produced a Seq Scan over the whole table
            // plus both user tables (414 buffers) instead of an index scan (139). Comparing the FK
            // columns keeps idx_tickets_raised_by_created_at and idx_tickets_assigned_to_created_at
            // usable as a BitmapOr. Hibernate renders an association id on the root as the FK column,
            // with no extra join.
            //
            // assigned_to = ? excludes NULL, which is correct: an unassigned ticket is still reachable
            // through the raised_by side of the OR.
            predicates.add(cb.or(
                    cb.equal(ticket.get("raisedBy").get("id"), restrictToUserId),
                    cb.equal(ticket.get("assignedTo").get("id"), restrictToUserId)));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(Predicate[]::new)));
        }
        query.orderBy(orders(cb, ticket, pageable.getSort()));

        return entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();
    }

    private long countRows(TicketFilter filter, Long restrictToUserId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Ticket> ticket = query.from(Ticket.class);

        query.select(cb.count(ticket));

        // No joins at all: every predicate compares a column on tickets itself. Comparing an
        // association id on the root renders as the foreign-key column, so there is nothing to join
        // and nothing that could accidentally become an inner join and drop unassigned rows.
        List<Predicate> predicates = predicates(cb, ticket, filter);
        if (restrictToUserId != null) {
            predicates.add(cb.or(
                    cb.equal(ticket.get("raisedBy").get("id"), restrictToUserId),
                    cb.equal(ticket.get("assignedTo").get("id"), restrictToUserId)));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(Predicate[]::new)));
        }

        Long total = entityManager.createQuery(query).getSingleResult();
        return total == null ? 0L : total;
    }

    /**
     * The filter predicates, shared by the row query and the count query.
     *
     * <p>Every predicate compares a column on {@code tickets} itself, including the two that look like
     * association comparisons: Hibernate renders an association id on the root as the foreign-key
     * column, so {@code ticket.get("assignedTo").get("id")} needs no join. That is what keeps
     * {@code idx_tickets_assigned_to_created_at} and {@code idx_tickets_raised_by_created_at} usable -
     * the same reasoning the {@code restrictToUserId} comment in {@link #fetchRows} records with
     * measurements.
     *
     * <p>Previously {@code raisedByUserId} was the exception: it compared {@code raiser.get("id")} on
     * the joined {@code User} instead, which is logically identical but spans a joined relation and so
     * gives the planner nothing to push into the ticket indexes. It also meant the row query and the
     * count query - which has no joins at all - filtered through two different expressions for the
     * same filter. One method now serves both, so they cannot drift apart again.
     */
    private List<Predicate> predicates(CriteriaBuilder cb, Root<Ticket> ticket, TicketFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.status() != null) {
            predicates.add(cb.equal(ticket.get("status"), filter.status()));
        }
        if (filter.priority() != null) {
            predicates.add(cb.equal(ticket.get("priority"), filter.priority()));
        }
        if (filter.assignedToUserId() != null) {
            predicates.add(cb.equal(ticket.get("assignedTo").get("id"), filter.assignedToUserId()));
        }
        if (filter.raisedByUserId() != null) {
            predicates.add(cb.equal(ticket.get("raisedBy").get("id"), filter.raisedByUserId()));
        }
        if (filter.q() != null) {
            predicates.add(freeText(cb, ticket, filter.q()));
        }
        return predicates;
    }

    /**
     * {@code lower(col) like '%term%'} across the three columns a reader would search by.
     *
     * <p><strong>Which columns, and why only these three.</strong> {@code ticket_number} is how a
     * ticket is referred to in an email or a corridor; {@code title} is how it is recognised;
     * {@code description} is where the detail that was not in the title ended up. The joined columns
     * are deliberately excluded - searching the raiser's and assignee's names would turn this into a
     * three-way join on a query that already cannot use an index, and "tickets involving a person" is
     * what {@code assignedTo} and {@code raisedBy} already answer exactly.
     *
     * <p>All three are {@code NOT NULL}, so the {@code OR} cannot be short-circuited by a NULL
     * comparison and no {@code coalesce} is needed.
     *
     * <p><strong>Case folding happens twice, on purpose.</strong> {@code lower()} in SQL for the
     * column and {@code Locale.ROOT} in Java for the term. {@code ROOT} rather than the default locale
     * because {@code String.toLowerCase()} is locale-sensitive in exactly the way that bites here: in a
     * Turkish locale {@code "I"} lowercases to a dotless {@code "ı"}, which would then match nothing
     * the database produced - a server searching for different text depending on its own
     * {@code -Duser.language}.
     */
    private Predicate freeText(CriteriaBuilder cb, Root<Ticket> ticket, String term) {
        String pattern = "%" + escapeLikePattern(term.toLowerCase(Locale.ROOT)) + "%";

        return cb.or(
                cb.like(cb.lower(ticket.get("ticketNumber")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(ticket.get("title")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(ticket.get("description")), pattern, LIKE_ESCAPE));
    }

    /**
     * Escapes the characters {@code LIKE} treats as wildcards, so that a search term is matched as the
     * literal text somebody typed.
     *
     * <p>Without this, {@code %} and {@code _} typed into a search box are operators rather than
     * characters. A lone {@code %} matches every ticket, which looks like the filter being ignored; and
     * {@code TKT_1} silently matches {@code TKT-1}, {@code TKTx1} and anything else of that shape, so
     * a ticket-number search returns rows that are not the ticket. The parameter is still bound, so
     * this is not an injection defence - it is the difference between searching for text and
     * accidentally handing the user a pattern language.
     *
     * <p>The escape character escapes itself, which is why a literal backslash in the term is doubled:
     * leaving it alone would consume the character after it.
     */
    private String escapeLikePattern(String term) {
        StringBuilder escaped = new StringBuilder(term.length() + 8);
        for (int index = 0; index < term.length(); index++) {
            char character = term.charAt(index);
            if (character == '%' || character == '_' || character == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /**
     * Translates the request's sort into criteria orders.
     *
     * <p>Sort properties are whitelisted upstream in {@code TicketPageRequests}, which also guarantees
     * an {@code id} tiebreaker, so ordering here is both safe and deterministic.
     */
    private List<Order> orders(CriteriaBuilder cb, Root<Ticket> ticket, Sort sort) {
        if (sort.isUnsorted()) {
            return List.of(cb.desc(ticket.get("createdAt")), cb.desc(ticket.get("id")));
        }
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Expression<?> expression = sortExpression(cb, ticket, order.getProperty());
            orders.add(order.isAscending() ? cb.asc(expression) : cb.desc(expression));
        }
        return orders;
    }

    /**
     * The expression a sort property orders by.
     *
     * <p>Every property sorts by its own column except {@code priority}, which sorts by severity.
     *
     * <p><strong>Why that needs an expression.</strong> {@code Priority} is mapped
     * {@code @Enumerated(EnumType.STRING)}, so the column holds {@code 'LOW'}, {@code 'MEDIUM'},
     * {@code 'HIGH'}, {@code 'CRITICAL'} and the database sorts it as text. Ascending gave
     * CRITICAL, HIGH, LOW, MEDIUM; {@code ?sort=priority,desc} - the obvious way to ask for "worst
     * first" - gave MEDIUM, LOW, HIGH, CRITICAL, putting the least urgent ticket at the top of a
     * triage queue. Nothing failed and nothing was logged; the list was simply wrong in the one place
     * a support queue is read.
     *
     * <p>Unlike most of the open questions in this codebase this one needs no business decision:
     * {@code Priority} is an ordered scale by its nature, and its declaration order
     * {@code LOW < MEDIUM < HIGH < CRITICAL} already states the severity. The {@code CASE} is built
     * from {@code Priority.values()}, so a new level inserted in the enum is ranked correctly without
     * touching this method.
     *
     * <p><strong>{@code status} is deliberately left alone.</strong> It has the same string-ordering
     * behaviour, but {@code TicketStatus} is not a scale - it is a set of lifecycle states whose legal
     * order is the undecided business question in audit P1-5, and {@code REOPENED} and {@code PENDING}
     * sit at the end of the enum precisely because no sequence was agreed. Ranking it by declaration
     * order would be inventing that sequence. Sorting by status still groups equal statuses together,
     * which is what it is useful for; it just does not claim to be lifecycle order. The same applies
     * to {@code role} on the user listing.
     *
     * <p><strong>No performance change.</strong> Ordering by an expression cannot use an index, but
     * ordering by the {@code priority} column could not either: V12 records that a
     * {@code (priority, created_at DESC, id DESC)} index was built, measured, chosen by the planner
     * zero times and discarded, because four distinct values over 20,000 rows never beat
     * {@code idx_tickets_created_at_id}. This replaces an unindexed column sort with an unindexed
     * expression sort over the same rows.
     */
    private Expression<?> sortExpression(CriteriaBuilder cb, Root<Ticket> ticket, String property) {
        if (!"priority".equals(property)) {
            return ticket.get(property);
        }
        CriteriaBuilder.Case<Integer> severity = cb.selectCase();
        for (Priority level : Priority.values()) {
            severity = severity.when(cb.equal(ticket.get("priority"), level), level.ordinal());
        }
        // Unreachable while the column is constrained to the enum; ranks anything unexpected as the
        // least urgent rather than silently sorting it first.
        return severity.otherwise(Integer.MAX_VALUE);
    }
}
