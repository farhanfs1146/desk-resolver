package com.forward.desk_resolver.repository.projection;

/**
 * Flat read projection for user listings — exactly the columns {@code UserResponse} needs from
 * {@code auth.users}.
 *
 * <p><strong>Why a projection when {@code User} has no associations.</strong> There is no N+1 problem to
 * solve here, unlike tickets. The reason is narrower and specific to this table: {@code users} carries
 * {@code password_hash}, and loading the entity selects it for every row in the listing. A directory
 * listing has no business reading password hashes out of the database at all.
 *
 * <p>It never reached a client — {@code UserResponse} has no such field — so this is defence in depth
 * rather than a fix for a leak. But a hash that is never read cannot be logged by accident, cannot appear
 * in a heap dump of a list request, and cannot be exposed by someone later adding a field to the response
 * DTO without thinking. The cheapest way to protect a secret is not to fetch it.
 *
 * <p><strong>No role field, as of Phase 7.</strong> A user holds any number of roles, so a role cannot be
 * a column on a flat row without multiplying it. {@code UserRoleRepository.findRoleCodesByUserIds} fetches
 * the codes for an entire page in one further query and the service joins them in memory.
 */
public record UserRow(
        Long id,
        Long employeeCode,
        String fullName,
        String email,
        Long departmentId,
        Long designationId,
        Boolean active
) {
}
