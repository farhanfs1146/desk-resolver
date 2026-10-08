package com.forward.desk_resolver.repository;


import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.Role;
import com.forward.desk_resolver.repository.projection.UserRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** The authentication lookup; needs the entity because it verifies the password hash. */
    Optional<User> findByEmail(String email);

    /**
     * Uniqueness pre-checks.
     *
     * <p>{@code exists} rather than {@code findBy...().isPresent()}: the callers only ask whether a row
     * is there, and loading a whole {@code User} - {@code password_hash} included - to answer a yes/no
     * question reads columns nobody looks at. The database answers this with
     * {@code select 1 ... limit 1} against {@code users_email_key} / {@code users_employee_code_key}.
     *
     * <p>Neither is a substitute for the unique constraints: two concurrent creates can both pass the
     * check, and the constraint is what actually refuses the second one. The check exists to turn the
     * common case into a clear 409 instead of a database error.
     */
    boolean existsByEmail(String email);

    boolean existsByEmployeeCode(Long employeeCode);

    /** Used by the bootstrap administrator check; counts rather than loads, so it stays cheap. */
    long countByRole(Role role);

    /**
     * One page of users, projected so a listing never reads {@code password_hash} (Phase 6).
     *
     * <p>Unlike the ticket list, this is not about N+1 - {@code User} has no associations. It is about not
     * selecting a password hash to build a directory listing; see {@link UserRow}.
     *
     * <p>An explicit {@code countQuery} is required because the main query is a constructor expression,
     * which Spring Data cannot rewrite into a count on its own. With it, a page costs two statements
     * whatever the number of users.
     */
    @Query(value = """
            select new com.forward.desk_resolver.repository.projection.UserRow(
                u.id, u.employeeCode, u.fullName, u.email, u.departmentId, u.designationId,
                u.role, u.active)
            from User u
            """,
            countQuery = "select count(u.id) from User u")
    Page<UserRow> findUserRows(Pageable pageable);
}
