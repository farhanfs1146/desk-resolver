package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.request.ChangePasswordRequest;
import com.forward.desk_resolver.dto.request.CreateUserRequest;
import com.forward.desk_resolver.dto.request.ReplaceUserRolesRequest;
import com.forward.desk_resolver.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    UserResponse getUserById(Long id);

    /**
     * One page of users.
     *
     * <p>Replaces the previous {@code getAllUsers()}, which returned every row with no upper bound - the
     * audit measured a 787 KB response for 5,103 users. Phase 4 made the endpoint require
     * {@code USER_READ}, so it is no longer an anonymous disclosure, but it was still unbounded. There is
     * deliberately no unbounded variant any more.
     */
    Page<UserResponse> searchUsers(Pageable pageable);

    /**
     * Replaces the calling user's own password.
     *
     * <p>Closes a contradiction rather than adding a feature. {@code BootstrapAdminInitializer} logs
     * "change this password after first sign-in", and the README says the same - but there was
     * no endpoint through which anyone could, so the only administrator a fresh deployment has was
     * permanently stuck on a password that had been typed into a deployment script.
     *
     * <p>The caller is always the authenticated user; there is no id parameter, so this cannot be used
     * to set somebody else's password.
     *
     * <p><strong>Every session the user holds ends, including this one.</strong> Until
     * {@code auth.sessions} existed this was impossible, and docs/DECISIONS.md recorded the gap: a
     * password change stopped new tokens being minted but did not cut off a session someone already
     * held, which is most of the point of changing a password you believe is compromised.
     *
     * @throws com.forward.desk_resolver.security.InvalidCredentialsException if
     *         {@code currentPassword} does not match, or the account has no password set
     */
    void changeOwnPassword(ChangePasswordRequest request);

    /**
     * Sets the complete list of roles a user holds.
     *
     * <p>The administrative half of the many-to-many that {@code V17} introduced: without it, roles
     * could only be granted at account creation and changed with SQL.
     *
     * @throws com.forward.desk_resolver.common.exception.ResourceNotFoundException  if no such user
     * @throws com.forward.desk_resolver.common.exception.InvalidReferenceException  if a role code does
     *         not exist - a 400 naming it, never a silent omission
     * @throws com.forward.desk_resolver.common.exception.LockoutPreventedException  if the change would
     *         leave no active account holding {@code USER_MANAGE}
     */
    UserResponse replaceRoles(Long userId, ReplaceUserRolesRequest request);
}
