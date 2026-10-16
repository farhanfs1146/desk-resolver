package com.forward.desk_resolver.repository.projection;

/**
 * One row of a {@code group by} count over tickets: the value grouped on, and how many tickets hold it.
 *
 * <p>Generic in the grouped value rather than two near-identical records for status and priority. The
 * two queries differ only in the column they group on, and nothing downstream treats the results
 * differently - both are turned into a {@code Map<name, count>} for the response.
 *
 * <p><strong>Absent values do not appear.</strong> {@code group by} returns groups that exist, so a
 * status no ticket currently holds is simply missing from the list rather than present with a zero.
 * Filling those zeroes in is the service's job, not the query's - the API should report every status
 * the enum defines, so that a client can render a complete breakdown without knowing the enum.
 *
 * @param value the grouped value; never null, because both columns are {@code NOT NULL}
 * @param count how many tickets are in that group; always at least 1
 */
public record TicketCountRow<T>(T value, long count) {
}
