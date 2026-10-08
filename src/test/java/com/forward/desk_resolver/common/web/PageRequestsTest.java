package com.forward.desk_resolver.common.web;

import com.forward.desk_resolver.common.exception.InvalidReferenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paging and sort parsing.
 *
 * <p>Worth unit-testing rather than only covering through a controller: this class is the single
 * place that decides what an unbounded or malformed paging request becomes, it is shared by every
 * listing endpoint, and its failure mode is a wrong answer rather than an error.
 */
class PageRequestsTest {

    private static final Set<String> SORTABLE = Set.of("createdAt", "priority", "id");
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }

    @Nested
    @DisplayName("page and size bounds")
    class Bounds {

        @Test
        @DisplayName("null page and size fall back to the first page at the default size")
        void defaults() {
            Pageable pageable = of(null, null, null);

            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(PageRequests.DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("size is capped, which is the whole point of the class")
        void sizeIsCapped() {
            // The unbounded list endpoint was the application's biggest scalability defect, so a
            // caller must not be able to re-create it by asking for a huge page.
            assertThat(of(0, 10_000, null).getPageSize()).isEqualTo(PageRequests.MAX_PAGE_SIZE);
            assertThat(of(0, Integer.MAX_VALUE, null).getPageSize()).isEqualTo(PageRequests.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("nonsensical page and size are corrected, not rejected")
        void negativesAreClamped() {
            assertThat(of(-5, -1, null).getPageNumber()).isZero();
            assertThat(of(-5, -1, null).getPageSize()).isEqualTo(PageRequests.DEFAULT_PAGE_SIZE);
            assertThat(of(0, 0, null).getPageSize()).isEqualTo(PageRequests.DEFAULT_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("sort parsing")
    class SortParsing {

        @Test
        @DisplayName("no sort means the endpoint's default order")
        void blankSortUsesDefault() {
            assertThat(of(0, 20, null).getSort()).isEqualTo(DEFAULT_SORT);
            assertThat(of(0, 20, "   ").getSort()).isEqualTo(DEFAULT_SORT);
        }

        @Test
        @DisplayName("an explicit direction is honoured in both directions")
        void explicitDirections() {
            assertThat(of(0, 20, "priority,desc").getSort().getOrderFor("priority").getDirection())
                    .isEqualTo(Sort.Direction.DESC);
            assertThat(of(0, 20, "priority,asc").getSort().getOrderFor("priority").getDirection())
                    .isEqualTo(Sort.Direction.ASC);
            assertThat(of(0, 20, "priority,DESC").getSort().getOrderFor("priority").getDirection())
                    .isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("omitting the direction sorts ascending")
        void bareProperty() {
            assertThat(of(0, 20, "priority").getSort().getOrderFor("priority").getDirection())
                    .isEqualTo(Sort.Direction.ASC);
        }

        /**
         * Regression test. {@code parseSort} used to decide the direction with
         * {@code equalsIgnoreCase("desc")}, so anything that was not exactly "desc" silently became
         * ascending. {@code ?sort=priority,descending} - a plausible way to ask for worst-first -
         * therefore returned the <em>least</em> urgent ticket at the top of a triage queue, with a 200
         * and nothing logged. A silently reversed list is worse than an error, because nobody looks for
         * it.
         */
        @Test
        @DisplayName("an unrecognised direction is a 400, never silently ascending")
        void unknownDirectionIsRejected() {
            assertThatThrownBy(() -> of(0, 20, "priority,descending"))
                    .isInstanceOf(InvalidReferenceException.class)
                    .hasMessageContaining("asc")
                    .hasMessageContaining("desc")
                    .hasMessageContaining("descending");

            assertThatThrownBy(() -> of(0, 20, "priority,down"))
                    .isInstanceOf(InvalidReferenceException.class);
            assertThatThrownBy(() -> of(0, 20, "priority,"))
                    .isInstanceOf(InvalidReferenceException.class);
        }

        @Test
        @DisplayName("a property outside the whitelist is a 400")
        void unknownPropertyIsRejected() {
            // The whitelist is also what makes it safe to hand the property to the Criteria API.
            assertThatThrownBy(() -> of(0, 20, "passwordHash"))
                    .isInstanceOf(InvalidReferenceException.class)
                    .hasMessageContaining("passwordHash");
        }

        @Test
        @DisplayName("more than one direction segment is a 400")
        void tooManySegments() {
            assertThatThrownBy(() -> of(0, 20, "priority,desc,extra"))
                    .isInstanceOf(InvalidReferenceException.class);
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated")
        void whitespaceTolerated() {
            assertThat(of(0, 20, " priority , desc ").getSort().getOrderFor("priority").getDirection())
                    .isEqualTo(Sort.Direction.DESC);
        }
    }

    @Nested
    @DisplayName("stable ordering")
    class Tiebreaker {

        /**
         * Offset paging over a non-unique sort key can show the same row on two pages and skip another
         * entirely, because the database is free to order equal keys differently between queries. Every
         * sort therefore ends in id.
         */
        @Test
        @DisplayName("id is appended as a tiebreaker, in the same direction")
        void idTiebreakerIsAdded() {
            Sort descending = of(0, 20, "priority,desc").getSort();
            assertThat(descending).containsExactly(
                    Sort.Order.desc("priority"), Sort.Order.desc("id"));

            Sort ascending = of(0, 20, "createdAt,asc").getSort();
            assertThat(ascending).containsExactly(
                    Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        }

        @Test
        @DisplayName("sorting by id does not append id twice")
        void idIsNotDuplicated() {
            assertThat(of(0, 20, "id,desc").getSort()).containsExactly(Sort.Order.desc("id"));
        }
    }

    @Nested
    @DisplayName("response headers")
    class Headers {

        @Test
        @DisplayName("pagination metadata travels in headers so the body stays a plain array")
        void headersDescribeThePage() {
            var page = new org.springframework.data.domain.PageImpl<>(
                    java.util.List.of("a", "b"),
                    org.springframework.data.domain.PageRequest.of(1, 2),
                    7);

            var headers = PageRequests.headers(page);

            assertThat(headers.getFirst("X-Total-Count")).isEqualTo("7");
            assertThat(headers.getFirst("X-Total-Pages")).isEqualTo("4");
            assertThat(headers.getFirst("X-Page-Number")).isEqualTo("1");
            assertThat(headers.getFirst("X-Page-Size")).isEqualTo("2");
            assertThat(headers.getFirst("X-Has-Next")).isEqualTo("true");
        }
    }
}
