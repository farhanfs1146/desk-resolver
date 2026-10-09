package com.forward.desk_resolver.service.impl;


import com.forward.desk_resolver.common.exception.DuplicateResourceException;
import com.forward.desk_resolver.common.exception.InvalidReferenceException;
import com.forward.desk_resolver.common.exception.LockoutPreventedException;
import com.forward.desk_resolver.common.exception.ResourceNotFoundException;
import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.dto.request.ChangePasswordRequest;
import com.forward.desk_resolver.dto.request.CreateUserRequest;
import com.forward.desk_resolver.dto.request.ReplaceUserRolesRequest;
import com.forward.desk_resolver.dto.response.UserResponse;
import com.forward.desk_resolver.entity.PermissionDefinition;
import com.forward.desk_resolver.entity.Role;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserRole;
import com.forward.desk_resolver.repository.RoleRepository;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.UserRoleRepository;
import com.forward.desk_resolver.repository.projection.UserRow;
import com.forward.desk_resolver.security.InvalidCredentialsException;
import com.forward.desk_resolver.security.Permission;
import com.forward.desk_resolver.security.SessionService;
import com.forward.desk_resolver.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final SessionService sessionService;

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {

        if (userRepository.existsByEmployeeCode(request.getEmployeeCode())) {
            throw new DuplicateResourceException("Employee code already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists");
        }

        // Resolved before anything is written: an unknown role code must fail the whole request, not
        // create an account and then skip a grant.
        List<Role> roles = resolveRoles(request.getRoles());

        User user = new User();
        user.setEmployeeCode(request.getEmployeeCode());
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setDepartmentId(request.getDepartmentId());
        user.setDesignationId(request.getDesignationId());
        user.setActive(request.getActive());
        // Hashed with BCrypt before it ever reaches the database. The plaintext is not logged,
        // not stored and not present on UserResponse.
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        User savedUser = userRepository.save(user);
        grant(savedUser.getId(), roles);

        log.info("User {} created with roles {} by user {}",
                savedUser.getId(), codesOf(roles), currentUserProvider.requireCurrentUserId());

        return mapToResponse(savedUser, codesOf(roles));
    }

    /**
     * Reads one user, enforcing that a caller without {@link Permission#USER_READ} may only read their
     * own record.
     *
     * <p>Without this, any authenticated user could walk {@code /api/users/1..n} and rebuild the
     * directory that locking down {@code GET /api/users} was meant to protect - the collection endpoint
     * and the item endpoint have to be guarded together or neither is guarded.
     *
     * <p>Answers 404 rather than 403 for someone else's record, for the same
     * no-existence-disclosure reason as {@code TicketServiceImpl.getTicketById}.
     */
    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {

        if (!currentUserProvider.hasPermission(Permission.USER_READ)
                && !currentUserProvider.requireCurrentUserId().equals(id)) {
            throw ResourceNotFoundException.of("User", id);
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));

        return mapToResponse(user, userRoleRepository.findRoleCodesByUserId(id));
    }

    /**
     * One page of users, read through a projection.
     *
     * <p>The projection is not about N+1 - {@code User} has no mapped associations. It is so that a
     * directory listing never selects {@code password_hash} from the database; see {@code UserRow}.
     *
     * <p>Roles are fetched for the whole page in a single further query and joined in memory. Asking
     * per user would be an N+1 of exactly the kind Phase 5 removed from the ticket list, and joining
     * them into the page query itself is not an option: a user with three roles would occupy three rows
     * and both the page size and the total count would be wrong.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> searchUsers(Pageable pageable) {
        Page<UserRow> rows = userRepository.findUserRows(pageable);

        Map<Long, List<String>> rolesByUser =
                rolesFor(rows.getContent().stream().map(UserRow::id).toList());

        return rows.map(row -> mapRow(row, rolesByUser.getOrDefault(row.id(), List.of())));
    }

    /**
     * Replaces the calling user's own password (see {@link UserService#changeOwnPassword}).
     *
     * <p>Five properties worth stating, because each is a way this endpoint could have gone wrong:
     *
     * <ul>
     *   <li><strong>No id parameter.</strong> The target is always
     *       {@code currentUserProvider.requireCurrentUserId()}. An endpoint that took a user id would
     *       need its own authorization rule, and the version of that rule that is wrong is the one
     *       that lets a caller set somebody else's password.
     *   <li><strong>The current password is verified.</strong> Without it a stolen or leaked access
     *       token would be enough to lock the real owner out permanently.
     *   <li><strong>An account with no password set cannot use this.</strong> A {@code NULL} hash
     *       means "cannot authenticate" (V13), and treating it as "no current password required"
     *       would turn every pre-existing passwordless account into one any token holder could claim.
     *       It fails the same way a wrong password does.
     *   <li><strong>The plaintext is hashed here and nowhere stored or logged.</strong> Only the user
     *       id reaches the log.
     *   <li><strong>Every session the user holds is revoked, this one included.</strong> New since
     *       {@code auth.sessions}: the limitation docs/DECISIONS.md used to record - that a password
     *       change could not end a session somebody already held - was most of the reason to change a
     *       password you believe is compromised. The client has the new password, so the cost is one
     *       re-authentication; leaving the current session alive to avoid that would be answering the
     *       easy half of the request.
     * </ul>
     */
    @Override
    @Transactional
    public void changeOwnPassword(ChangePasswordRequest request) {
        Long userId = currentUserProvider.requireCurrentUserId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        String storedHash = user.getPasswordHash();
        if (storedHash == null || storedHash.isBlank()) {
            log.info("Password change rejected for user {}: no password is set", userId);
            throw new InvalidCredentialsException("The current password is incorrect");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), storedHash)) {
            log.info("Password change rejected for user {}: current password did not match", userId);
            throw new InvalidCredentialsException("The current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        sessionService.revokeAllForUser(userId, SessionService.RevocationReason.PASSWORD_CHANGED);
        log.info("Password changed for user {}; all sessions revoked", userId);
    }

    /**
     * Sets the complete set of roles a user holds (see {@link UserService#replaceRoles}).
     *
     * <p>Delete-then-insert rather than a diff. The endpoint is defined as "these are the roles
     * afterwards", so the previous set is not interesting, and a diff would be more code computing an
     * answer the two statements already produce. Both run in one transaction, so there is no moment at
     * which the user holds a partial set.
     *
     * <p>No {@code @Version} on {@code User} is involved and none is needed: the grants are the thing
     * being changed, and the last writer of a complete set wins by definition.
     */
    @Override
    @Transactional
    public UserResponse replaceRoles(Long userId, ReplaceUserRolesRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        List<Role> roles = resolveRoles(request.getRoles());
        guardAgainstLockout(userId, roles);

        userRoleRepository.deleteByUserId(userId);
        // Forces the DELETE out before the INSERTs. Without it Hibernate is free to order the bulk
        // delete after the inserts it has queued, and re-granting a role the user already held would
        // collide with the primary key of auth.user_roles.
        userRoleRepository.flush();
        grant(userId, roles);

        List<String> codes = codesOf(roles);
        log.info("Roles for user {} set to {} by user {}",
                userId, codes, currentUserProvider.requireCurrentUserId());

        return mapToResponse(user, codes);
    }

    /**
     * Refuses to remove {@code USER_MANAGE} from the last account that holds it.
     *
     * <p>Nothing else in the application could undo that change: user administration is the capability
     * being removed, and the bootstrap administrator only runs when no administrator exists at all, so
     * it would not step in either. Recovery would mean hand-editing {@code auth.user_roles}.
     *
     * <p>Note which case this can actually fire in. The caller must hold {@code USER_MANAGE} to reach
     * this endpoint, so when they are editing somebody else there is always at least one other holder -
     * themselves - and the count cannot be zero. It therefore only ever fires on an administrator
     * removing their own last administrative role, which is exactly the mistake worth catching.
     */
    private void guardAgainstLockout(Long userId, Collection<Role> newRoles) {
        if (grants(newRoles, Permission.USER_MANAGE)) {
            return;
        }
        if (userRoleRepository.countOtherActiveHoldersOfPermission(
                Permission.USER_MANAGE.name(), userId) == 0) {
            throw new LockoutPreventedException(
                    "This would leave no active account able to manage users. Grant "
                            + Permission.USER_MANAGE.name() + " to another account first.");
        }
    }

    /**
     * Whether any of these roles grants the permission.
     *
     * <p>Walks the lazy {@code permissions} association, which is a query per role. That is acceptable
     * precisely here and nowhere on the request path: this is an administrative write against a handful
     * of roles, each holding at most nine permissions.
     */
    private static boolean grants(Collection<Role> roles, Permission permission) {
        return roles.stream()
                .filter(role -> Boolean.TRUE.equals(role.getActive()))
                .flatMap(role -> role.getPermissions().stream())
                .map(PermissionDefinition::getCode)
                .anyMatch(permission.name()::equals);
    }

    /**
     * Turns requested role codes into roles, reporting every unknown one.
     *
     * <p>All of them, not the first: an administrator who mistyped two codes should learn both in one
     * response rather than fixing one and resubmitting to discover the next.
     */
    private List<Role> resolveRoles(Set<String> requestedCodes) {
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            return List.of();
        }

        Set<String> wanted = new TreeSet<>(requestedCodes);
        List<Role> found = roleRepository.findByCodeIn(wanted);

        if (found.size() != wanted.size()) {
            Set<String> missing = new TreeSet<>(wanted);
            found.forEach(role -> missing.remove(role.getCode()));
            throw new InvalidReferenceException(
                    "roles does not reference an existing record: " + String.join(", ", missing));
        }
        return found;
    }

    private void grant(Long userId, Collection<Role> roles) {
        if (roles.isEmpty()) {
            return;
        }
        Long grantedBy = currentUserProvider.requireCurrentUserId();
        List<UserRole> grants = new ArrayList<>(roles.size());
        for (Role role : roles) {
            grants.add(UserRole.of(userId, role.getId(), grantedBy));
        }
        userRoleRepository.saveAll(grants);
    }

    /** Role codes for a page of users, in one query, grouped by user id. */
    private Map<Long, List<String>> rolesFor(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> byUser = new LinkedHashMap<>();
        for (Object[] pair : userRoleRepository.findRoleCodesByUserIds(userIds)) {
            Long userId = ((Number) pair[0]).longValue();
            byUser.computeIfAbsent(userId, key -> new ArrayList<>()).add((String) pair[1]);
        }
        return byUser;
    }

    private static List<String> codesOf(Collection<Role> roles) {
        return roles.stream().map(Role::getCode).sorted().toList();
    }

    /** Maps a read projection to the response DTO. Field names and types are unchanged. */
    private static UserResponse mapRow(UserRow row, List<String> roles) {
        return UserResponse.builder()
                .id(row.id())
                .employeeCode(row.employeeCode())
                .fullName(row.fullName())
                .email(row.email())
                .departmentId(row.departmentId())
                .designationId(row.designationId())
                .roles(roles)
                .active(row.active())
                .build();
    }

    /** Entity-based mapping, still used by create, single-user read and the role replacement. */
    private static UserResponse mapToResponse(User user, List<String> roles) {
        return UserResponse.builder()
                .id(user.getId())
                .employeeCode(user.getEmployeeCode())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .departmentId(user.getDepartmentId())
                .designationId(user.getDesignationId())
                .roles(roles)
                .active(user.getActive())
                .build();
    }
}
