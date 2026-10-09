package com.forward.desk_resolver.security;

/**
 * The one role code that application code has to name by hand.
 *
 * <p>{@code BootstrapAdminInitializer} has to decide whether an administrator exists before creating
 * one, and has to grant something to the account it creates. That is the only place in the
 * application with a built-in opinion about a specific role; every other role comes from a request or
 * from the database.
 *
 * <p><strong>This is not the old {@code Role} enum under another name.</strong> The enum enumerated
 * every role in the system and was the key to a compiled permission map, so adding a role meant a
 * redeploy. Roles now live in {@code auth.roles}. This constant is a <em>lookup key</em>: it is
 * resolved against the table and the bootstrap fails loudly if the role is not there, rather than
 * assuming the role exists because a Java constant mentions it.
 *
 * <p>Note what is deliberately absent: a default role for user creation.
 * {@code CreateUserRequest.roles} is {@code @NotEmpty}, so an administrator must say what an account
 * may do. A silent default would mean privileges granted by omission, which is the kind of grant
 * nobody reviews.
 */
public final class SystemRoles {

    /** Holds every permission. Seeded by {@code V17}. */
    public static final String ADMIN = "ADMIN";

    private SystemRoles() {
    }
}
