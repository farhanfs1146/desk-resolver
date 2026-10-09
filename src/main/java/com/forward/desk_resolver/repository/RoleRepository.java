package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    /** Resolves a well-known code - {@code SystemRoles.ADMIN} - against the table rather than assuming it. */
    Optional<Role> findByCode(String code);

    /**
     * Resolves a set of codes supplied by a caller.
     *
     * <p>Returns only the ones that exist, so the caller compares sizes and reports the missing codes
     * as a 400. Unknown roles must never be silently dropped: a request to make somebody an
     * {@code ADMNI} would otherwise create an account with no roles at all and answer 201.
     */
    List<Role> findByCodeIn(Collection<String> codes);

    /**
     * Every role with its permissions, for the administrative read.
     *
     * <p>{@code join fetch} rather than lazy traversal: without it, listing seven roles costs eight
     * queries. {@code distinct} because the fetch join multiplies the role rows by their permissions.
     */
    @Query("select distinct r from Role r left join fetch r.permissions order by r.code")
    List<Role> findAllWithPermissions();
}
