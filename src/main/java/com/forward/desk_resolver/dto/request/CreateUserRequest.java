package com.forward.desk_resolver.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

@Data
public class CreateUserRequest {

    @NotNull(message = "Employee code is required")
    @Schema(description = "Employee unique code like card numbers or cnic etc.", example = "13411")
    private Long employeeCode;

    @NotBlank(message = "Full name is required")
    @Size(max = 150, message = "Full name must be at most 150 characters")
    @Schema(description = "Full name", example = "Farhan Ali")
    private String fullName;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    @Size(max = 100, message = "Email must be at most 100 characters")
    private String email;

    @Schema(description = "Employee department id which will refer as foreign key", example = "1")
    private Long departmentId;

    @Schema(description = "Employee designation id which will refer as foreign key", example = "1")
    private Long designationId;

    @NotBlank(message = "An initial password is required")
    @Size(min = 12, max = 200, message = "Password must be between 12 and 200 characters")
    @Schema(description = "Initial password. Stored only as a BCrypt hash and never returned.",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    /**
     * The roles the account is created with.
     *
     * <p>A set of codes rather than a single value, because a user can hold several roles, and
     * {@code @NotEmpty} rather than a default of the least-privileged role, because an account created
     * by omission is an account nobody decided on. An unknown code is a 400 naming it; it is never
     * silently dropped, or a typo would create an account with no capabilities and answer 201.
     *
     * <p>A {@code Set} also means a code repeated in the payload is accepted rather than being
     * inserted twice against the primary key of {@code auth.user_roles}.
     */
    @NotEmpty(message = "At least one role is required")
    @Schema(description = "Role codes, as listed by GET /api/roles")
    private Set<@NotBlank(message = "A role code must not be blank") String> roles;

    @NotNull(message = "Active status is required")
    @Schema(description = "whether employee will consider as active or not", example = "true")
    private Boolean active;
}
